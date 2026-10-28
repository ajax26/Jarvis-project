package com.doorskorea.singkro.ai

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Utterance in, text for TTS out. */
class IntentHandler(
    private val context: Context,
    private val family: ModelFamily,
    private val weather: WeatherSource,
    private val news: NewsSource = NewsSource(),
    private val songs: SongSource = SongSource(),
) {
    private val device = DeviceController(context)

    suspend fun handle(utterance: String): String {
        val raw = LlmEngine.generate(IntentPrompt.intent(utterance, family))
        val intent = IntentParser.parse(raw)
        Log.d(TAG, "intent=$intent")
        return try {
            when (intent) {
                is AssistantIntent.GetWeather ->
                    weather.fetch(intent.location, intent.date)?.let { speakable(utterance, it) }
                        ?: "${intent.location} 날씨는 아직 지원하지 않는 지역이에요."
                is AssistantIntent.GetNews -> speakable(utterance, news.fetch(intent.category))
                is AssistantIntent.DeviceControl -> device.apply(intent)
                is AssistantIntent.SearchSong -> {
                    val found = songs.search(intent.query)
                    if (found.isEmpty()) "'${intent.query}'에 맞는 곡을 찾지 못했어요."
                    // read the list as-is: summarising it with the LLM invites made-up titles
                    else found.mapIndexed { i, s -> "${i + 1}. ${s.title} - ${s.artist} (곡번호 ${s.songNum})" }
                        .joinToString("\n")
                }
                is AssistantIntent.PlaySong -> {
                    val song = songs.search(intent.query, limit = 1).firstOrNull()
                    if (song == null) "'${intent.query}'에 맞는 곡을 찾지 못했어요."
                    else {
                        songs.play(context, song)
                        "${song.artist} - '${song.title}' 재생할게요."
                    }
                }
                is AssistantIntent.Chitchat -> intent.reply
                is AssistantIntent.Unknown -> "죄송해요, 잘 이해하지 못했어요. 다시 말씀해 주세요."
            }
        } catch (e: IOException) {
            Log.w(TAG, "fetch failed", e)
            "지금은 정보를 가져올 수 없어요. 네트워크를 확인해 주세요."
        } catch (e: JSONException) {
            Log.w(TAG, "bad API response", e)
            "지금은 정보를 가져올 수 없어요."
        }
    }

    // fetched text goes back into the LLM to become a short spoken answer;
    // to save ~seconds per request, return `info` directly to TTS instead
    private suspend fun speakable(utterance: String, info: String) =
        LlmEngine.generate(IntentPrompt.answer(utterance, info, family), temperature = 0.3f, topK = 20)
            .trim()
            .ifBlank { info }

    private companion object {
        const val TAG = "IntentHandler"
    }
}

/** 기상청 단기예보 (data.go.kr VilageFcstInfoService_2.0). `serviceKey` is the URL-encoded key from data.go.kr. */
class WeatherSource(private val serviceKey: String) {
    // shortcut: fixed KMA grid for major cities; add lat/lon -> grid (LCC) conversion to support any location
    private val grid = mapOf(
        "서울" to (60 to 127), "부산" to (98 to 76), "대구" to (89 to 90), "인천" to (55 to 124),
        "광주" to (58 to 74), "대전" to (67 to 100), "울산" to (102 to 84), "세종" to (66 to 103),
        "수원" to (60 to 121), "제주" to (52 to 38),
    )
    private val kst = ZoneId.of("Asia/Seoul")
    private val ymd = DateTimeFormatter.BASIC_ISO_DATE
    private val skyText = mapOf("1" to "맑음", "3" to "구름 많음", "4" to "흐림")
    private val ptyText = mapOf("1" to "비", "2" to "비 또는 눈", "3" to "눈", "4" to "소나기")

