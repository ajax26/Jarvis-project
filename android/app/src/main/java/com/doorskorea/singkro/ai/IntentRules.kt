package com.doorskorea.singkro.ai

/**
 * Song and volume requests matched by rule, skipping the LLM (~2.7 s on the RK3588), as the SingTree sample did.
 * Returns null for anything else, which then goes to the LLM. Kept conservative on purpose: a wrong match can't be
 * corrected by the model, while a miss only costs the LLM's time.
 */
object IntentRules {
    private val VOLUME_WORD = Regex("볼륨|소리|음량|음소거")
    private val VOLUME_SET = Regex("(\\d{1,3})\\s*(%|퍼센트|프로)?\\s*(으로|로|에|까지)")
    private val VOLUME_MUTE = Regex("음소거|(볼륨|소리|음량)\\s*(좀\\s*)?(꺼|끄|없애|죽여)")
    private val VOLUME_UP = Regex("키워|키우|올려|올리|크게|높여|높이")
    private val VOLUME_DOWN = Regex("줄여|줄이|내려|내리|작게|낮춰|낮추")
    private val VOLUME_UNMUTE = Regex("해제|풀어|켜")

    // "틀어 줘", "재생해", "시작해 줘", "예약해 줘", "부를래", "부르고 싶어"; "불러 줘" only with 노래/곡 ("엄마 불러 줘")
    private val PLAY_VERB = Regex("(틀어|재생|시작|예약|부를래|부를게|부르고\\s*싶|부르자)")
    private val CALL_VERB = Regex("불러")
    private val SEARCH_VERB = Regex("(찾아|검색)")
    private val SONG_WORD = Regex("노래|곡")

    // things you "틀어/시작" that aren't songs; left to the LLM
    private val NOT_SONG = Regex(
        "뉴스|날씨|라디오|티비|TV|텔레비전|유튜브|영상|타이머|알람|게임|운동|녹음|노래방|에어컨|불|볼륨|소리|^(음악|노래|곡|아무거나)$",
        RegexOption.IGNORE_CASE,
    )

    fun match(utterance: String): AssistantIntent? {
        val text = utterance.replace(Regex("[,.!?~]"), " ").replace(Regex("\\s+"), " ").trim()
        return volume(text) ?: song(text)
    }

    private fun volume(text: String): AssistantIntent.DeviceControl? {
        // "음소거 풀어 줘", "소리 켜 줘": unmute isn't a DeviceControl value yet, so not a rule match
        if (!VOLUME_WORD.containsMatchIn(text) || VOLUME_UNMUTE.containsMatchIn(text)) return null
        val value = when {
            VOLUME_MUTE.containsMatchIn(text) -> "MUTE"
            VOLUME_SET.find(text) != null -> VOLUME_SET.find(text)!!.groupValues[1].toInt().coerceIn(0, 100).toString()
            VOLUME_UP.containsMatchIn(text) -> "UP"
            VOLUME_DOWN.containsMatchIn(text) -> "DOWN"
            else -> return null
        }
        return AssistantIntent.DeviceControl("VOLUME", value)
    }

    private fun song(text: String): AssistantIntent? {
        val hasSongWord = SONG_WORD.containsMatchIn(text)
        val play = PLAY_VERB.find(text) ?: CALL_VERB.find(text)?.takeIf { hasSongWord }
        val search = SEARCH_VERB.find(text)
        // the title is what precedes the first verb; any play verb makes it a play ("좋은 날 찾아서 틀어 줘")
        val first = listOfNotNull(play, search).minByOrNull { it.range.first } ?: return null
        val query = title(text.substring(0, first.range.first)) ?: return null
        return if (play != null) AssistantIntent.PlaySong(query) else AssistantIntent.SearchSong(query)
    }

    /** "고향의 봄 노래 좀" -> "고향의 봄"; null when nothing song-like is left. */
    private fun title(before: String): String? {
        val q = before
            .replace(Regex("\\s*(을|를)?\\s*(좀)?\\s*$"), "")
            .replace(Regex("\\s*(노래|곡)\\s*(을|를|으로|로|좀|하나|한\\s*곡)?\\s*(좀|하나|한\\s*곡)?\\s*$"), "")
            .replace(Regex("\\s*(좀|하나|한\\s*곡|한\\s*번|한번)\\s*$"), "")
            .replace(Regex("^(우리|나|저|내가|제가|오늘|지금|그럼|그러면)\\s+"), "")
            .trim()
        return q.takeIf { it.isNotEmpty() && !NOT_SONG.containsMatchIn(it) }
    }
}
