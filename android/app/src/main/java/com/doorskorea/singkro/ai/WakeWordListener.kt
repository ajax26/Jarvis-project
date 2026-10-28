package com.doorskorea.singkro.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Offline wake word with Vosk (vosk-model-small-ko). The Korean model has no [unk] word, so a grammar of
 * just the wake word would match every sound; instead it transcribes freely and wakes when one of
 * `tokens` appears as a whole word (whole-word, so "싱크대"/"싱크로" don't trigger "싱크").
 * Measured with pc-test/wakeword_test.py.
 */
class WakeWordListener(modelDir: String, private val tokens: Set<String>) : AutoCloseable {
    // ~250 MB unpacked, loads in a few seconds: construct off the main thread
    private val model = Model(modelDir)

    /** Holds the microphone and suspends until the wake word is heard. Call on the main thread. */
    suspend fun await(): Unit = suspendCancellableCoroutine { cont ->
        val recognizer = Recognizer(model, SAMPLE_RATE)
        val service = SpeechService(recognizer, SAMPLE_RATE)
        val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) {
                service.shutdown() // joins the audio thread, so the recognizer is idle before close
                recognizer.close()
            }
        }

        service.startListening(object : RecognitionListener {
            override fun onPartialResult(hypothesis: String) = check(JSONObject(hypothesis).optString("partial"), partial = true)
            override fun onResult(hypothesis: String) = check(JSONObject(hypothesis).optString("text"), partial = false)
            override fun onFinalResult(hypothesis: String) = check(JSONObject(hypothesis).optString("text"), partial = false)
            override fun onTimeout() {}

            override fun onError(e: Exception) {
                release()
                if (cont.isActive) cont.resumeWithException(e)
            }

            private fun check(text: String, partial: Boolean) {
                if (cont.isActive && heard(text, partial, tokens)) {
                    release()
                    cont.resume(Unit)
                }
            }
        })
        cont.invokeOnCancellation { release() }
    }

    override fun close() = model.close()

    companion object {
        private const val SAMPLE_RATE = 16000f

        /** In a partial result the last word may still grow ("싱크" -> "싱크대"), so it doesn't count yet. */
        internal fun heard(text: String, partial: Boolean, tokens: Set<String>): Boolean {
            val words = text.split(" ").filter { it.isNotBlank() }
            return (if (partial) words.dropLast(1) else words).any { it in tokens }
        }
    }
}
