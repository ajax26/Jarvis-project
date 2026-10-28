import asyncio
import json
import os
import sys
from pathlib import Path

import anthropic
import httpx
from dotenv import load_dotenv
from fastapi import APIRouter, FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware

load_dotenv()

# "ollama" = local chat model (default), "claude" = Claude API,
# "intent" = Android on-device assistant pipeline from pc-test/ (intent JSON -> weather/news/device)
LLM_PROVIDER = os.getenv("LLM_PROVIDER", "ollama")
INTENT_MODEL = os.getenv("INTENT_MODEL", "qwen2.5:1.5b")
OLLAMA_URL = os.getenv("OLLAMA_URL", "http://localhost:11434")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "gemma4:e2b-it-qat")
CLAUDE_MODEL = "claude-opus-5-5"
SYSTEM_PROMPT = (
    "You are SINGKRO, the AI assistant of Doors Korea (도어스코리아): calm, precise, friendly and unfailingly polite. "
    "Keep answers short and conversational, since they are shown in a small HUD console. "
    "Reply in the language the user writes in."
)

# reads ANTHROPIC_API_KEY from the environment / backend/.env
claude = anthropic.AsyncAnthropic()
# no read timeout: a cold model load on a small GPU can take a while
ollama = httpx.AsyncClient(base_url=OLLAMA_URL, timeout=httpx.Timeout(None, connect=5))

app = FastAPI(title="SINGKRO API", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:3000", "http://127.0.0.1:3000"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

api = APIRouter(prefix="/api")


@api.get("/health")
async def health():
    return {"status": "online"}


@api.get("/status")
async def status():
    model = {"ollama": OLLAMA_MODEL, "claude": CLAUDE_MODEL, "intent": INTENT_MODEL}.get(LLM_PROVIDER)
    return {"name": "SINGKRO", "systems": "nominal", "provider": LLM_PROVIDER, "model": model}


app.include_router(api)


@app.get("/")
async def root():
    return {"message": "SINGKRO backend running"}


async def reply_ollama(ws: WebSocket, history: list):
    messages = [{"role": "system", "content": SYSTEM_PROMPT}, *history]
    parts = []
    # think off: on gemma4 e2b it cuts reply time ~4x (32s -> 8s on a GTX 1050 Ti)
    body = {"model": OLLAMA_MODEL, "messages": messages, "think": False}
    async with ollama.stream("POST", "/api/chat", json=body) as r:
        if r.status_code != 200:
            await r.aread()
            await ws.send_json({"type": "error", "text": f"Ollama {r.status_code}: {r.json().get('error', r.text)}"})
            return
        # NDJSON: one chunk per line
        async for line in r.aiter_lines():
            if not line:
                continue
            chunk = json.loads(line)
            text = chunk.get("message", {}).get("content", "")
            if text:
                parts.append(text)
                await ws.send_json({"type": "delta", "text": text})
    history.append({"role": "assistant", "content": "".join(parts)})


async def reply_intent(ws: WebSocket, history: list):
    # pc-test/ is a plain script folder (hyphenated name), so load it by path
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "pc-test"))
    import assistant

    family = "qwen" if "qwen" in INTENT_MODEL else "gemma"
    # stateless per utterance, like the device; handle() is blocking, so run it off the event loop
    intent, reply = await asyncio.to_thread(assistant.handle, history[-1]["content"], INTENT_MODEL, family)
    await ws.send_json({"type": "system", "text": "intent " + json.dumps(intent, ensure_ascii=False)})
    await ws.send_json({"type": "delta", "text": reply})
    history.append({"role": "assistant", "content": reply})


async def reply_claude(ws: WebSocket, history: list):
    async with claude.beta.messages.stream(
        model=CLAUDE_MODEL,
        max_tokens=64000,
        system=SYSTEM_PROMPT,
        messages=history,
        output_config={"effort": "low"},
        betas=["server-side-fallback-2026-07-01"],
        fallbacks="default",
    ) as stream:
        async for chunk in stream.text_stream:
            await ws.send_json({"type": "delta", "text": chunk})
        final = await stream.get_final_message()
    history.append({"role": "assistant", "content": final.content})
    if final.stop_reason == "refusal":
        await ws.send_json({"type": "error", "text": "I'm afraid I can't help with that."})


@app.websocket("/ws")
async def websocket_endpoint(ws: WebSocket):
    await ws.accept()
    await ws.send_json({"type": "system", "text": "Connected. SINGKRO online."})
    # per-connection memory; append-only so Claude thinking blocks stay valid across turns
    history: list = []
    reply = {"claude": reply_claude, "intent": reply_intent}.get(LLM_PROVIDER, reply_ollama)
    try:
        while True:
            history.append({"role": "user", "content": await ws.receive_text()})
            await ws.send_json({"type": "start"})
            try:
                await reply(ws, history)
            except httpx.ConnectError:
                await ws.send_json({"type": "error", "text": f"Ollama is not running at {OLLAMA_URL}."})
            except httpx.HTTPError as e:
                await ws.send_json({"type": "error", "text": f"Ollama error: {e}"})
            except anthropic.AuthenticationError:
                await ws.send_json({"type": "error", "text": "Invalid API key. Check ANTHROPIC_API_KEY in backend/.env."})
            except anthropic.RateLimitError:
                await ws.send_json({"type": "error", "text": "Rate limited. Please try again shortly."})
            except anthropic.APIStatusError as e:
                await ws.send_json({"type": "error", "text": f"API error {e.status_code}: {e.message}"})
            except anthropic.APIConnectionError:
                await ws.send_json({"type": "error", "text": "Cannot reach the Claude API. Check the network."})
            except anthropic.AnthropicError as e:
                # e.g. no credentials configured
                await ws.send_json({"type": "error", "text": str(e)})
            await ws.send_json({"type": "done"})
    except WebSocketDisconnect:
        pass
