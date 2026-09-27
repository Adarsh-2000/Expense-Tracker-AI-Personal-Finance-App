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


async def test_review_queue(client):
    # POST an SMS from an unknown sender — ingestion sets parsed_status="needs_review"
    r = await client.post("/ingest/sms", json={
        "sender": "NOPE",
        "body": "junk",
        "received_at": "2026-06-01T10:00:00",
        "external_id": "rv1",
    })
    assert r.status_code == 202

    # GET /review — should contain the message with sender "NOPE"
    r = await client.get("/review")
    assert r.status_code == 200
    messages = r.json()
    assert len(messages) == 1
    assert messages[0]["sender"] == "NOPE"
