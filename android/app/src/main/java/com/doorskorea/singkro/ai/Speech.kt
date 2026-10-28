package com.doorskorea.singkro.ai

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Korean speech-to-text through the platform recognizer (Google on GMS devices).
 * Prefers the on-device recognizer; falls back to the default one if Korean isn't installed offline.
 * Create and call on the main thread.
 */
class SpeechInput(private val context: Context) {
    private var onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    private var recognizer = create()

    /** "on-device" or "default", for diagnostics. */
    val engine get() = if (onDevice) "on-device" else "default"
    val isAvailable get() = onDevice || SpeechRecognizer.isRecognitionAvailable(context)

    /** Last SpeechRecognizer.ERROR_* code, or null after a successful listen. */
    var lastError: Int? = null
        private set

    private fun create(): SpeechRecognizer =
        if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)

    /** Listens for one utterance; returns null if nothing was recognised (see [lastError]). */
    suspend fun listen(onPartial: (String) -> Unit = {}): String? {
        val text = listenOnce(onPartial)
        // on-device recognizer without the Korean pack: switch to the default recognizer for good and retry
        if (text == null && onDevice && lastError in LANGUAGE_ERRORS) {
            Log.w(TAG, "on-device recognizer lacks Korean (error $lastError), using default recognizer")
            recognizer.destroy()
            onDevice = false
            recognizer = create()
            return listenOnce(onPartial)
        }
        return text
    }

    private suspend fun listenOnce(onPartial: (String) -> Unit): String? = suspendCancellableCoroutine { cont ->
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                lastError = null
                if (cont.isActive) cont.resume(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            }

            override fun onPartialResults(partial: Bundle) {
                partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }

            override fun onError(error: Int) {
                Log.w(TAG, "recognition error $error ($engine)")
                lastError = error
                if (cont.isActive) cont.resume(null)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
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
        )
        cont.invokeOnCancellation { recognizer.cancel() }
    }

    fun destroy() = recognizer.destroy()

    private companion object {
        const val TAG = "SpeechInput"
        // ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE (API 31)
        val LANGUAGE_ERRORS = setOf(12, 13)
    }
}

/** Korean text-to-speech through the platform TTS engine (Google TTS on GMS devices). */
class SpeechOutput(context: Context) {
    private lateinit var tts: TextToSpeech

    /** null until the engine has initialised; false if it has no Korean voice. */
    var ready: Boolean? = null
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
            Log.i(TAG, "TTS ready=$ready engine=${tts.defaultEngine}")
        }
    }

    fun speak(text: String) {
        if (ready != true) {
            Log.w(TAG, "TTS not ready, skipping")
            return
        }
        tts.speak(forSpeech(text), TextToSpeech.QUEUE_FLUSH, null, "reply")
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
