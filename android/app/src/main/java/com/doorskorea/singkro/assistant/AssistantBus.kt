package com.doorskorea.singkro.assistant

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * In-process link between AssistantService (does the work) and HudActivity (shows it).
 * Events are JSON in the same shape the backend WebSocket sends, so the web HUD handles both.
 */
object AssistantBus {
    sealed interface Request {
        /** wake word heard: acknowledge, then listen */
        data object Wake : Request
        /** mic button in the HUD: listen right away */
        data object Listen : Request
        data class Ask(val text: String) : Request
    }

    // conflated: if several taps arrive while busy, only the latest is handled
    val requests = Channel<Request>(Channel.CONFLATED)

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    private val _state = MutableStateFlow("idle")
    /** idle | listening | thinking | speaking (drives the sphere animation) */
    val state = _state.asStateFlow()

    private var prefs: SharedPreferences? = null
    private val _wakeSound = MutableStateFlow(false)
    /** HUD toggle: hear the recognizer's start/stop chimes while waiting for the wake word (off = muted) */
    val wakeSound = _wakeSound.asStateFlow()

    /** Loads saved settings; call from the service and the HUD before using them. */
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("assistant", Context.MODE_PRIVATE).also {
            _wakeSound.value = it.getBoolean(PREF_WAKE_SOUND, false)
        }
    }

    fun setWakeSound(on: Boolean) {
        _wakeSound.value = on
        prefs?.edit()?.putBoolean(PREF_WAKE_SOUND, on)?.apply()
        settings()
    }

    fun settings() = emit(JSONObject().put("type", "settings").put("wakeSound", _wakeSound.value))

    fun setState(s: String) {
        _state.value = s
        emit(JSONObject().put("type", "state").put("state", s))
    }

    fun user(text: String) = emit(JSONObject().put("type", "user").put("text", text))
    fun system(text: String) = emit(JSONObject().put("type", "system").put("text", text))
    fun error(text: String) = emit(JSONObject().put("type", "error").put("text", text))
    fun replyStart() = emit(JSONObject().put("type", "start"))
    fun replyText(text: String) = emit(JSONObject().put("type", "delta").put("text", text))
    fun replyDone() = emit(JSONObject().put("type", "done"))

    /**
     * Microphone status for the HUD. mode: "wake" (waiting for the wake word) | "ask" (listening to a question)
     * | "off"; level 0..1; clipped when the input hits full scale (mic gain too high).
     */
    fun mic(mode: String, level: Float = 0f, clipped: Boolean = false) = emit(
        JSONObject().put("type", "mic").put("mode", mode).put("level", level.toDouble()).put("clipped", clipped),
    )

    /** What the wake word recognizer transcribed, so a missed wake word can be seen on screen. */
    fun heard(text: String) = emit(JSONObject().put("type", "heard").put("text", text))

    private fun emit(json: JSONObject) {
        _events.tryEmit(json.toString())
    }

    private const val PREF_WAKE_SOUND = "wake_sound"
}
