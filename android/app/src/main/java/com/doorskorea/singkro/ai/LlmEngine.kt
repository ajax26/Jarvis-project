package com.doorskorea.singkro.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Process-wide holder for the MediaPipe LLM engine.
 * Loading a 1.5–2B model takes seconds and 2–3 GB of RAM, so it is created once and reused.
 */
object LlmEngine {
    private const val TAG = "LlmEngine"

    // the engine runs one inference at a time
    private val mutex = Mutex()
    private var llm: LlmInference? = null

    private val NO_TEMPLATES = PromptTemplates.builder()
        .setUserPrefix("").setUserSuffix("")
        .setModelPrefix("").setModelSuffix("")
        .setSystemPrefix("").setSystemSuffix("")
        .build()

    // sessions with a fixed prompt prefix (system prompt) already added; cloned per request.
    // Several are kept so alternating intent/answer prompts don't evict each other.
    private data class PrefixKey(val prefix: String, val temperature: Float, val topK: Int)
    private const val MAX_PREFIXES = 3
    private val prefixSessions = LinkedHashMap<PrefixKey, LlmInferenceSession>()
    private var cloneUnsupported = false

    val isReady: Boolean get() = llm != null

    /** Backend the engine ended up on ("GPU" or "CPU"), for diagnostics. */
    var backend: String? = null
        private set

    suspend fun init(context: Context, modelPath: String, preferGpu: Boolean = true) = mutex.withLock {
        if (llm != null) return@withLock
        require(File(modelPath).canRead()) { "Model file not readable: $modelPath" }
        llm = withContext(Dispatchers.IO) {
            if (!preferGpu) return@withContext create(context, modelPath, LlmInference.Backend.CPU).also { backend = "CPU" }
            try {
                create(context, modelPath, LlmInference.Backend.GPU).also { backend = "GPU" }
            } catch (e: Exception) {
                // RK3588 Mali GPU support depends on the vendor driver; fall back to CPU
                Log.w(TAG, "GPU backend failed, falling back to CPU", e)
                create(context, modelPath, LlmInference.Backend.CPU).also { backend = "CPU" }
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

    private fun newSession(engine: LlmInference, temperature: Float, topK: Int) =
        LlmInferenceSession.createFromOptions(
            engine,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTemperature(temperature)
                .setTopK(topK)
                // prompts arrive fully templated (IntentPrompt); stop MediaPipe adding the .task's own
                .setPromptTemplates(NO_TEMPLATES)
                .build()
        )

    /**
     * One-shot generation; nothing leaks between requests.
     * `prefix` is the part shared by every request (system prompt): it is added to a cached session once
     * and that session is cloned per call, so only `rest` has to be processed each time.
     */
    suspend fun generate(prefix: String, rest: String, temperature: Float = 0.1f, topK: Int = 1): String =
        mutex.withLock {
            val engine = checkNotNull(llm) { "LlmEngine.init() has not been called" }
            withContext(Dispatchers.Default) {
                val session = cachedClone(engine, PrefixKey(prefix, temperature, topK))
                    ?: newSession(engine, temperature, topK).apply { addQueryChunk(prefix) }
                try {
                    session.addQueryChunk(rest)
                    ByteLevel.fix(session.generateResponse())
                } finally {
                    session.close()
                }
            }
        }

    private fun cachedClone(engine: LlmInference, key: PrefixKey): LlmInferenceSession? {
        if (cloneUnsupported) return null
        return try {
            val base = prefixSessions.getOrPut(key) {
                if (prefixSessions.size >= MAX_PREFIXES) {
                    val oldest = prefixSessions.keys.first()
                    prefixSessions.remove(oldest)?.close()
                }
                newSession(engine, key.temperature, key.topK).apply { addQueryChunk(key.prefix) }
            }
            base.cloneSession()
        } catch (e: Exception) {
            // cloning is not available on every backend; fall back to a fresh session per call
            Log.w(TAG, "cloneSession unsupported, prefix caching off", e)
            cloneUnsupported = true
            clearPrefixes()
            null
        }
    }

    private fun clearPrefixes() {
        prefixSessions.values.forEach { it.close() }
        prefixSessions.clear()
    }

    suspend fun close() = mutex.withLock {
        clearPrefixes()
        llm?.close()
        llm = null
    }
}

/**
 * Qwen uses GPT-2 style byte-level BPE, and MediaPipe returns some of its tokens undecoded,
 * so Korean comes back as "좋은ĠëĤł" instead of "좋은 날". Each run of byte-alphabet characters is
 * mapped back to bytes and kept only if it is valid UTF-8, so normal text (e.g. "café") is untouched.
 */
internal object ByteLevel {
    private val charToByte: Map<Char, Int> = buildMap {
        val printable = (33..126) + (161..172) + (174..255)
        printable.forEach { put(it.toChar(), it) }
        var n = 0
        for (b in 0..255) if (b !in printable) put((256 + n++).toChar(), b)
    }

    fun fix(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] !in charToByte) {
                out.append(text[i++])
                continue
            }
            var j = i
            while (j < text.length && text[j] in charToByte) j++
            val run = text.substring(i, j)
            out.append(decodeOrNull(run) ?: run)
            i = j
        }
        return out.toString()
    }

    private fun decodeOrNull(run: String): String? {
        val bytes = ByteArray(run.length) { charToByte.getValue(run[it]).toByte() }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }
}
