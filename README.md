# SINGKRO Project (Doors Korea AI assistant)

```
backend/   FastAPI (REST + WebSocket, port 8000)
frontend/  Next.js App Router + TypeScript + Tailwind (port 3000)
```

## Backend
```bash
cd backend
python -m venv .venv && .venv\Scripts\activate
pip install -r requirements.txt
uvicorn main:app --reload --port 8000
```
- `GET /api/health`, `GET /api/status`, `WS /ws`

## Frontend
```bash
cd frontend
npm install
npm run dev
```
