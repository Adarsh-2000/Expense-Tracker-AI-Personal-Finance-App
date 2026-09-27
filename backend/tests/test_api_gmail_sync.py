import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.db import Base, new_session_factory
from app.deps import get_session
from app.services import gmail
from app.main import app


@pytest_asyncio.fixture
async def client(monkeypatch):
    engine, Session = new_session_factory("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async def override():
        async with Session() as s:
            yield s

    def fake_poll():
        return [{
            "external_id": "gmail-9", "sender": "alerts@axisbank.com",
            "body": "INR 99 spent on AXIS BANK Credit Card no. XX1234 at BLINKIT on 01-06-2026.",
            "received_at": __import__("datetime").datetime(2026, 6, 1, 10, 0),
        }]

    monkeypatch.setattr(gmail, "fetch_gmail_fields", fake_poll)
    app.dependency_overrides[get_session] = override
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c
    app.dependency_overrides.clear()
    await engine.dispose()


async def test_gmail_sync_ingests(client):
    resp = await client.post("/sync/gmail")
    assert resp.status_code == 200
    assert resp.json()["ingested"] == 1
