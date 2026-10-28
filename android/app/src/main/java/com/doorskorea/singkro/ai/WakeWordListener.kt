package com.doorskorea.singkro.ai

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Offline wake word with Vosk (vosk-model-small-ko). The Korean model has no [unk] word, so a grammar of
 * just the wake word would match every sound; instead it transcribes freely and wakes when one of
 * `tokens` appears as a whole word (whole-word, so "싱크대"/"싱크로" don't trigger "싱크").
 * Measured with pc-test/wakeword_test.py.
 *
 * Reads the microphone itself instead of using Vosk's SpeechService, which hard-codes the VOICE_RECOGNITION
 * source: on the RK3588 board that source doesn't reach the USB wireless mic, so the source is configurable.
 */
class WakeWordListener(
    modelDir: String,
    private val tokens: Set<String>,
    private val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    private val preferredDevice: AudioDeviceInfo? = null,
    /** input peak (0..32767) about 5 times a second, called on the audio thread */
    private val onLevel: (Int) -> Unit = {},
    /** each final transcription, called on the audio thread */
    private val onHeard: (String) -> Unit = {},
) : AutoCloseable {
    // ~250 MB unpacked, loads in a few seconds: construct off the main thread
    private val model = Model(modelDir)

    /**
     * Holds the microphone and suspends until the wake word is heard. On cancellation the read loop stops
     * and the microphone is released before this returns (the recognizer is only touched by this thread).
     */
    @SuppressLint("MissingPermission") // the service only starts after RECORD_AUDIO is granted
    suspend fun await(): Unit = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            audioSource, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, CHUNK * 2 * 4),
        )
        val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed (source $audioSource)" }
            preferredDevice?.let { record.setPreferredDevice(it) }
            record.startRecording()
            Log.i(TAG, "listening source=$audioSource device=${record.routedDevice?.productName}/${record.routedDevice?.type}")

            val buffer = ShortArray(CHUNK)
            var peak = 0
            var logPeak = 0
            var chunks = 0
            var lastPartial = ""
            var stableChunks = 0
            while (true) {
                ensureActive() // read() returns within one chunk (100 ms), so cancellation is quick
                val n = record.read(buffer, 0, buffer.size)
                check(n >= 0) { "AudioRecord read error $n" }
                for (i in 0 until n) peak = maxOf(peak, kotlin.math.abs(buffer[i].toInt()))
                logPeak = maxOf(logPeak, peak)
                if (++chunks % 2 == 0) {
                    onLevel(peak)
                    peak = 0
                }
                // input level every ~3 s: near 0 means this source isn't getting the mic
                if (chunks % 30 == 0) {
                    Log.d(TAG, "level peak=$logPeak")
                    logPeak = 0
                }

                val final = recognizer.acceptWaveForm(buffer, n)
                val text = if (final) JSONObject(recognizer.result).optString("text")
                else JSONObject(recognizer.partialResult).optString("partial")
                // what Vosk actually heard, for tuning WAKE_TOKENS on a new device
                if (final && text.isNotBlank()) {
                    Log.d(TAG, "heard \"$text\"")
                    onHeard(text)
                }
                // a partial that hasn't changed for STABLE_CHUNKS means the user paused after its last word, so
                // that word is complete too: wake now instead of waiting for Vosk's end-of-utterance (~0.5-1 s)
                stableChunks = if (!final && text == lastPartial) stableChunks + 1 else 0
                lastPartial = if (final) "" else text
                val settled = final || stableChunks >= STABLE_CHUNKS
                if (heard(text, partial = !settled, tokens)) {
                    if (!final) {
                        Log.d(TAG, "heard \"$text\" (stable partial)")
                        onHeard(text)
                    }
                    return@withContext
                }
            }
        } finally {
            if (record.state == AudioRecord.STATE_INITIALIZED) record.stop()
            record.release()
            recognizer.close()
        }
    }

    override fun close() = model.close()

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHUNK = SAMPLE_RATE / 10 // 100 ms of samples
        private const val STABLE_CHUNKS = 3 // 300 ms
        private const val TAG = "WakeWord"

        /** In a partial result the last word may still grow ("싱크" -> "싱크대"), so it doesn't count yet. */
        internal fun heard(text: String, partial: Boolean, tokens: Set<String>): Boolean {
            val words = text.split(" ").filter { it.isNotBlank() }
            return (if (partial) words.dropLast(1) else words).any { it in tokens }
        }
    }
}
