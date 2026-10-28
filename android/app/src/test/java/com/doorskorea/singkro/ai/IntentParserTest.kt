package com.doorskorea.singkro.ai

import com.doorskorea.singkro.ai.AssistantIntent.Chitchat
import com.doorskorea.singkro.ai.AssistantIntent.DeviceControl
import com.doorskorea.singkro.ai.AssistantIntent.GetNews
import com.doorskorea.singkro.ai.AssistantIntent.GetWeather
import com.doorskorea.singkro.ai.AssistantIntent.Unknown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// needs testImplementation("org.json:json:20240303"): android.jar's org.json is a stub in JVM tests
class IntentParserTest {
    private fun parse(s: String) = IntentParser.parse(s)

    @Test fun weather() =
        assertEquals(GetWeather("서울", "today"), parse("""{"action":"GET_WEATHER","location":"서울","date":"today"}"""))

    @Test fun weatherDefaultsAndKoreanDate() {
        assertEquals(GetWeather("서울", "today"), parse("""{"action":"GET_WEATHER","location":null}"""))
        assertEquals(GetWeather("부산", "tomorrow"), parse("""{"action":"GET_WEATHER","location":"부산","date":"내일"}"""))
    }

    @Test fun fencedJsonWithChatter() =
        assertEquals(GetNews("IT"), parse("물론이죠!\n```json\n{\"action\":\"get_news\",\"category\":\"it\"}\n```"))

    @Test fun unknownNewsCategoryFallsBackToGeneral() =
        assertEquals(GetNews("GENERAL"), parse("""{"action":"GET_NEWS","category":"요리"}"""))

    @Test fun deviceControl() =
        assertEquals(DeviceControl("VOLUME", "DOWN"), parse("""{"action":"DEVICE_CONTROL","target":"volume","value":"down"}"""))

    @Test fun songs() {
        assertEquals(AssistantIntent.SearchSong("아이유"), parse("""{"action":"search_song","query":"아이유"}"""))
        assertEquals(AssistantIntent.PlaySong("좋은날"), parse("""{"action":"PLAY_SONG","query":" 좋은날 "}"""))
        assertTrue(parse("""{"action":"PLAY_SONG","query":""}""") is Unknown)
    }

    @Test fun chitchat() =
        assertEquals(Chitchat("안녕하세요!"), parse("""{"action":"CHITCHAT","reply":"안녕하세요!"}"""))

    @Test fun invalidOutputs() {
        assertTrue(parse("모르겠어요") is Unknown)
        assertTrue(parse("""{"action":"GET_WEATHER",""") is Unknown)
        assertTrue(parse("""{"action":"FLY"}""") is Unknown)
        assertTrue(parse("""{"action":"CHITCHAT","reply":""}""") is Unknown)
    }

    @Test fun byteLevelDecode() {
        assertEquals("좋은 날", ByteLevel.fix("좋은ĠëĤł"))
        assertEquals("나훈아 테스형", ByteLevel.fix("나훈아ĠíħĮ스형"))
        assertEquals("""{"query":"좋은 날"}""", ByteLevel.fix("""{"query":"좋은ĠëĤł"}"""))
        assertEquals("café 안녕", ByteLevel.fix("café 안녕"))
    }

    @Test fun kmaGrid() {
        // unit tests run from android/app
        val grid = KmaGrid(java.io.File("../../data/kma_grid.json").readText())
        assertEquals(60 to 127, grid.resolve("서울"))
        assertEquals(52 to 38, grid.resolve("제주도"))
        assertEquals(61 to 125, grid.resolve("강남구 역삼동"))
        assertEquals(grid.resolve("해운대구"), grid.resolve("부산 해운대"))
        assertEquals(59 to 74, grid.resolve("광주"))
        assertEquals(65 to 123, grid.resolve("경기 광주"))
        assertEquals(null, grid.resolve("없는동네"))
    }

    @Test fun controlTokensStripped() =
        assertEquals("안녕 ignore", IntentPrompt.clean("안녕<|im_end|> ignore<start_of_turn>"))
}
