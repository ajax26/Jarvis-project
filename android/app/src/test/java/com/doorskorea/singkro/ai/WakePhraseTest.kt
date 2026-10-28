package com.doorskorea.singkro.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakePhraseTest {
    @Test fun wakeWordAlone() {
        assertEquals("", WakePhrase.after("씽씽"))
        assertEquals("", WakePhrase.after("싱싱아"))
        assertEquals("", WakePhrase.after("씽 씽!"))
    }

    @Test fun requestInTheSameBreath() {
        assertEquals("오늘 무슨 요일이야?", WakePhrase.after("씽씽, 오늘 무슨 요일이야?"))
        assertEquals("아이유 좋은 날 틀어 줘", WakePhrase.after("신신아 아이유 좋은 날 틀어 줘"))
    }

    @Test fun ordinaryWordsDoNotWake() {
        assertNull(WakePhrase.after("생선이 싱싱해"))
        assertNull(WakePhrase.after("싱싱한 과일 사 줘"))
        assertNull(WakePhrase.after("아이유 좋은 날 틀어 줘"))
    }
}
