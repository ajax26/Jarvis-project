"""PC test bench for the Android on-device assistant.

Runs the same prompts, JSON parser and handlers as android/.../ai/*.kt, with Ollama standing in
for MediaPipe, so prompts and models can be tuned on a PC before going to the RK3588.

  python assistant.py                         # chat (default model qwen2.5:1.5b)
  python assistant.py --model gemma2:2b
  python assistant.py --eval                  # intent accuracy + latency on CASES
"""

import argparse
import json
import os
import re
import time
from pathlib import Path
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone

import httpx
from dotenv import load_dotenv

# secrets come from backend/.env (git-ignored) or the environment
load_dotenv(Path(__file__).resolve().parent.parent / "backend" / ".env")

OLLAMA_URL = os.getenv("OLLAMA_URL", "http://localhost:11434")
KMA_AUTH_KEY = os.getenv("KMA_AUTH_KEY", "")  # 기상청 API허브 (apihub.kma.go.kr) authKey
SINGKRO_WEB = os.getenv("SINGKRO_WEB", "https://singkro1.mycafe24.com")

# --- keep in sync with IntentPrompt.kt -------------------------------------------------------
INTENT_SYSTEM = """
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
""".strip()

CONTROL_TOKENS = re.compile(r"<\|im_(start|end)\|>|<(start|end)_of_turn>")


def clean(text: str) -> str:
    return CONTROL_TOKENS.sub("", text)


def wrap(system: str, user: str, family: str) -> str:
    if family == "qwen":
        return f"<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n"
    return f"<start_of_turn>user\n{system}\n\n{user}<end_of_turn>\n<start_of_turn>model\n"


def intent_prompt(utterance: str, family: str) -> str:
    return wrap(INTENT_SYSTEM, f"발화: {clean(utterance)}", family)


# --- keep in sync with AssistantIntent.kt ----------------------------------------------------
NEWS_CATEGORIES = {"IT", "ECONOMY", "SPORTS", "SCIENCE", "ENTERTAINMENT", "GENERAL"}


def parse(raw: str) -> dict:
    start, end = raw.find("{"), raw.rfind("}")
    if start < 0 or end <= start:
        return {"action": "UNKNOWN"}
    try:
        obj = json.loads(raw[start : end + 1])
    except json.JSONDecodeError:
        return {"action": "UNKNOWN"}
    if not isinstance(obj, dict):
        return {"action": "UNKNOWN"}

    def s(key):
        v = obj.get(key)
        return "" if v is None else str(v).strip()

    action = s("action").upper()
    if action == "GET_WEATHER":
        return {
            "action": action,
            "location": s("location") or "서울",
            "date": "tomorrow" if s("date").lower() in ("tomorrow", "내일") else "today",
        }
    if action == "GET_NEWS":
        cat = s("category").upper()
        return {"action": action, "category": cat if cat in NEWS_CATEGORIES else "GENERAL"}
    if action == "DEVICE_CONTROL":
        return {"action": action, "target": s("target").upper(), "value": s("value").upper()}
    if action in ("SEARCH_SONG", "PLAY_SONG") and s("query"):
        return {"action": action, "query": s("query")}
    if action == "CHITCHAT" and s("reply"):
        return {"action": action, "reply": s("reply")}
    return {"action": "UNKNOWN"}


# --- LLM (Ollama, raw mode so the prompt string is byte-identical to the Android one) -----------
def generate(prompt: str, model: str, temperature=0.1, top_k=1) -> str:
    r = httpx.post(
        f"{OLLAMA_URL}/api/generate",
        json={
            "model": model,
            "prompt": prompt,
            "raw": True,
            "stream": False,
            "options": {"temperature": temperature, "top_k": top_k, "num_ctx": 1280},
        },
        timeout=None,
    )
    r.raise_for_status()
    return r.json()["response"]


# --- data sources (keep in sync with IntentHandler.kt) ----------------------------------------
GRID = {
    "서울": (60, 127), "부산": (98, 76), "대구": (89, 90), "인천": (55, 124), "광주": (58, 74),
    "대전": (67, 100), "울산": (102, 84), "세종": (66, 103), "수원": (60, 121), "제주": (52, 38),
}
SKY = {"1": "맑고", "3": "구름이 많고", "4": "흐리고"}
PTY = {"1": "비", "2": "비 또는 눈", "3": "눈", "4": "소나기"}
KST = timezone(timedelta(hours=9))  # Korea has no DST; avoids needing tzdata on Windows


