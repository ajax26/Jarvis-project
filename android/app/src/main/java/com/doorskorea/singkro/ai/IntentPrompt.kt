package com.doorskorea.singkro.ai

enum class ModelFamily { QWEN, GEMMA }

object IntentPrompt {

    private val INTENT_SYSTEM = """
너는 음성 비서의 의도 분류기다. 사용자 발화를 분석해 아래 예시와 같은 형식의 JSON 한 줄만 출력하라.
설명, 마크다운, 코드블록 없이 JSON만 출력한다.

action별 필드:
- GET_WEATHER: location(도시명), date(today 또는 tomorrow 중 하나)
- GET_NEWS: category(IT, ECONOMY, SPORTS, SCIENCE, ENTERTAINMENT, GENERAL 중 하나)
- DEVICE_CONTROL: target(VOLUME), value(UP, DOWN, MUTE, 또는 0~100 사이 숫자 중 하나)
- SEARCH_SONG: query(노래방 곡 검색어: 제목, 가수, 초성)
- PLAY_SONG: query(재생할 곡의 제목과 가수)
- CHITCHAT: reply(짧은 한국어 답변)

규칙:
- 지역이 없으면 location은 "서울".
- 날짜가 없으면 date는 "today".
- 뉴스 분야가 없으면 category는 "GENERAL".
- 노래를 찾거나 목록을 보여 달라면 SEARCH_SONG, 노래를 부르거나 틀어/재생/예약해 달라면 PLAY_SONG.
- query에는 '노래', '찾아줘', '틀어줘' 같은 명령어를 빼고 제목과 가수만 넣는다.
- 날씨, 뉴스, 기기 제어, 노래 검색/재생이 아니면 CHITCHAT.

예시:
발화: 오늘 서울 날씨 어때?
{"action":"GET_WEATHER","location":"서울","date":"today"}
발화: 내일 부산 비 와?
{"action":"GET_WEATHER","location":"부산","date":"tomorrow"}
발화: 최신 IT 뉴스 읽어줘
{"action":"GET_NEWS","category":"IT"}
발화: 소리 좀 줄여줘
{"action":"DEVICE_CONTROL","target":"VOLUME","value":"DOWN"}
발화: 음소거 해줘
{"action":"DEVICE_CONTROL","target":"VOLUME","value":"MUTE"}
발화: 아이유 노래 찾아줘
{"action":"SEARCH_SONG","query":"아이유"}
발화: 임영웅 사랑은 늘 도망가 틀어줘
{"action":"PLAY_SONG","query":"임영웅 사랑은 늘 도망가"}
발화: 보고싶다 틀어줘
{"action":"PLAY_SONG","query":"보고싶다"}
발화: 안녕
{"action":"CHITCHAT","reply":"안녕하세요! 무엇을 도와드릴까요?"}
""".trim()

    /** Step 2: utterance -> JSON intent. */
    fun intent(utterance: String, family: ModelFamily) =
        wrap(INTENT_SYSTEM, "발화: ${clean(utterance)}", family)

    /** `prefix + rest` is the full templated prompt; `prefix` is constant per system prompt, so it can be cached. */
    data class Prompt(val prefix: String, val rest: String)

    private fun wrap(system: String, user: String, family: ModelFamily) = when (family) {
        ModelFamily.QWEN ->
            Prompt("<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n", "$user<|im_end|>\n<|im_start|>assistant\n")
        // Gemma has no system role: the instructions go at the top of the user turn
        ModelFamily.GEMMA ->
            Prompt("<start_of_turn>user\n$system\n\n", "$user<end_of_turn>\n<start_of_turn>model\n")
    }

    // speech and RSS text are untrusted: drop chat-template control tokens so they can't close the turn
    private val CONTROL_TOKENS = Regex("<\\|im_(start|end)\\|>|<(start|end)_of_turn>")
    internal fun clean(text: String) = CONTROL_TOKENS.replace(text, "")
}
