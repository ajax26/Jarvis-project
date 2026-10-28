package com.doorskorea.singkro.ai

import org.json.JSONException
import org.json.JSONObject

sealed interface AssistantIntent {
    data class GetWeather(val location: String, val date: String) : AssistantIntent
    data class GetNews(val category: String) : AssistantIntent
    data class DeviceControl(val target: String, val value: String) : AssistantIntent
    data class SearchSong(val query: String) : AssistantIntent
    data class PlaySong(val query: String) : AssistantIntent
    data class Chitchat(val reply: String) : AssistantIntent
    data class Unknown(val raw: String) : AssistantIntent
}

object IntentParser {
    private val NEWS_CATEGORIES = setOf("IT", "ECONOMY", "SPORTS", "SCIENCE", "ENTERTAINMENT", "GENERAL")

    /** Small models often wrap the JSON in ```json fences or add chatter, so parse leniently and validate. */
    fun parse(raw: String): AssistantIntent {
        val obj = extractJson(raw) ?: return AssistantIntent.Unknown(raw)
        return when (obj.str("action").uppercase()) {
            "GET_WEATHER" -> AssistantIntent.GetWeather(
                location = obj.str("location").ifBlank { "서울" },
                date = if (obj.str("date").lowercase() in setOf("tomorrow", "내일")) "tomorrow" else "today",
            )
            "GET_NEWS" -> AssistantIntent.GetNews(
                obj.str("category").uppercase().takeIf { it in NEWS_CATEGORIES } ?: "GENERAL"
            )
            "DEVICE_CONTROL" -> AssistantIntent.DeviceControl(
                target = obj.str("target").uppercase(),
                value = obj.str("value").uppercase(),
            )
            "SEARCH_SONG" -> obj.str("query").takeIf { it.isNotBlank() }
                ?.let { AssistantIntent.SearchSong(it) }
                ?: AssistantIntent.Unknown(raw)
            "PLAY_SONG" -> obj.str("query").takeIf { it.isNotBlank() }
                ?.let { AssistantIntent.PlaySong(it) }
                ?: AssistantIntent.Unknown(raw)
            "CHITCHAT" -> obj.str("reply").takeIf { it.isNotBlank() }
                ?.let { AssistantIntent.Chitchat(it) }
                ?: AssistantIntent.Unknown(raw)
            else -> AssistantIntent.Unknown(raw)
        }
    }

    private fun extractJson(raw: String): JSONObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(raw.substring(start, end + 1))
        } catch (e: JSONException) {
            null
        }
    }

    // optString() turns JSON null into the string "null"
    private fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key).trim()
}
