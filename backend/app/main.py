from __future__ import annotations

import time
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app import security
from app.config import settings
from app.db import Base
from app.deps import _factory
from app.routers import (
    accounts, admin, auth, bills, budget, goals, insights, money_radar,
    notifications, portfolio, push, reports, rewards, savings, sync, transactions,
)


@asynccontextmanager
async def lifespan(_app):
    engine, _ = _factory()
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield


app = FastAPI(title="Expense Tracker", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def app_lock_middleware(request, call_next):
    # Protection is on when any auth mechanism is configured: PIN, paired device key,
    # or Google sign-in. With Google configured, the only way to obtain a session is a
    # server-verified Google ID token (/auth/verify-google).
    lock_active = (
        bool(settings.app_pin_hash)
        or bool(settings.device_key)
        or bool(settings.google_web_client_id)
    )
    if lock_active and request.url.path not in security.OPEN_PATHS:
        header = request.headers.get("authorization", "")
        token = header[7:] if header[:7].lower() == "bearer " else ""
        if not security.sessions.valid(token, time.time()):
            return JSONResponse({"detail": "locked"}, status_code=401)
    return await call_next(request)


app.include_router(auth.router)
app.include_router(transactions.router)
app.include_router(accounts.router)
app.include_router(savings.router)
app.include_router(budget.router)
app.include_router(bills.router)
app.include_router(reports.router)
app.include_router(insights.router)
app.include_router(notifications.router)
app.include_router(goals.router)
app.include_router(portfolio.router)
app.include_router(sync.router)
app.include_router(admin.router)
app.include_router(money_radar.router)
app.include_router(push.router)
app.include_router(rewards.router)
