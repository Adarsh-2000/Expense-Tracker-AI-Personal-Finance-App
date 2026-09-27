from __future__ import annotations

import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.config import settings
from app.auth import hash_pin
from app import security
from app.db import Base, new_session_factory
from app.deps import get_session
from app.main import app


@pytest_asyncio.fixture
async def client(monkeypatch):
    monkeypatch.setattr(settings, "app_pin_salt", "test-salt")
    monkeypatch.setattr(settings, "app_pin_hash", hash_pin("1234", "test-salt"))
    security.sessions.revoke_all()
    security.unlock_limiter.reset()
    engine, Session = new_session_factory("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async def override():
        async with Session() as s:
            yield s

    app.dependency_overrides[get_session] = override
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c
    app.dependency_overrides.clear()
    await engine.dispose()


async def test_wrong_pin_rejected(client):
    assert (await client.post("/auth/unlock", json={"pin": "0000"})).status_code == 401


async def test_correct_pin_returns_token(client):
    resp = await client.post("/auth/unlock", json={"pin": "1234"})
    assert resp.status_code == 200 and "token" in resp.json()


async def test_guarded_endpoint_requires_token(client):
    assert (await client.get("/transactions")).status_code == 401


async def test_guarded_endpoint_with_token_succeeds(client):
    token = (await client.post("/auth/unlock", json={"pin": "1234"})).json()["token"]
    resp = await client.get("/transactions", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 200


async def test_rate_limit_locks_after_repeated_failures(client):
    for _ in range(5):
        await client.post("/auth/unlock", json={"pin": "0000"})
    # 6th attempt is rate-limited regardless of correctness
    assert (await client.post("/auth/unlock", json={"pin": "1234"})).status_code == 429


async def test_forged_token_rejected(client):
    resp = await client.get("/transactions", headers={"Authorization": "Bearer not-a-real-token"})
    assert resp.status_code == 401
