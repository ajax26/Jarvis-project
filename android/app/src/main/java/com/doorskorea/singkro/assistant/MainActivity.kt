package com.doorskorea.singkro.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ScrollView
import android.widget.TextView
import com.doorskorea.singkro.ai.AssistantIntent
import com.doorskorea.singkro.ai.IntentHandler
import com.doorskorea.singkro.ai.IntentParser
import com.doorskorea.singkro.ai.IntentPrompt
import com.doorskorea.singkro.ai.KmaGrid
import com.doorskorea.singkro.ai.LlmEngine
import com.doorskorea.singkro.ai.ModelFamily
import com.doorskorea.singkro.ai.SpeechInput
import com.doorskorea.singkro.ai.SpeechOutput
import com.doorskorea.singkro.ai.WeatherSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Test screen for step 1-2: loads the on-device model, runs single utterances through the
 * full pipeline, and runs the shared intent eval (eval/intent_cases.json) to measure accuracy and speed.
 */
class MainActivity : Activity() {
    private val scope = MainScope()
    private val family = ModelFamily.QWEN

    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var log: TextView
    private lateinit var handler: IntentHandler
    private lateinit var speechIn: SpeechInput
    private lateinit var speechOut: SpeechOutput
    private var readyText = ""

    private val modelFile get() = AssistantService.modelFile(this)

