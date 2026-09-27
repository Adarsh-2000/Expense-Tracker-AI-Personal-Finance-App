import pytest_asyncio
from httpx import ASGITransport, AsyncClient

import app.routers.sync as sync_router
from app.db import Base, new_session_factory
from app.deps import get_session
from app.main import app


@pytest_asyncio.fixture
async def client():
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


async def test_sync_gmail_invokes_investment_sync(client, monkeypatch):
    calls = {"n": 0}

    async def fake_sync_investments(session):
        calls["n"] += 1
        return {"status": "ok"}

    monkeypatch.setattr(sync_router, "sync_investments", fake_sync_investments)

    r = await client.post("/sync/gmail")
    assert r.status_code == 200
    assert calls["n"] == 1


async def test_sync_gmail_survives_investment_failure(client, monkeypatch):
    async def boom(session):
        raise RuntimeError("portfolio blew up")

    monkeypatch.setattr(sync_router, "sync_investments", boom)

    r = await client.post("/sync/gmail")
    assert r.status_code == 200
    body = r.json()
    assert "ingested" in body and "fetched" in body