def fetch_weather(location: str, date: str) -> str | None:
    city = next((c for c in GRID if location.startswith(c)), None)
    if city is None:
        return None
    if not KMA_AUTH_KEY:
        raise RuntimeError("KMA_AUTH_KEY not set")
    nx, ny = GRID[city]
    now = datetime.now(KST) - timedelta(minutes=15)
    base_hour = next((h for h in (23, 20, 17, 14, 11, 8, 5, 2) if h <= now.hour), None)
    base_date = now.date() if base_hour is not None else now.date() - timedelta(days=1)
    target = (datetime.now(KST).date() + timedelta(days=1 if date == "tomorrow" else 0)).strftime("%Y%m%d")
    params = {
        "pageNo": 1, "numOfRows": 1000, "dataType": "JSON",
        "base_date": f"{base_date:%Y%m%d}", "base_time": f"{(base_hour if base_hour is not None else 23):02d}00",
        "nx": nx, "ny": ny, "authKey": KMA_AUTH_KEY,
    }
    url = "https://apihub.kma.go.kr/api/typ02/openApi/VilageFcstInfoService_2.0/getVilageFcst"
    # API허브 occasionally times out or answers 200 with an empty body; one retry clears it
    for attempt in range(2):
        try:
            r = httpx.get(url, params=params, timeout=10)
            body = r.json()
            break
        except (ValueError, httpx.TimeoutException):
            if attempt:
                raise
    if "result" in body:  # API허브 gateway error, e.g. {"result": {"status": 401, "message": "유효한 인증키가 아닙니다."}}
        raise RuntimeError(f"KMA API허브 {body['result'].get('status')}: {body['result'].get('message')}")
    r.raise_for_status()
    resp = body["response"]
    if resp["header"]["resultCode"] != "00":
        raise RuntimeError(f"KMA error {resp['header'].get('resultMsg')}")

    temps, pop, sky, pty = [], 0, None, None
    for it in resp["body"]["items"]["item"]:
        if it["fcstDate"] != target:
            continue
        v, cat = it["fcstValue"], it["category"]
        if cat == "TMP" and v.lstrip("-").isdigit():
            temps.append(int(v))
        elif cat == "POP" and v.isdigit():
            pop = max(pop, int(v))
        elif cat == "SKY" and (sky is None or it["fcstTime"] == "1200"):
            sky = v
        elif cat == "PTY" and v != "0":
            pty = v
    if not temps:
        raise RuntimeError(f"no forecast for {target}")
    # spoken as-is (no LLM rewrite): instant and nothing can be made up
    day = "내일" if date == "tomorrow" else "오늘"
    rain = f"{PTY[pty]} 소식이 있어요" if pty else "비 소식은 없어요"
    return (
        f"{day} {city} 날씨는 {SKY[sky] + ' ' if sky in SKY else ''}{rain}. "
        f"기온은 최저 {min(temps)}도, 최고 {max(temps)}도이고 강수확률은 최대 {pop}%예요."
    )


NEWS_NAMES = {"IT": "IT", "ECONOMY": "경제", "SPORTS": "스포츠", "SCIENCE": "과학", "ENTERTAINMENT": "연예", "GENERAL": "주요"}
TOPICS = {"IT": "TECHNOLOGY", "ECONOMY": "BUSINESS", "SPORTS": "SPORTS", "SCIENCE": "SCIENCE", "ENTERTAINMENT": "ENTERTAINMENT"}


def fetch_news(category: str, limit=5) -> str:
    locale = "hl=ko&gl=KR&ceid=KR:ko"
    topic = TOPICS.get(category)
    url = f"https://news.google.com/rss/headlines/section/topic/{topic}?{locale}" if topic else f"https://news.google.com/rss?{locale}"
    r = httpx.get(url, timeout=5, follow_redirects=True)
    r.raise_for_status()
    titles = [t.text.rsplit(" - ", 1)[0].strip() for t in ET.fromstring(r.text).iterfind("./channel/item/title")][:limit]
    if not titles:
        raise RuntimeError("empty news feed")
    # headlines are read as-is: an LLM summary of them distorted facts in testing
    return f"최신 {NEWS_NAMES.get(category, '주요')} 뉴스예요.\n" + "\n".join(f"{i}. {t}" for i, t in enumerate(titles, 1))


