package com.doorskorea.singkro.ai

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.text.Html
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

/** Utterance in, text for TTS out (plus the parsed intent, for logging and the test screen). */
class IntentHandler(
    private val context: Context,
    private val family: ModelFamily,
    private val weather: WeatherSource,
    private val news: NewsSource = NewsSource(),
    private val songs: SongSource = SongSource(),
    private val web: WebSearchSource = WebSearchSource(),
) {
    private val device = DeviceController(context)

    data class Reply(val intent: AssistantIntent, val text: String)

    suspend fun handle(utterance: String): Reply {
        // song and volume requests skip the LLM (~2.7 s on the RK3588)
        val intent = IntentRules.match(utterance)?.also { Log.d(TAG, "rule intent=$it") }
            ?: IntentPrompt.intent(utterance, family).let { LlmEngine.generate(it.prefix, it.rest) }
                .let(IntentParser::parse).also { Log.d(TAG, "intent=$it") }
                .let { songOrWeb(it, utterance) }
        val text = try {
            when (intent) {
                is AssistantIntent.GetWeather ->
                    weather.fetch(intent.location, intent.date)
                        ?: "${intent.location} 날씨는 아직 지원하지 않는 지역이에요."
                is AssistantIntent.GetNews -> news.fetch(intent.category)
                is AssistantIntent.DeviceControl -> device.apply(intent)
                is AssistantIntent.SearchSong -> {
                    val found = searchSongs(intent.query)
                    val top = found.firstOrNull()
                    // only the best match is spoken: reading five results took ~14 s.
                    // not summarised by the LLM, which invents titles
                    if (top == null) "'${intent.query}'에 맞는 곡을 찾지 못했어요."
                    else "${top.artist}의 '${top.title}', 곡번호 ${top.songNum}번" +
                        (if (found.size > 1) " 외에 ${found.size - 1}곡이 더 있어요." else "이에요.")
                }
                is AssistantIntent.PlaySong -> {
                    val song = searchSongs(intent.query, limit = 1).firstOrNull()
                    if (song == null) "'${intent.query}'에 맞는 곡을 찾지 못했어요."
                    else {
                        songs.play(context, song)
                        "${song.artist} - '${song.title}' 재생할게요."
                    }
                }
                is AssistantIntent.WebSearch ->
                    web.search(intent.query) ?: "'${intent.query}'에 대한 정보를 찾지 못했어요."
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
        return Reply(intent, text)
    }

    /**
     * With no web search intent the model filed questions like "홈니아 핸드 청소기 알려 줘" as song searches; a song
     * search from the model with nothing song-like in the utterance goes to the web instead.
     */
    private fun songOrWeb(intent: AssistantIntent, utterance: String): AssistantIntent =
        if (intent is AssistantIntent.SearchSong && !SONG_CUE.containsMatchIn(utterance)) {
            AssistantIntent.WebSearch(intent.query)
        } else intent

    /** The catalog needs every word to match, so "아이유의 좋은 날" finds nothing; retry as "아이유 좋은 날". */
    private suspend fun searchSongs(query: String, limit: Int = 5): List<SongSource.Song> {
        val found = songs.search(query, limit)
        val plain = query.replaceFirst(Regex("(\\S)의\\s"), "$1 ")
        return if (found.isEmpty() && plain != query) songs.search(plain, limit) else found
    }

    private companion object {
        const val TAG = "IntentHandler"
        // 노래/곡/가수/검색, or 초성 like "ㅈㅇㄴ"
        val SONG_CUE = Regex("노래|곡|가수|검색|[ㄱ-ㅎ]{2,}")
    }
}

/** 기상청 단기예보 via 기상청 API허브 (apihub.kma.go.kr, VilageFcstInfoService_2.0). */
class WeatherSource(private val authKey: String, private val grid: KmaGrid) {
    private val kst = ZoneId.of("Asia/Seoul")
    private val ymd = DateTimeFormatter.BASIC_ISO_DATE
    private val skyText = mapOf("1" to "맑고", "3" to "구름이 많고", "4" to "흐리고")
    private val ptyText = mapOf("1" to "비", "2" to "비 또는 눈", "3" to "눈", "4" to "소나기")

    /** Returns a one-line summary, or null if the location is not supported. */
    suspend fun fetch(location: String, date: String): String? = withContext(Dispatchers.IO) {
        val xy = grid.resolve(location) ?: return@withContext null
        val city = location // spoken back as the user said it

        // forecasts are issued at 02,05,...,23h and published ~10 minutes later
        val now = LocalDateTime.now(kst).minusMinutes(15)
        val baseHour = listOf(23, 20, 17, 14, 11, 8, 5, 2).firstOrNull { it <= now.hour }
        val baseDate = if (baseHour == null) now.toLocalDate().minusDays(1) else now.toLocalDate()
        val target = LocalDate.now(kst).plusDays(if (date == "tomorrow") 1 else 0).format(ymd)

        if (authKey.isBlank()) throw IOException("KMA auth key not set (local.properties kma.authKey)")
        val url = "https://apihub.kma.go.kr/api/typ02/openApi/VilageFcstInfoService_2.0/getVilageFcst" +
            "?authKey=${URLEncoder.encode(authKey, "UTF-8")}&pageNo=1&numOfRows=1000&dataType=JSON" +
            "&base_date=${baseDate.format(ymd)}&base_time=%02d00".format(baseHour ?: 23) +
            "&nx=${xy.first}&ny=${xy.second}"

        // API허브 occasionally times out or answers 200 with an empty body; one retry clears it
        val body = JSONObject(runCatching { httpGet(url) }.getOrNull()?.takeIf { it.isNotBlank() } ?: httpGet(url))
        // API허브 gateway errors come as {"result": {"status": 401, "message": "..."}}
        body.optJSONObject("result")?.let { throw IOException("KMA API허브 ${it.optInt("status")}: ${it.optString("message")}") }
        val response = body.getJSONObject("response")
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

        // spoken as-is (no LLM rewrite): instant and nothing can be made up
        val day = if (date == "tomorrow") "내일" else "오늘"
        val rain = ptyText[pty]?.let { "$it 소식이 있어요" } ?: "비 소식은 없어요"
        "$day $city 날씨는 ${skyText[sky]?.let { "$it " } ?: ""}$rain. " +
            "기온은 최저 ${temps.min()}도, 최고 ${temps.max()}도이고 강수확률은 최대 $pop%예요."
    }
}

/** Google News RSS (Korean edition). Check the feed's terms before commercial use; swap in licensed press RSS if needed. */
class NewsSource {
    private val names = mapOf(
        "IT" to "IT", "ECONOMY" to "경제", "SPORTS" to "스포츠",
        "SCIENCE" to "과학", "ENTERTAINMENT" to "연예", "GENERAL" to "주요",
    )
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
        // headlines are read as-is: an LLM summary of them distorted facts in testing
        "최신 ${names[category] ?: "주요"} 뉴스예요.\n" + titles.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
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

/**
 * Web search for information questions, read as-is (an LLM summary of search snippets invents facts).
 * Naver Search API when keys are set (Korean web documents, products included); otherwise Korean Wikipedia,
 * which needs no key but knows little about products.
 */
class WebSearchSource(private val naverId: String = "", private val naverSecret: String = "") {
    suspend fun search(query: String): String? = withContext(Dispatchers.IO) {
        if (naverId.isNotBlank() && naverSecret.isNotBlank()) naver(query) else wikipedia(query)
    }

    private fun naver(query: String): String? {
        val q = URLEncoder.encode(query, "UTF-8")
        val body = httpGet(
            "https://openapi.naver.com/v1/search/webkr.json?query=$q&display=3",
            mapOf("X-Naver-Client-Id" to naverId, "X-Naver-Client-Secret" to naverSecret),
        )
        val items = JSONObject(body).getJSONArray("items")
        val top = (0 until items.length()).map { items.getJSONObject(it) }
            .firstOrNull { plain(it.optString("description")).length >= 20 } ?: return null
        return "검색 결과예요. ${plain(top.getString("title"))}. ${short(plain(top.getString("description")))}"
    }

    private fun wikipedia(query: String): String? {
        val q = URLEncoder.encode(query, "UTF-8")
        val ua = mapOf("User-Agent" to "SINGKRO/0.1 (Doors Korea voice assistant)")
        val body = JSONObject(
            httpGet("https://ko.wikipedia.org/w/api.php?action=query&list=search&srsearch=$q&srlimit=3&format=json", ua),
        )
        val hits = body.optJSONObject("query")?.optJSONArray("search")
            ?: throw IOException("Wikipedia search: ${body.optJSONObject("error")?.optString("info") ?: body.toString().take(200)}")
        // full-text search always returns something ("시장 1위 핸드 청소기" -> 삼성전자): only take a page whose
        // title matches one of the query's words ("세종대왕" -> "세종 (조선)" counts), else report nothing found
        // rather than read an unrelated article
        val words = query.split(" ").map { it.trim() }.filter { it.length >= 2 }
        val title = (0 until hits.length()).map { hits.getJSONObject(it).getString("title") }.firstOrNull { t ->
            val base = t.substringBefore(" (")
            base.length >= 2 && words.any { it.startsWith(base) || base.startsWith(it) }
        } ?: return null
        val page = URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
        val extract = JSONObject(httpGet("https://ko.wikipedia.org/api/rest_v1/page/summary/$page", ua))
            .optString("extract").takeIf { it.isNotBlank() } ?: return null
        // "(한국 한자: 世宗, 중세 한국어: ·솅조ᇰ, 1397년 ...)" is unreadable aloud
        var spoken = extract
        while (true) spoken = PARENS.replace(spoken, "").takeIf { it != spoken } ?: break
        // the summary API sometimes drops the closing one: "이순신(李舜臣, ... 16일은" -> "이순신은"
        spoken = UNCLOSED_PAREN.replaceFirst(spoken, "")
        return "위키백과에 따르면, ${short(spoken.replace(Regex("\\s+([은는이가을를의,.])"), "$1"))}"
    }

    private companion object {
        val PARENS = Regex("\\s*\\([^()]*\\)")
        val UNCLOSED_PAREN = Regex("\\s*\\([^()]*?(?=[은는이가]\\s)")
    }

    private fun plain(html: String) = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()

    /** First two sentences, at most ~160 characters: it's read aloud. */
    private fun short(text: String): String {
        val sentences = Regex("[^.!?。]+[.!?。]?").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }
        val two = sentences.take(2).joinToString(" ")
        return if (two.length <= 160) two else two.take(157).trimEnd() + "..."
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

private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
    conn.connectTimeout = 5_000
    // API허브 forecasts (~100 KB) can take several seconds
    conn.readTimeout = 10_000
    try {
        // host only: the full URL carries the API key
        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} from ${conn.url.host}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}
