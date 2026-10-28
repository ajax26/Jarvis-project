package com.doorskorea.singkro.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.util.Log
import com.doorskorea.singkro.ai.IntentHandler
import com.doorskorea.singkro.ai.IntentPrompt
import com.doorskorea.singkro.ai.KmaGrid
import com.doorskorea.singkro.ai.LlmEngine
import com.doorskorea.singkro.ai.ModelFamily
import com.doorskorea.singkro.ai.SpeechInput
import com.doorskorea.singkro.ai.SpeechOutput
import com.doorskorea.singkro.ai.WakePhrase
import com.doorskorea.singkro.ai.WakeWordListener
import com.doorskorea.singkro.ai.WeatherSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Always-on assistant: waits for the wake word ("씽씽"), listens to one request, answers by voice, repeats.
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
        val source = intent?.getStringExtra(EXTRA_MIC_SOURCE)?.let { MIC_SOURCES[it.uppercase()] } ?: MIC_SOURCE
        val vosk = intent?.getStringExtra(EXTRA_WAKE_ENGINE) == "vosk"
        if (loop == null) loop = scope.launch { run(vosk, tokens, source) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        quietChimes(false) // never leave the device's notification sounds turned down
        super.onDestroy()
    }

    /** [useVosk]: offline Vosk wake word "싱크" instead of "씽씽" through the platform recognizer (the default). */
    private suspend fun run(useVosk: Boolean, tokens: Set<String>, micSource: Int) {
        AssistantBus.init(this)
        restoreChimes()
        // the HUD toggle applies at once, not just at the next listen
        scope.launch { AssistantBus.wakeSound.collect { on -> if (waitingForWake) quietChimes(!on) } }
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
            wake = if (useVosk && vosk.exists()) {
                withContext(Dispatchers.IO) {
                    WakeWordListener(
                        vosk.absolutePath, tokens, micSource, usbMic(),
                        onLevel = { peak -> AssistantBus.mic("wake", peak / 32767f, clipped = peak >= CLIP_PEAK) },
                        onHeard = AssistantBus::heard,
                    )
                }
            } else null
            if (useVosk && wake == null) AssistantBus.error("호출어 모델이 없어 MIC 버튼으로만 들을 수 있습니다: ${vosk.name}")
            val callWake = if (useVosk) "'싱크'라고" else "'씽씽'이라고"
            val awaitWake: suspend () -> AssistantBus.Request = wake?.let { w -> { w.await(); AssistantBus.Request.Wake } }
                ?: { awaitSpokenWake(speechIn) }

            AssistantBus.system("SINGKRO online. $callWake 불러 주세요.")
            while (scope.isActive) {
                update("대기 중: $callWake 불러 주세요")
                AssistantBus.setState("idle")
                val request = nextRequest(awaitWake)
                AssistantBus.mic("off")
                Log.i(TAG, "request $request")

                val text = if (request is AssistantBus.Request.Ask) {
                    request.text
                } else {
                    // a short beep instead of saying "네": ~0.15 s instead of ~0.7 s before the question can start
                    if (request is AssistantBus.Request.Wake) beep()
                    update("듣는 중...")
                    AssistantBus.setState("listening")
                    // rms dB is roughly -2 (silence) .. 10 (loud speech)
                    speechIn.listen(onRms = { db -> AssistantBus.mic("ask", ((db + 2f) / 12f).coerceIn(0f, 1f)) })
                        .also { AssistantBus.mic("off") }
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
    private suspend fun nextRequest(awaitWake: suspend () -> AssistantBus.Request): AssistantBus.Request = coroutineScope {
        val heard = async { awaitWake() }
        // wait for the wake job to release the microphone before the recognizer needs it
        select { heard.onAwait { it }; AssistantBus.requests.onReceive { it } }.also { heard.cancelAndJoin() }
    }

    /**
     * "씽씽" through the platform recognizer, listening one utterance after another (as the SingTree sample did).
     * Returns [AssistantBus.Request.Ask] when the request came in the same breath ("씽씽, 오늘 무슨 요일이야?").
     * Not offline: the recognizer may send the audio to Google.
     */
    private suspend fun awaitSpokenWake(speechIn: SpeechInput): AssistantBus.Request {
        waitingForWake = true
        quietChimes(!AssistantBus.wakeSound.value)
        try {
            return listenForWake(speechIn)
        } finally {
            // the question that follows keeps its start chime: it tells the user to speak
            waitingForWake = false
            quietChimes(false)
        }
    }

    private suspend fun listenForWake(speechIn: SpeechInput): AssistantBus.Request {
        while (true) {
            // the final result sometimes drops a leading "씽씽" that the partials had
            // ("씽씽 오늘 주요 뉴스 알려 줘" -> "오늘 주요 뉴스 알려 줘"), so a wake word in any partial counts too
            var partialWake: String? = null
            val text = speechIn.listen(
                onPartial = { p ->
                    AssistantBus.heard(p)
                    if (partialWake == null && WakePhrase.after(p) != null) partialWake = p
                },
                onRms = { db -> AssistantBus.mic("wake", ((db + 2f) / 12f).coerceIn(0f, 1f)) },
            )
            if (text.isNullOrBlank()) {
                // no speech / no match just mean silence; anything else (network, busy) retries more slowly
                val quiet = speechIn.lastError in QUIET_ERRORS
                if (!quiet) Log.w(TAG, "wake listen error ${speechIn.lastError}")
                delay(if (quiet) 250 else 1000)
                continue
            }
            AssistantBus.heard(text)
            val rest = WakePhrase.after(text) ?: partialWake?.let { text }
            Log.d(
                TAG,
                "wake heard \"$text\"" + (partialWake?.let { " (partial \"$it\")" } ?: "") +
                    " -> " + if (rest == null) "ignored" else "wake, rest \"$rest\"",
            )
            if (rest == null) continue
            return if (rest.length >= 2) AssistantBus.Request.Ask(rest) else AssistantBus.Request.Wake
        }
    }

    private var waitingForWake = false
    private val prefs by lazy { getSharedPreferences("assistant_service", MODE_PRIVATE) }

    /**
     * The recognizer plays a chime on the notification stream each time it starts and stops listening, i.e. every
     * few seconds while waiting for the wake word. Quieting turns that stream's volume down (other notifications
     * too, meanwhile) and back up after. Not ADJUST_MUTE: on the RK3588 an app can mute that stream but not unmute it.
     * The saved volume is persisted so a killed process gets it back on the next start ([restoreChimes]).
     */
    private fun quietChimes(quiet: Boolean) {
        val audio = getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_NOTIFICATION
        val saved = prefs.getInt(PREF_CHIME_VOLUME, -1)
        try {
            if (quiet && saved < 0) {
                prefs.edit().putInt(PREF_CHIME_VOLUME, audio.getStreamVolume(stream)).commit()
                // 0 may switch the ringer to silent, which needs do-not-disturb access; the lowest audible step doesn't
                try {
                    audio.setStreamVolume(stream, 0, 0)
                } catch (e: SecurityException) {
                    audio.setStreamVolume(stream, audio.getStreamMinVolume(stream).coerceAtLeast(1), 0)
                }
            } else if (!quiet && saved >= 0) {
                audio.setStreamVolume(stream, saved, 0)
                prefs.edit().remove(PREF_CHIME_VOLUME).commit()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "can't change the notification volume", e)
        }
    }

    /** Undo what an earlier, killed process left: a lowered volume, or the mute an older build applied. */
    private fun restoreChimes() {
        quietChimes(false)
        val audio = getSystemService(AudioManager::class.java)
        if (audio.isStreamMute(AudioManager.STREAM_NOTIFICATION)) {
            audio.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
            Log.i(TAG, "notification stream was muted; unmuted=${!audio.isStreamMute(AudioManager.STREAM_NOTIFICATION)}")
        }
    }

    private suspend fun beep() {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        try {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
            delay(BEEP_MS.toLong()) // let it finish so the recognizer doesn't hear it
        } finally {
            tone.release()
        }
    }

    /** A USB input (wireless mic receiver) if Android lists one; the board's built-in mic may be unconnected. */
    private fun usbMic(): AudioDeviceInfo? {
        val inputs = getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
        Log.i(TAG, "inputs: " + inputs.joinToString { "${it.productName}/${it.type}" })
        return inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
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
        const val EXTRA_MIC_SOURCE = "mic_source"
        // "vosk": offline wake word "싱크" (unreliable with the RK3588 wireless mic); default "씽씽" via the recognizer
        const val EXTRA_WAKE_ENGINE = "wake_engine"

        // SpeechRecognizer.ERROR_SPEECH_TIMEOUT, ERROR_NO_MATCH: nobody spoke
        private val QUIET_ERRORS = setOf(6, 7)

        // peaks at or above this are treated as clipping (full scale is 32767)
        private const val CLIP_PEAK = 32000
        private const val BEEP_MS = 150
        private const val PREF_CHIME_VOLUME = "chime_volume"

        // wake word mic source; try others without reinstalling: am start-foreground-service ... --es mic_source MIC
        val MIC_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
        val MIC_SOURCES = mapOf(
            "DEFAULT" to MediaRecorder.AudioSource.DEFAULT,
            "MIC" to MediaRecorder.AudioSource.MIC,
            "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,
            "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED,
            "VOICE_PERFORMANCE" to MediaRecorder.AudioSource.VOICE_PERFORMANCE,
        )

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