    /** Returns a one-line summary, or null if the location is not supported. */
    suspend fun fetch(location: String, date: String): String? = withContext(Dispatchers.IO) {
        val (city, xy) = grid.entries.firstOrNull { location.startsWith(it.key) }?.toPair()
            ?: return@withContext null

        // forecasts are issued at 02,05,...,23h and published ~10 minutes later
        val now = LocalDateTime.now(kst).minusMinutes(15)
        val baseHour = listOf(23, 20, 17, 14, 11, 8, 5, 2).firstOrNull { it <= now.hour }
        val baseDate = if (baseHour == null) now.toLocalDate().minusDays(1) else now.toLocalDate()
        val target = LocalDate.now(kst).plusDays(if (date == "tomorrow") 1 else 0).format(ymd)

        val url = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst" +
            "?serviceKey=$serviceKey&pageNo=1&numOfRows=1000&dataType=JSON" +
            "&base_date=${baseDate.format(ymd)}&base_time=%02d00".format(baseHour ?: 23) +
            "&nx=${xy.first}&ny=${xy.second}"

        val response = JSONObject(httpGet(url)).getJSONObject("response")
        val header = response.getJSONObject("header")
        if (header.getString("resultCode") != "00") throw IOException("KMA error ${header.optString("resultMsg")}")
        val items = response.getJSONObject("body").getJSONObject("items").getJSONArray("item")

        val temps = mutableListOf<Int>()
        var pop = 0
        var sky: String? = null
        var pty: String? = null
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.getString("fcstDate") != target) continue
            val v = item.getString("fcstValue")
            when (item.getString("category")) {
                "TMP" -> v.toIntOrNull()?.let(temps::add)
                "POP" -> pop = maxOf(pop, v.toIntOrNull() ?: 0)
                "SKY" -> if (sky == null || item.getString("fcstTime") == "1200") sky = v
                "PTY" -> if (v != "0") pty = v
            }
        }
        if (temps.isEmpty()) throw IOException("no forecast for $target")

        val day = if (date == "tomorrow") "내일" else "오늘"
        "$city $day 날씨: 하늘 ${skyText[sky] ?: "정보 없음"}, 강수 ${ptyText[pty] ?: "없음"}, " +
            "기온 최저 ${temps.min()}도 최고 ${temps.max()}도, 강수확률 최대 $pop%"
    }
}

/** Google News RSS (Korean edition). Check the feed's terms before commercial use; swap in licensed press RSS if needed. */
class NewsSource {
    private val topics = mapOf(
        "IT" to "TECHNOLOGY", "ECONOMY" to "BUSINESS", "SPORTS" to "SPORTS",
        "SCIENCE" to "SCIENCE", "ENTERTAINMENT" to "ENTERTAINMENT",
    )

    suspend fun fetch(category: String, limit: Int = 5): String = withContext(Dispatchers.IO) {
        val locale = "hl=ko&gl=KR&ceid=KR:ko"
        val url = topics[category]
            ?.let { "https://news.google.com/rss/headlines/section/topic/$it?$locale" }
            ?: "https://news.google.com/rss?$locale"
        val titles = parseTitles(httpGet(url), limit)
        if (titles.isEmpty()) throw IOException("empty news feed")
        titles.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
    }

    private fun parseTitles(xml: String, limit: Int): List<String> {
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        val titles = mutableListOf<String>()
        var inItem = false
        while (titles.size < limit && parser.next() != XmlPullParser.END_DOCUMENT) {
            when {
                parser.eventType == XmlPullParser.START_TAG && parser.name == "item" -> inItem = true
                parser.eventType == XmlPullParser.END_TAG && parser.name == "item" -> inItem = false
                parser.eventType == XmlPullParser.START_TAG && parser.name == "title" && inItem ->
                    // "제목 - 언론사" -> "제목"
                    titles += parser.nextText().substringBeforeLast(" - ").trim()
            }
        }
        return titles
    }
}

/** Singkro web song catalog: matches title, artist, lyricist, composer and 초성 (all words must match). */
class SongSource(private val baseUrl: String = "https://singkro1.mycafe24.com") {
    data class Song(val cntsSeq: Long, val songNum: Int, val title: String, val artist: String)

    suspend fun search(query: String, limit: Int = 5): List<Song> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val items = JSONObject(httpGet("$baseUrl/api/catalog/search?q=$q&limit=$limit")).getJSONArray("items")
        (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            Song(o.getLong("cnts_seq"), o.optInt("song_num"), o.getString("title"), o.getString("artist"))
        }
    }

    /**
     * Opens the Singkro web player for the song. Playback needs a logged-in session in that browser.
     * Android blocks activity starts from the background: run this from a foreground service with
     * SYSTEM_ALERT_WINDOW granted, or as a system/privileged app on the device image.
     */
    fun play(context: Context, song: Song) {
        val uri = Uri.parse("$baseUrl/player?cnts_seq=${song.cntsSeq}")
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class DeviceController(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    fun apply(cmd: AssistantIntent.DeviceControl): String {
        if (cmd.target != "VOLUME") return "아직 지원하지 않는 기기 제어예요."
        val stream = AudioManager.STREAM_MUSIC
        val flags = AudioManager.FLAG_SHOW_UI
        when (cmd.value) {
            "UP" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flags)
            "DOWN" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flags)
            "MUTE" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags)
            else -> {
                val pct = cmd.value.toIntOrNull()?.coerceIn(0, 100) ?: return "볼륨 값을 이해하지 못했어요."
                audio.setStreamVolume(stream, audio.getStreamMaxVolume(stream) * pct / 100, flags)
            }
        }
        return "볼륨을 조절했어요."
    }
}

private fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 5_000
    conn.readTimeout = 5_000
    try {
        // host only: the full URL carries the API key
        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} from ${conn.url.host}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}
