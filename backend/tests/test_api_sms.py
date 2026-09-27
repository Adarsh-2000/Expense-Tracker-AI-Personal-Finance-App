import pytest_asyncio
from httpx import ASGITransport, AsyncClient

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


async def test_post_sms_creates_transaction(client):
    resp = await client.post("/ingest/sms", json={
        "sender": "AX-AXISBK",
        "body": "INR 450 spent on AXIS BANK Credit Card no. XX1234 at SWIGGY on 01-06-2026.",
        "received_at": "2026-06-01T10:00:00",
        "external_id": "api-1",
    })
    assert resp.status_code == 201
    assert resp.json()["amount"] == 450.0


async def test_post_unknown_sms_returns_202_review(client):
    resp = await client.post("/ingest/sms", json={
        "sender": "NOPE", "body": "gibberish", "received_at": "2026-06-01T10:00:00", "external_id": "api-2",
    })
    assert resp.status_code == 202
