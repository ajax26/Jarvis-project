package com.doorskorea.singkro.assistant

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

    private fun emit(json: JSONObject) {
        _events.tryEmit(json.toString())
    }
}
