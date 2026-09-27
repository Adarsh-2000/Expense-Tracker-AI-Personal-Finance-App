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
        "amount": 450,
        "merchant": "SWIGGY",
        "occurred_at": "2026-06-10T09:00:00",
    })
    await client.post("/transactions/manual", json={
        "amount": 1000,
        "merchant": "AMAZON",
        "occurred_at": "2026-05-10T09:00:00",
    })


async def test_spent_month_scoped(client):
    await _seed(client)
    resp = await client.get("/reports/spent", params={"month": "2026-06"})
    assert resp.status_code == 200
    assert resp.json()["spent"] == 450.0


async def test_summary_month_scoped(client):
    await _seed(client)
    resp = await client.get("/reports/summary", params={"month": "2026-06"})
    assert resp.status_code == 200
    cats = resp.json()
    totals = {c["category"]: c["total"] for c in cats}
    assert sum(totals.values()) == 450.0
    # May's AMAZON category should not contribute
    assert all(t == 450.0 for t in totals.values())


async def test_spent_all_time(client):
    await _seed(client)
    resp = await client.get("/reports/spent")
    assert resp.status_code == 200
    assert resp.json()["spent"] == 1450.0
