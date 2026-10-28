from fastapi import APIRouter, FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(title="JARVIS API", version="0.1.0")

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
    return {"name": "J.A.R.V.I.S.", "systems": "nominal"}


app.include_router(api)


@app.get("/")
async def root():
    return {"message": "JARVIS backend running"}


@app.websocket("/ws")
async def websocket_endpoint(ws: WebSocket):
    await ws.accept()
    await ws.send_json({"type": "system", "text": "Connected. Good day, sir."})
    try:
        while True:
            text = await ws.receive_text()
            # placeholder: replace echo with AI response pipeline
            await ws.send_json({"type": "reply", "text": f"Received: {text}"})
    except WebSocketDisconnect:
        pass
