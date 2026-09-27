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


async def test_budget_endpoints(client):
    # Create a SWIGGY transaction to get the "food" category
    resp = await client.post("/transactions/manual", json={
        "merchant": "SWIGGY", "amount": 850, "occurred_at": "2026-06-10T12:00:00",
    })
    assert resp.status_code == 201

    # Find the food category_id
    txns = (await client.get("/transactions")).json()
    food_category_id = next(t["category_id"] for t in txns if t["merchant"] == "SWIGGY")

    # Set income for 2026-06
    r = await client.put("/budget/income", json={"month": "2026-06", "amount": 50000})
    assert r.status_code == 200
    assert r.json()["status"] == "ok"

    # Assign 1000 to food category for 2026-06
    r = await client.put("/budget/assign", json={
        "category_id": food_category_id, "month": "2026-06", "assigned_amount": 1000,
    })
    assert r.status_code == 200
    assert r.json()["status"] == "ok"

    # GET budget for 2026-06
    r = await client.get("/budget/2026-06")
    assert r.status_code == 200
    data = r.json()
    assert data["month"] == "2026-06"
    assert data["ready_to_assign"] == 49000.0

    # Find food entry in categories
    food_entry = next(c for c in data["categories"] if c["category_id"] == food_category_id)
    assert food_entry["spent"] == 850.0
    assert food_entry["remaining"] == 150.0
    assert food_entry["status"] == "warning"
