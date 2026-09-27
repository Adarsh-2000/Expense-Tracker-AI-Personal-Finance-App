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


async def _seed_txn(client) -> int:
    """Seed one transaction via SMS ingest and return its id."""
    await client.post("/ingest/sms", json={
        "sender": "AX-AXISBK",
        "body": "INR 450 spent on AXIS BANK Credit Card no. XX1234 at SWIGGY on 01-06-2026.",
        "received_at": "2026-06-01T10:00:00",
        "external_id": "e1",
    })
    resp = await client.get("/transactions")
    return resp.json()[0]["id"]


async def test_patch_category_id_updates_transaction(client):
    txn_id = await _seed_txn(client)
    resp = await client.patch(f"/transactions/{txn_id}", json={"category_id": 99})
    assert resp.status_code == 200
    data = resp.json()
    assert data["category_id"] == 99


async def test_patch_nonexistent_transaction_returns_404(client):
    resp = await client.patch("/transactions/9999", json={"category_id": 1})
    assert resp.status_code == 404


async def test_put_splits_valid_sum(client):
    txn_id = await _seed_txn(client)
    resp = await client.put(f"/transactions/{txn_id}/splits", json={
        "splits": [
            {"category_id": 1, "amount": 300},
            {"category_id": 2, "amount": 150},
        ]
    })
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert data["count"] == 2


async def test_put_splits_invalid_sum_returns_400(client):
    txn_id = await _seed_txn(client)
    resp = await client.put(f"/transactions/{txn_id}/splits", json={
        "splits": [
            {"category_id": 1, "amount": 999},
        ]
    })
    assert resp.status_code == 400
