package com.doorskorea.singkro.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Process-wide holder for the MediaPipe LLM engine.
 * Loading a 1.5–2B model takes seconds and 2–3 GB of RAM, so it is created once and reused.
 */
object LlmEngine {
    private const val TAG = "LlmEngine"

    // the engine runs one inference at a time
    private val mutex = Mutex()
    private var llm: LlmInference? = null

    val isReady: Boolean get() = llm != null

    suspend fun init(context: Context, modelPath: String) = mutex.withLock {
        if (llm != null) return@withLock
        require(File(modelPath).canRead()) { "Model file not readable: $modelPath" }
        llm = withContext(Dispatchers.IO) {
            try {
                create(context, modelPath, LlmInference.Backend.GPU)
            } catch (e: Exception) {
                // RK3588 Mali GPU support depends on the vendor driver; fall back to CPU
                Log.w(TAG, "GPU backend failed, falling back to CPU", e)
                create(context, modelPath, LlmInference.Backend.CPU)
            }
        }
    }

    private fun create(context: Context, path: String, backend: LlmInference.Backend): LlmInference =
        LlmInference.createFromOptions(
            context.applicationContext,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                // input + output tokens; must not exceed the KV cache size the model was converted with
                .setMaxTokens(1280)
                .setPreferredBackend(backend)
                .build()
        )

    /** One-shot generation in a fresh session, so nothing leaks between requests. */
    suspend fun generate(prompt: String, temperature: Float = 0.1f, topK: Int = 1): String = mutex.withLock {
        val engine = checkNotNull(llm) { "LlmEngine.init() has not been called" }
        withContext(Dispatchers.Default) {
            val session = LlmInferenceSession.createFromOptions(
                engine,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTemperature(temperature)
                    .setTopK(topK)
                    .build()
            )
            try {
                session.addQueryChunk(prompt)
                session.generateResponse()
            } finally {
                session.close()
            }
        }
    }

    suspend fun close() = mutex.withLock {
        llm?.close()
        llm = null
    }
}
