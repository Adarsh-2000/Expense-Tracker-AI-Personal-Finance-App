# Expense Tracker

A personal finance tracker built for Indian bank SMS alerts and Gmail notifications. Parses bank messages, categorises spending, tracks account balances, and supports zero-based budgeting.

## Architecture

| Layer | Tech |
|---|---|
| Backend API | FastAPI (async), SQLAlchemy 2 async, Pydantic v2 |
| Database | SQLite (dev, via aiosqlite) or PostgreSQL (prod, via asyncpg) |
| Web UI | React + TypeScript + Vite ("Ledger Noir" dark theme) |
| Android | Planned — SMS forwarding client |

The backend exposes a REST API on port 8000. The web frontend runs on port 5173 and proxies `/api` calls to the backend.

## Backend Setup

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
```

> **Note on asyncpg and Python 3.14:** asyncpg may fail to build on Python 3.14 (no pre-built wheel). For local development, skip asyncpg entirely and use SQLite via the environment variable below. If you need PostgreSQL, install asyncpg separately or use Docker (see below).

## Running the Backend (SQLite — no extra dependencies)

```bash
cd backend
ET_DATABASE_URL=sqlite+aiosqlite:///./expense.db .venv/bin/python -m uvicorn app.main:app --reload
```

Tables are created automatically on first startup. The API is available at http://localhost:8000. Interactive docs at http://localhost:8000/docs.

A ready-made dev env file is provided:

```bash
# backend/.env.dev already contains:
# ET_DATABASE_URL=sqlite+aiosqlite:///./expense.db
```

## Running the Backend (PostgreSQL via Docker)

```bash
docker compose up -d db   # starts postgres on 5432
cd backend
ET_DATABASE_URL=postgresql+asyncpg://expense:expense@localhost:5432/expense \
  .venv/bin/python -m uvicorn app.main:app --reload
```

## Running the Web UI

```bash
cd web
npm install
npm run dev
```

Opens at http://localhost:5173. The dev server expects the backend running on port 8000.

## Optional: PIN Lock

Set two environment variables to password-protect the API:

```
ET_APP_PIN_SALT=<any random string>
ET_APP_PIN_HASH=<sha256 of "<salt>:<pin>">
```

Compute the hash with:

```python
python3 -c "import hashlib; print(hashlib.sha256('<salt>:<pin>'.encode()).hexdigest())"
```

All endpoints except `POST /auth/unlock` will require a `Bearer` token obtained by posting the PIN to `/auth/unlock`.

## Gmail Sync

Place a valid `token.json` (OAuth2 credentials for the Gmail API) in the `backend/` directory, then call:

```
POST /sync/gmail
```

The poller queries for messages from axisbank.com, idfcfirstbank.com, and bankofbaroda.co.in.

## Parser Calibration

SMS regexes live in `backend/app/parsing/banks/`. They were written against sample messages; tune the patterns against your own redacted SMS transcripts if a bank's messages are not being parsed correctly.

## Tests

```bash
cd backend
.venv/bin/python -m pytest -q
```

84 tests run against an in-memory SQLite database; no network or database server required.
# Expense-Tracker-AI-Personal-Finance-App
