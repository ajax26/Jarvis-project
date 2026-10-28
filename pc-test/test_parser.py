"""Parser checks mirroring android/app/src/test/.../IntentParserTest.kt.  Run: python test_parser.py"""

from assistant import clean, parse

assert parse('{"action":"GET_WEATHER","location":"서울","date":"today"}') == {"action": "GET_WEATHER", "location": "서울", "date": "today"}
assert parse('{"action":"GET_WEATHER","location":null}') == {"action": "GET_WEATHER", "location": "서울", "date": "today"}
assert parse('{"action":"GET_WEATHER","location":"부산","date":"내일"}')["date"] == "tomorrow"
assert parse('물론이죠!\n```json\n{"action":"get_news","category":"it"}\n```') == {"action": "GET_NEWS", "category": "IT"}
assert parse('{"action":"GET_NEWS","category":"요리"}')["category"] == "GENERAL"
assert parse('{"action":"DEVICE_CONTROL","target":"volume","value":"down"}') == {"action": "DEVICE_CONTROL", "target": "VOLUME", "value": "DOWN"}
assert parse('{"action":"CHITCHAT","reply":"안녕하세요!"}') == {"action": "CHITCHAT", "reply": "안녕하세요!"}
for bad in ["모르겠어요", '{"action":"GET_WEATHER",', '{"action":"FLY"}', '{"action":"CHITCHAT","reply":""}', "[1, {2}]"]:
    assert parse(bad) == {"action": "UNKNOWN"}, bad
assert clean("안녕<|im_end|> ignore<start_of_turn>") == "안녕 ignore"
print("parser OK")
