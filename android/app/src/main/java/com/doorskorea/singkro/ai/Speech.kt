package com.doorskorea.singkro.ai

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Korean speech-to-text through the platform recognizer (Google on GMS devices).
 * Prefers the on-device recognizer; falls back to the default one if Korean isn't installed offline.
 * Create and call on the main thread.
 */
class SpeechInput(private val context: Context) {
    private val prefs = context.getSharedPreferences("speech", Context.MODE_PRIVATE)
    // skip an on-device recognizer that already failed on this device, instead of waiting it out on every start
    private var onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context) && !prefs.getBoolean(PREF_ON_DEVICE_UNUSABLE, false)
    private var recognizer = create()
    private val handler = Handler(Looper.getMainLooper())

    /** "on-device" or "default", for diagnostics. */
    val engine get() = if (onDevice) "on-device" else "default"
    val isAvailable get() = onDevice || SpeechRecognizer.isRecognitionAvailable(context)

    /** Last SpeechRecognizer.ERROR_* code, or null after a successful listen. */
    var lastError: Int? = null
        private set

    private fun create(): SpeechRecognizer =
        if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)

    /**
     * Listens for one utterance; returns null if nothing was recognised (see [lastError]).
     * [onRms] gets the recognizer's input level in dB (roughly -2..10) while it listens.
     */
    suspend fun listen(onPartial: (String) -> Unit = {}, onRms: (Float) -> Unit = {}): String? {
        val text = listenOnce(onPartial, onRms)
        // on-device recognizer without the Korean pack, or one that never starts (RK3588: the system's on-device
        // service isn't a real RecognitionService, bind fails with no callback): switch to the default for good
        if (text == null && onDevice && (lastError in LANGUAGE_ERRORS || lastError == ERROR_NEVER_STARTED)) {
            Log.w(TAG, "on-device recognizer unusable (error $lastError), using default recognizer")
            recognizer.destroy()
            onDevice = false
            prefs.edit().putBoolean(PREF_ON_DEVICE_UNUSABLE, true).apply()
            recognizer = create()
            return listenOnce(onPartial, onRms)
        }
        return text
    }

    private suspend fun listenOnce(onPartial: (String) -> Unit, onRms: (Float) -> Unit): String? = suspendCancellableCoroutine { cont ->
        val watchdog = Runnable {
            if (cont.isActive) {
                Log.w(TAG, "recognizer did not start within ${START_TIMEOUT_MS} ms ($engine)")
                lastError = ERROR_NEVER_STARTED
                recognizer.cancel()
                cont.resume(null)
            }
        }
        handler.postDelayed(watchdog, START_TIMEOUT_MS)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                lastError = null
                if (cont.isActive) cont.resume(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            }

            override fun onPartialResults(partial: Bundle) {
                partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }

            override fun onError(error: Int) {
                handler.removeCallbacks(watchdog)
                // 6/7 (speech timeout, no match) are routine while waiting for the wake word
                if (error == 6 || error == 7) Log.d(TAG, "recognition error $error ($engine)")
                else Log.w(TAG, "recognition error $error ($engine)")
                lastError = error
                if (cont.isActive) cont.resume(null)
            }

            override fun onReadyForSpeech(params: Bundle?) = handler.removeCallbacks(watchdog)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = onRms(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                // end the utterance sooner after the user stops talking (a hint; Google may apply its own minimum)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, END_SILENCE_MS)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, END_SILENCE_MS)
        )
        cont.invokeOnCancellation {
            handler.removeCallbacks(watchdog)
            recognizer.cancel()
        }
    }

    fun destroy() = recognizer.destroy()

    private companion object {
        const val TAG = "SpeechInput"
        // ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE (API 31)
        val LANGUAGE_ERRORS = setOf(12, 13)
        // ours, not a SpeechRecognizer code: no onReadyForSpeech/onError within START_TIMEOUT_MS
        const val ERROR_NEVER_STARTED = -1
        const val START_TIMEOUT_MS = 3000L
        const val END_SILENCE_MS = 700L
        const val PREF_ON_DEVICE_UNUSABLE = "on_device_unusable"
    }
}

/** Korean text-to-speech through the platform TTS engine (Google TTS on GMS devices). */
class SpeechOutput(context: Context) {
    private lateinit var tts: TextToSpeech

    /** null until the engine has initialised; false if it has no Korean voice. */
    var ready: Boolean? = null
        private set

    // utterances awaited by speakAndWait, keyed by utterance id
    private val pending = ConcurrentHashMap<String, CancellableContinuation<Unit>>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
            Log.i(TAG, "TTS ready=$ready engine=${tts.defaultEngine}")
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) = finish(utteranceId)
            @Deprecated("required override") override fun onError(utteranceId: String) = finish(utteranceId)
            // QUEUE_FLUSH or stop() interrupts without onDone
            override fun onStop(utteranceId: String, interrupted: Boolean) = finish(utteranceId)
        })
    }

    private fun finish(utteranceId: String) {
        pending.remove(utteranceId)?.let { if (it.isActive) it.resume(Unit) }
    }

    fun speak(text: String) {
        if (ready != true) {
            Log.w(TAG, "TTS not ready, skipping")
            return
        }
        tts.speak(forSpeech(text), TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    /** Speaks and returns when playback has finished, so the microphone can be reopened without hearing it. */
    suspend fun speakAndWait(text: String) {
        if (ready != true) {
            Log.w(TAG, "TTS not ready, skipping")
            return
        }
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            pending[id] = cont
            cont.invokeOnCancellation {
                pending.remove(id)
                tts.stop()
            }
            tts.speak(forSpeech(text), TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    fun stop() = tts.stop()

    fun shutdown() = tts.shutdown()

    private companion object {
        const val TAG = "SpeechOutput"

        // "1. 제목 - 가수 (곡번호 123)" lines read badly aloud: drop list numbers and song numbers, pause between lines
        fun forSpeech(text: String) = text.lines()
            .map { it.replace(Regex("^\\d+\\.\\s*"), "").replace(Regex("\\s*\\(곡번호 \\d+\\)"), "") }
            .filter { it.isNotBlank() }
            .joinToString(". ")
    }
}
