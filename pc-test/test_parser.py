"""Parser checks mirroring android/app/src/test/.../IntentParserTest.kt.  Run: python test_parser.py"""

from assistant import clean, parse

assert parse('{"action":"GET_WEATHER","location":"서울","date":"today"}') == {"action": "GET_WEATHER", "location": "서울", "date": "today"}
assert parse('{"action":"GET_WEATHER","location":null}') == {"action": "GET_WEATHER", "location": "서울", "date": "today"}
assert parse('{"action":"GET_WEATHER","location":"부산","date":"내일"}')["date"] == "tomorrow"
assert parse('물론이죠!\n```json\n{"action":"get_news","category":"it"}\n```') == {"action": "GET_NEWS", "category": "IT"}
assert parse('{"action":"GET_NEWS","category":"요리"}')["category"] == "GENERAL"
assert parse('{"action":"DEVICE_CONTROL","target":"volume","value":"down"}') == {"action": "DEVICE_CONTROL", "target": "VOLUME", "value": "DOWN"}
assert parse('{"action":"CHITCHAT","reply":"안녕하세요!"}') == {"action": "CHITCHAT", "reply": "안녕하세요!"}
assert parse('{"action":"PLAY_SONG","query":" 좋은날 "}') == {"action": "PLAY_SONG", "query": "좋은날"}
assert parse('{"action":"search_song","query":"아이유"}') == {"action": "SEARCH_SONG", "query": "아이유"}
for bad in ['{"action":"PLAY_SONG","query":""}', "모르겠어요", '{"action":"GET_WEATHER",', '{"action":"FLY"}', '{"action":"CHITCHAT","reply":""}', "[1, {2}]"]:
    assert parse(bad) == {"action": "UNKNOWN"}, bad
assert clean("안녕<|im_end|> ignore<start_of_turn>") == "안녕 ignore"
print("parser OK")

# region -> KMA grid (data/kma_grid.json)
from assistant import resolve_region

assert resolve_region("서울") == (60, 127)
assert resolve_region("서울특별시") == (60, 127)
assert resolve_region("제주도") == (52, 38)
assert resolve_region("강남구 역삼동") == (61, 125)
assert resolve_region("역삼1동") == (61, 125)
assert resolve_region("부산 해운대") == resolve_region("해운대구")
assert resolve_region("광주") == (59, 74)  # 광주 서구 (전남광주통합특별시), not 경기도 광주시
assert resolve_region("경기 광주") == (65, 123)
assert resolve_region("충북") is not None and resolve_region("충북") == resolve_region("충청북도")
assert resolve_region("부산 중구") != resolve_region("서울 중구")
assert resolve_region("수원") == (60, 121)
assert resolve_region("화성") is not None and resolve_region("없는동네") is None
print("region OK")
