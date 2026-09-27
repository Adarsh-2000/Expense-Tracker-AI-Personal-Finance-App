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


async def test_post_cash_expense_creates_manual_transaction(client):
    resp = await client.post("/transactions/manual", json={
        "amount": 120,
        "merchant": "Tea Stall",
        "occurred_at": "2026-06-03T09:00:00",
    })
    assert resp.status_code == 201
    data = resp.json()
    assert data["entry_type"] == "manual"
    assert data["amount"] == 120.0
    assert data["merchant"] == "Tea Stall"


async def test_manual_transaction_appears_in_list(client):
    await client.post("/transactions/manual", json={
        "amount": 120,
        "merchant": "Tea Stall",
        "occurred_at": "2026-06-03T09:00:00",
    })
    resp = await client.get("/transactions")
    assert resp.status_code == 200
    txns = resp.json()
    assert len(txns) == 1
    assert txns[0]["entry_type"] == "manual"
