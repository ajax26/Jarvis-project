package com.doorskorea.singkro.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import com.doorskorea.singkro.ai.IntentHandler
import com.doorskorea.singkro.ai.IntentPrompt
import com.doorskorea.singkro.ai.KmaGrid
import com.doorskorea.singkro.ai.LlmEngine
import com.doorskorea.singkro.ai.ModelFamily
import com.doorskorea.singkro.ai.SpeechInput
import com.doorskorea.singkro.ai.SpeechOutput
import com.doorskorea.singkro.ai.WakeWordListener
import com.doorskorea.singkro.ai.WeatherSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Always-on assistant: waits for the wake word ("씽크"), listens to one request, answers by voice, repeats.
 * Runs as a microphone foreground service, so it must be started while the app is on screen.
 */
class AssistantService : Service() {
    private val scope = MainScope()
    private var loop: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground("준비 중...")
        val tokens = intent?.getStringExtra(EXTRA_WAKE_TOKENS)?.split(",")?.toSet() ?: WAKE_TOKENS
        if (loop == null) loop = scope.launch { run(tokens) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun run(tokens: Set<String>) {
        val speechIn = SpeechInput(this)
        val speechOut = SpeechOutput(this)
        var wake: WakeWordListener? = null
        try {
            val model = modelFile(this)
            val vosk = voskDir(this)
            if (!model.exists()) {
                update("모델 없음: ${model.absolutePath} 을 adb push 하세요")
                AssistantBus.error("AI 모델이 없습니다: ${model.name}")
                return
            }
            if (!LlmEngine.isReady) {
                update("모델 로딩 중...")
                LlmEngine.init(this, model.absolutePath, preferGpu = false) // CPU: see MainActivity.preferGpu
                IntentPrompt.intent("안녕", FAMILY).let { LlmEngine.generate(it.prefix, it.rest) } // cache system prompt
            }
            val grid = KmaGrid(assets.open("kma_grid.json").bufferedReader().use { it.readText() })
            val handler = IntentHandler(this, FAMILY, WeatherSource(BuildConfig.KMA_AUTH_KEY, grid))
            // without the Vosk model the HUD's mic button and typed questions still work, just no wake word
            wake = if (vosk.exists()) withContext(Dispatchers.IO) { WakeWordListener(vosk.absolutePath, tokens) } else null
            if (wake == null) AssistantBus.error("호출어 모델이 없어 MIC 버튼으로만 들을 수 있습니다: ${vosk.name}")

            AssistantBus.system("SINGKRO online. '씽크'라고 불러 주세요.")
            while (scope.isActive) {
                update("대기 중: '씽크'라고 불러 주세요")
                AssistantBus.setState("idle")
                val request = nextRequest(wake)
                Log.i(TAG, "request $request")

                val text = if (request is AssistantBus.Request.Ask) {
                    request.text
                } else {
                    if (request is AssistantBus.Request.Wake) speechOut.speakAndWait("네")
                    update("듣는 중...")
                    AssistantBus.setState("listening")
                    speechIn.listen()
                }
                if (text.isNullOrBlank()) {
                    Log.i(TAG, "nothing recognised (error ${speechIn.lastError})")
                    AssistantBus.error("잘 못 들었어요. 다시 말씀해 주세요.")
                    AssistantBus.setState("speaking")
                    speechOut.speakAndWait("잘 못 들었어요.")
                    continue
                }

                update("처리 중: $text")
                AssistantBus.user(text)
                AssistantBus.setState("thinking")
                AssistantBus.replyStart()
                val reply = try {
                    handler.handle(text).text
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "handle failed", e)
                    "죄송해요, 처리하지 못했어요."
                }
                Log.i(TAG, "wake ask \"$text\" -> $reply")
                AssistantBus.replyText(reply)
                AssistantBus.setState("speaking")
                speechOut.speakAndWait(reply)
                AssistantBus.replyDone()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "assistant loop stopped", e)
            update("오류로 중지됨: ${e.message}")
        } finally {
            wake?.close()
            speechIn.destroy()
            speechOut.shutdown()
        }
    }

    /** Waits for the wake word or a HUD request (mic button / typed text), whichever comes first. */
    private suspend fun nextRequest(wake: WakeWordListener?): AssistantBus.Request = coroutineScope {
        if (wake == null) return@coroutineScope AssistantBus.requests.receive()
        val heard = async { wake.await(); AssistantBus.Request.Wake }
        // cancelling the wake job releases the microphone before the recognizer needs it
        select { heard.onAwait { it }; AssistantBus.requests.onReceive { it } }.also { heard.cancel() }
    }

    private fun startInForeground(text: String) {
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun update(text: String) {
        Log.i(TAG, text)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "SINGKRO 비서", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("SINGKRO 비서")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "중지", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AssistantService"
        private const val CHANNEL = "assistant"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"
        const val EXTRA_WAKE_TOKENS = "wake_tokens"

        val FAMILY = ModelFamily.QWEN

        // "씽크" as Vosk transcribes it (pc-test/wakeword_test.py: 12/15 detected, 0/48 false wakes).
        // Retune after real-voice tests, or override: am start-foreground-service ... --es wake_tokens 싱크,신키
        val WAKE_TOKENS = setOf("싱크", "신키")

        fun modelFile(c: Context) = File(c.getExternalFilesDir(null), "model.task")
        fun voskDir(c: Context) = File(c.getExternalFilesDir(null), "vosk-ko")

        fun start(c: Context) = c.startForegroundService(Intent(c, AssistantService::class.java))
        fun stop(c: Context) = c.startService(Intent(c, AssistantService::class.java).setAction(ACTION_STOP))
    }
}
