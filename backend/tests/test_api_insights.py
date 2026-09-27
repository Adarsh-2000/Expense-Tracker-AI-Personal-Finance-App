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
    # May: two SWIGGY (repeated merchant) + one AMAZON
    await client.post("/transactions/manual", json={
        "amount": 100, "merchant": "SWIGGY", "occurred_at": "2026-05-05T09:00:00"})
    await client.post("/transactions/manual", json={
        "amount": 200, "merchant": "SWIGGY", "occurred_at": "2026-05-12T09:00:00"})
    await client.post("/transactions/manual", json={
        "amount": 50, "merchant": "AMAZON", "occurred_at": "2026-05-20T09:00:00"})
    # June: one SWIGGY
    await client.post("/transactions/manual", json={
        "amount": 80, "merchant": "SWIGGY", "occurred_at": "2026-06-03T09:00:00"})


async def test_trend_has_both_months_ascending(client):
    await _seed(client)
    resp = await client.get("/reports/trend")
    assert resp.status_code == 200
    series = resp.json()["series"]
    months = [row["month"] for row in series]
    assert months == ["2026-05", "2026-06"]
    by_month = {row["month"]: row["spent"] for row in series}
    assert by_month["2026-05"] == 350.0
    assert by_month["2026-06"] == 80.0


async def test_top_merchants_repeated_first(client):
    await _seed(client)
    resp = await client.get("/reports/top-merchants", params={"month": "2026-05"})
    assert resp.status_code == 200
    merchants = resp.json()["merchants"]
    assert merchants[0]["merchant"] == "SWIGGY"
    assert merchants[0]["total"] == 300.0
    assert merchants[0]["count"] == 2


async def test_top_merchants_all_time(client):
    await _seed(client)
    resp = await client.get("/reports/top-merchants")
    assert resp.status_code == 200
    merchants = resp.json()["merchants"]
    swiggy = next(m for m in merchants if m["merchant"] == "SWIGGY")
    assert swiggy["count"] == 3
    assert swiggy["total"] == 380.0


async def test_daily_per_day_rows(client):
    await _seed(client)
    resp = await client.get("/reports/daily", params={"month": "2026-05"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["month"] == "2026-05"
    days = {row["date"]: row["spent"] for row in body["days"]}
    assert days["2026-05-05"] == 100.0
    assert days["2026-05-12"] == 200.0
    assert days["2026-05-20"] == 50.0