def search_songs(query: str, limit=5) -> list[dict]:
    """Singkro web catalog search: matches title, artist, lyricist, composer and 초성 (all words must match)."""
    r = httpx.get(f"{SINGKRO_WEB}/api/catalog/search", params={"q": query, "limit": limit}, timeout=5)
    r.raise_for_status()
    return r.json()["items"]


def player_url(cnts_seq: int) -> str:
    return f"{SINGKRO_WEB}/player?cnts_seq={cnts_seq}"


# --- handler (mirror of IntentHandler.handle) --------------------------------------------------
def handle(utterance: str, model: str, family: str) -> tuple[dict, str]:
    intent = parse(generate(intent_prompt(utterance, family), model))
    action = intent["action"]
    try:
        if action == "GET_WEATHER":
            info = fetch_weather(intent["location"], intent["date"])
            if info is None:
                return intent, f"{intent['location']} 날씨는 아직 지원하지 않는 지역이에요."
            return intent, info
        if action == "GET_NEWS":
            return intent, fetch_news(intent["category"])
        if action == "SEARCH_SONG":
            songs = search_songs(intent["query"])
            if not songs:
                return intent, f"'{intent['query']}'에 맞는 곡을 찾지 못했어요."
            # read the list as-is: summarising it with the LLM invites made-up titles
            return intent, "\n".join(f"{i}. {x['title']} - {x['artist']} (곡번호 {x['song_num']})" for i, x in enumerate(songs, 1))
        if action == "PLAY_SONG":
            songs = search_songs(intent["query"], limit=1)
            if not songs:
                return intent, f"'{intent['query']}'에 맞는 곡을 찾지 못했어요."
            song = songs[0]
            intent.update(cnts_seq=song["cnts_seq"], url=player_url(song["cnts_seq"]))
            return intent, f"{song['artist']} - '{song['title']}' 재생할게요."
        if action == "DEVICE_CONTROL":
            # PC has no device: report what Android would do
            return intent, f"[기기 제어] {intent['target']} -> {intent['value']}"
        if action == "CHITCHAT":
            return intent, intent["reply"]
    except (httpx.HTTPError, RuntimeError, KeyError, ValueError, ET.ParseError) as e:
        # httpx errors quote the request URL, which carries the auth key
        detail = str(e).replace(KMA_AUTH_KEY, "***") if KMA_AUTH_KEY else str(e)
        return intent, f"지금은 정보를 가져올 수 없어요. ({detail})"
    return intent, "죄송해요, 잘 이해하지 못했어요. 다시 말씀해 주세요."


# --- eval -------------------------------------------------------------------------------------
# shared with the Android app (bundled as an asset), so PC and device runs score the same set
CASES = [
    (c["utterance"], c["expected"])
    for c in json.load(open(Path(__file__).resolve().parent.parent / "eval" / "intent_cases.json", encoding="utf-8"))
]


def run_eval(model: str, family: str):
    ok, times = 0, []
    for utterance, expected in CASES:
        t0 = time.perf_counter()
        raw = generate(intent_prompt(utterance, family), model)
        times.append(time.perf_counter() - t0)
        got = parse(raw)
        # spacing-insensitive: "좋은 날" and "좋은날" hit the same catalog search
        hit = all(str(got.get(k, "")).replace(" ", "") == v.replace(" ", "") for k, v in expected.items())
        ok += hit
        print(f"{'OK  ' if hit else 'FAIL'} {times[-1]:5.1f}s  {utterance}  ->  {raw.strip()[:120]}")
    print(f"\n{model}: {ok}/{len(CASES)} correct, avg {sum(times) / len(times):.1f}s per intent")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="qwen2.5:1.5b")
    ap.add_argument("--eval", action="store_true")
    args = ap.parse_args()
    family = "qwen" if "qwen" in args.model else "gemma"

    if args.eval:
        run_eval(args.model, family)
        return
    print(f"model={args.model} ({family}); empty line to quit")
    while utterance := input("\n> ").strip():
        t0 = time.perf_counter()
        intent, reply = handle(utterance, args.model, family)
        print(f"  intent: {json.dumps(intent, ensure_ascii=False)}")
        print(f"  reply ({time.perf_counter() - t0:.1f}s): {reply}")


if __name__ == "__main__":
    main()
