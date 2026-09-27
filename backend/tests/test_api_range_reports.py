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


async def _seed(client):
    await client.post("/transactions/manual", json={
        "amount": 100, "merchant": "SWIGGY", "occurred_at": "2025-01-05T09:00:00",
    })
    await client.post("/transactions/manual", json={
        "amount": 200, "merchant": "AMAZON", "occurred_at": "2025-02-05T09:00:00",
    })
    await client.post("/transactions/manual", json={
        "amount": 400, "merchant": "UBER", "occurred_at": "2025-03-05T09:00:00",
    })


async def test_spent_range_scoped(client):
    await _seed(client)
    resp = await client.get("/reports/spent", params={"start": "2025-02-01", "end": "2025-03-10"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["spent"] == 600.0  # Feb 200 + Mar 400
    assert body["start"] == "2025-02-01"
    assert body["end"] == "2025-03-10"


async def test_summary_range_scoped(client):
    await _seed(client)
    resp = await client.get("/reports/summary", params={"start": "2025-02-01", "end": "2025-03-10"})
    assert resp.status_code == 200
    totals = {c["category"]: c["total"] for c in resp.json()}
    assert sum(totals.values()) == 600.0


async def test_spent_month_still_works(client):
    await _seed(client)
    resp = await client.get("/reports/spent", params={"month": "2025-01"})
    assert resp.status_code == 200
    assert resp.json()["spent"] == 100.0


async def test_spent_all_time_still_works(client):
    await _seed(client)
    resp = await client.get("/reports/spent")
    assert resp.status_code == 200
    assert resp.json()["spent"] == 700.0


async def test_summary_month_still_works(client):
    await _seed(client)
    resp = await client.get("/reports/summary", params={"month": "2025-01"})
    assert resp.status_code == 200
    totals = {c["category"]: c["total"] for c in resp.json()}
    assert sum(totals.values()) == 100.0
