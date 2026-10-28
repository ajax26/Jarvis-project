package com.doorskorea.singkro.ai

import com.doorskorea.singkro.ai.AssistantIntent.DeviceControl
import com.doorskorea.singkro.ai.AssistantIntent.PlaySong
import com.doorskorea.singkro.ai.AssistantIntent.SearchSong
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IntentRulesTest {
    private fun match(s: String) = IntentRules.match(s)

    @Test fun volume() {
        assertEquals(DeviceControl("VOLUME", "UP"), match("볼륨 키워줘"))
        assertEquals(DeviceControl("VOLUME", "DOWN"), match("소리 좀 줄여 줘"))
        assertEquals(DeviceControl("VOLUME", "MUTE"), match("소리 꺼줘"))
        assertEquals(DeviceControl("VOLUME", "MUTE"), match("음소거 해 줘"))
        assertEquals(DeviceControl("VOLUME", "30"), match("볼륨 30으로 맞춰줘"))
        assertEquals(DeviceControl("VOLUME", "100"), match("음량 150%로 해 줘"))
    }

    @Test fun volumeLeftToTheModel() {
        assertNull(match("음소거 풀어 줘"))
        assertNull(match("소리 켜 줘"))
        assertNull(match("무슨 소리야?"))
    }

    @Test fun play() {
        assertEquals(PlaySong("좋은날"), match("좋은날 틀어줘"))
        assertEquals(PlaySong("아이유의 좋은 날"), match("아이유의 좋은 날 시작해 줘"))
        assertEquals(PlaySong("방탄소년단 다이너마이트"), match("방탄소년단 다이너마이트 예약해줘"))
        assertEquals(PlaySong("나훈아 테스형"), match("나훈아 테스형 부를래"))
        assertEquals(PlaySong("고향의 봄"), match("고향의 봄 노래 좀 틀어 줘"))
        // "의" stays here; IntentHandler retries the catalog without it
        assertEquals(PlaySong("아이유의 좋은 날"), match("아이유의 좋은 날 부르고 싶어"))
        assertEquals(PlaySong("섬마을 선생님"), match("섬마을 선생님 노래 불러 줘"))
        assertEquals(PlaySong("좋은 날"), match("좋은 날 찾아서 틀어 줘"))
    }

    @Test fun search() {
        assertEquals(SearchSong("아이유"), match("아이유 노래 찾아줘"))
        assertEquals(SearchSong("ㅈㅇㄴ"), match("ㅈㅇㄴ 검색해줘"))
    }

    @Test fun notSongs() {
        assertNull(match("뉴스 틀어 줘"))
        assertNull(match("라디오 틀어 줘"))
        assertNull(match("타이머 시작해 줘"))
        assertNull(match("노래 틀어 줘"))
        assertNull(match("음악 틀어 줘"))
        assertNull(match("엄마 불러 줘"))
        assertNull(match("트로트 신나는 노래 뭐 있어?"))
        assertNull(match("오늘 서울 날씨 어때?"))
    }

    /** Whatever the rules take from the shared eval set must be what the eval expects. */
    @Test fun agreesWithEvalCases() {
        val cases = JSONArray(File("../../eval/intent_cases.json").readText())
        var matched = 0
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val got = match(case.getString("utterance")) ?: continue
            matched++
            val fields = when (got) {
                is DeviceControl -> mapOf("action" to "DEVICE_CONTROL", "target" to got.target, "value" to got.value)
                is SearchSong -> mapOf("action" to "SEARCH_SONG", "query" to got.query)
                is PlaySong -> mapOf("action" to "PLAY_SONG", "query" to got.query)
                else -> mapOf("action" to got.toString())
            }
            val expected = case.getJSONObject("expected")
            for (key in expected.keys()) {
                assertEquals("${case.getString("utterance")} [$key]", expected.getString(key), fields[key])
            }
        }
        // 3 volume + 2 search + 3 play
        assertTrue("rules matched only $matched eval cases", matched >= 8)
    }
}
