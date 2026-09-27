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


async def test_accounts_and_networth(client):
    # 1) A UPI debit creates the AXIS savings account (last4 1234).
    r = await client.post("/ingest/sms", json={
        "sender": "AX-AXISBK",
        "body": "INR 160.00 debited\nA/c no. XX1234\n30-05-26, 16:24:32\nUPI/P2A/435061571506/YADBALINDER SINGH S\nNot you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank",
        "received_at": "2026-05-30T16:24:00",
        "external_id": "a0",
    })
    assert r.status_code == 201
    # 2) An ATM-format SMS carries the available balance (stored for the scan).
    await client.post("/ingest/sms", json={
        "sender": "AX-AXISBK",
        "body": "INR 6000.00 debited from A/c no. XX1234 on +SECTOR 01-06-2026 16:43:09 IST. Avl bal: INR 49550.00. Not you?",
        "received_at": "2026-06-01T10:00:00",
        "external_id": "a1",
    })

    # GET /accounts — at least 1 AXIS bank account.
    r = await client.get("/accounts")
    assert r.status_code == 200
    accounts = r.json()
    axis_acc = next(a for a in accounts if a["bank"] == "AXIS" and a["type"] == "bank")
    account_id = axis_acc["id"]

    # GET /networth — savings component reflects the scanned available balance.
    r = await client.get("/networth")
    assert r.status_code == 200
    body = r.json()
    assert body["net_worth"] == 49550.0
    assert body["components"]["savings"] == 49550.0

    # GET /accounts/{id}/balance-history still responds.
    r = await client.get(f"/accounts/{account_id}/balance-history")
    assert r.status_code == 200
