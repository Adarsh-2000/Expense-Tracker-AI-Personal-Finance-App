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


async def test_recurring_series(client):
    # Create 3 manual NETFLIX debits at monthly intervals
    for occurred_at in ["2026-04-01T12:00:00", "2026-05-01T12:00:00", "2026-06-01T12:00:00"]:
        r = await client.post("/transactions/manual", json={
            "merchant": "NETFLIX", "amount": 199, "direction": "debit",
            "occurred_at": occurred_at,
        })
        assert r.status_code == 201

    # GET /recurring — should detect 1 series for NETFLIX
    r = await client.get("/recurring")
    assert r.status_code == 200
    series = r.json()
    assert len(series) == 1
    assert series[0]["merchant"] == "NETFLIX"
