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


async def test_recategorize_returns_counts_and_is_idempotent(client):
    create = await client.post("/transactions/manual", json={
        "amount": 250, "merchant": "ZOMATO", "occurred_at": "2025-04-05T09:00:00",
    })
    assert create.status_code == 201

    resp = await client.post("/admin/recategorize")
    assert resp.status_code == 200
    body = resp.json()
    assert "recategorized" in body
    assert "total" in body
    assert body["total"] >= 1

    # ZOMATO should be food_dining under the 16-cat rules; verify via summary
    summary = await client.get("/reports/summary")
    totals = {c["category"]: c["total"] for c in summary.json()}
    assert totals.get("food_dining") == 250.0