    // CPU by default: on the MT8781 tablet GPU took ~21 s per intent vs ~3 s on CPU. To try GPU:
    // adb shell am start -n com.doorskorea.singkro.assistant/.MainActivity --es backend GPU
    private val preferGpu get() = intent.getStringExtra("backend") == "GPU"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handler = IntentHandler(this, family, WeatherSource(BuildConfig.KMA_AUTH_KEY, KmaGrid(assets.open("kma_grid.json").bufferedReader().use { it.readText() })))
        speechIn = SpeechInput(this)
        speechOut = SpeechOutput(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this)
        input = EditText(this).apply {
            hint = "예: 아이유 좋은날 틀어줘"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ -> (id == EditorInfo.IME_ACTION_SEND).also { if (it) sendInput() } }
        }
        log = TextView(this).apply { setTextIsSelectable(true) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(LinearLayout(context).apply {
                addView(Button(context).apply { text = "말하기"; setOnClickListener { onMic() } })
                addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(context).apply { text = "보내기"; setOnClickListener { sendInput() } })
                addView(Button(context).apply { text = "평가 실행"; setOnClickListener { runEval() } })
                addView(Button(context).apply { text = "상시 대기"; setOnClickListener { toggleService(this) } })
            })
            addView(ScrollView(context).apply { addView(log) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // targetSdk 35 draws edge-to-edge: keep content clear of the status bar and keyboard
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom)
                insets
            }
        }
        setContentView(root)
        loadModel()
    }

    // test hook when the app is already running (same --es ask extra)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("ask")?.let { ask(it) }
    }

    override fun onDestroy() {
        scope.cancel()
        speechIn.destroy()
        speechOut.shutdown()
        super.onDestroy()
    }

    private fun loadModel() = scope.launch {
        if (LlmEngine.isReady) {
            status.text = "준비 완료 (${LlmEngine.backend})"
            return@launch
        }
        val file = modelFile
        if (!file.exists()) {
            status.text = "모델 파일이 없습니다. PC에서 실행:\nadb push <모델>.task ${file.absolutePath}\n그다음 앱을 다시 실행하세요."
            return@launch
        }
        status.text = "모델 로딩 중... (${file.length() / 1_000_000} MB)"
        try {
            val ms = measureTimeMillis { LlmEngine.init(this@MainActivity, file.absolutePath, preferGpu) }
            Log.i(TAG, "model loaded backend=${LlmEngine.backend} ms=$ms")
            // first call pays for the system prompt (~25 s on CPU); do it now so the user's first question is fast
            status.text = "시스템 프롬프트 준비 중... (${LlmEngine.backend})"
            val warmMs = measureTimeMillis {
                IntentPrompt.intent("안녕", family).let { LlmEngine.generate(it.prefix, it.rest) }
            }
            readyText = "준비 완료: ${LlmEngine.backend}, 로딩 ${ms / 1000.0}s + 준비 ${warmMs / 1000.0}s, 음성인식 ${speechIn.engine}"
            status.text = readyText
            Log.i(TAG, "warm-up ms=$warmMs")
            // test hook (adb can't type Korean): adb shell am start ... --es ask "내일 부산 비 와?"
            intent.getStringExtra("ask")?.let { ask(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status.text = "모델 로딩 실패: ${e.message}"
            Log.e(TAG, "model load failed", e)
        }
    }

    private fun sendInput() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.text.clear()
        ask(text)
    }

    private fun onMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen()
    }

    // always-on mode: wake word "씽크" in a foreground service (AssistantService)
    private var serviceOn = false

    private fun toggleService(button: Button) {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_SERVICE)
            return
        }
        serviceOn = !serviceOn
        if (serviceOn) AssistantService.start(this) else AssistantService.stop(this)
        button.text = if (serviceOn) "대기 중지" else "상시 대기"
        append(if (serviceOn) "상시 대기 시작: '씽크'라고 불러 보세요. (상태는 알림에 표시)\n" else "상시 대기 중지\n")
    }

    private fun listen() {
        if (!LlmEngine.isReady) return
        if (!speechIn.isAvailable) {
            append("음성 인식 엔진이 없습니다.\n")
            return
        }
        speechOut.stop()
        scope.launch {
            status.text = "듣는 중..."
            val text = speechIn.listen { partial -> status.text = "듣는 중: $partial" }
            status.text = readyText
            if (text.isNullOrBlank()) {
                append("  (음성 인식 실패: 오류 ${speechIn.lastError}, ${speechIn.engine})\n")
                speechOut.speak("잘 못 들었어요. 다시 말씀해 주세요.")
            } else {
                ask(text)
            }
        }
    }

    private fun ask(text: String) {
        if (!LlmEngine.isReady) return
        scope.launch {
            try {
                val reply: IntentHandler.Reply
                val ms = measureTimeMillis { reply = handler.handle(text) }
                append("> $text\n  intent: ${reply.intent}\n  (${ms} ms) ${reply.text}\n")
                Log.i(TAG, "ask \"$text\" -> ${reply.intent} (${ms} ms): ${reply.text}")
                speechOut.speak(reply.text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append("> $text\n  오류: ${e.message}\n")
                Log.e(TAG, "handle failed", e)
            }
        }
    }

    private fun runEval() {
        if (!LlmEngine.isReady) return
        scope.launch {
            val cases = JSONArray(assets.open("intent_cases.json").bufferedReader().use { it.readText() })
            var ok = 0
            var totalMs = 0L
            append("=== 평가 시작: ${cases.length()}문장, ${LlmEngine.backend} ===\n")
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                val utterance = case.getString("utterance")
                val expected = case.getJSONObject("expected")
                val raw: String
                val ms = measureTimeMillis { raw = IntentPrompt.intent(utterance, family).let { LlmEngine.generate(it.prefix, it.rest) } }
                totalMs += ms
                val got = fields(IntentParser.parse(raw))
                // spacing-insensitive: "좋은 날" and "좋은날" hit the same catalog search
                val hit = expected.keys().asSequence().all { k ->
                    got[k]?.replace(" ", "") == expected.getString(k).replace(" ", "")
                }
                if (hit) ok++
                val line = "${if (hit) "OK  " else "FAIL"} ${ms}ms  $utterance -> ${raw.trim().take(100)}"
                append("$line\n")
                Log.i(EVAL_TAG, line)
            }
            val summary = "결과: $ok/${cases.length()} 정답, 평균 ${totalMs / cases.length()} ms (${LlmEngine.backend})"
            append("$summary\n")
            Log.i(EVAL_TAG, summary)
        }
    }

    // same keys as eval/intent_cases.json "expected"
    private fun fields(i: AssistantIntent): Map<String, String> = when (i) {
        is AssistantIntent.GetWeather -> mapOf("action" to "GET_WEATHER", "location" to i.location, "date" to i.date)
        is AssistantIntent.GetNews -> mapOf("action" to "GET_NEWS", "category" to i.category)
        is AssistantIntent.DeviceControl -> mapOf("action" to "DEVICE_CONTROL", "target" to i.target, "value" to i.value)
        is AssistantIntent.SearchSong -> mapOf("action" to "SEARCH_SONG", "query" to i.query)
        is AssistantIntent.PlaySong -> mapOf("action" to "PLAY_SONG", "query" to i.query)
        is AssistantIntent.Chitchat -> mapOf("action" to "CHITCHAT", "reply" to i.reply)
        is AssistantIntent.Unknown -> mapOf("action" to "UNKNOWN")
    }

    private fun append(text: String) {
        log.append(text)
        (log.parent as ScrollView).post { (log.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private companion object {
        const val TAG = "SingkroMain"
        const val EVAL_TAG = "SingkroEval"
        const val REQ_MIC = 1
        const val REQ_SERVICE = 2
    }
}
