import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.db import Base, new_session_factory
from app.deps import get_session
from app.entities import Account, RawMessage
from app.main import app


@pytest_asyncio.fixture
async def client_and_session():
    engine, Session = new_session_factory("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async def override():
        async with Session() as s:
            yield s

    app.dependency_overrides[get_session] = override
    transport = ASGITransport(app=app)
    client = AsyncClient(transport=transport, base_url="http://test")
    yield client, Session
    await client.aclose()
    app.dependency_overrides.clear()
    await engine.dispose()


async def _seed(Session):
    from datetime import datetime
    async with Session() as s:
        s.add(Account(bank="AXIS", type="bank", last4="4400"))
        s.add(Account(bank="BOB", type="bank", last4="2404"))
        # Newest BOB balance via SMS
        s.add(RawMessage(
            channel="sms", sender="BOBSMS",
            body="Rs.12 Dr. from A/C XXXXXX2404. AvlBal:Rs496386.50(2026:04:04) - Bank of Baroda",
            received_at=datetime(2026, 4, 4, 16, 46), external_id="b1", parsed_status="parsed"))
        await s.commit()


async def test_savings_lists_accounts_with_scanned_balance(client_and_session):
    c, Session = client_and_session
    await _seed(Session)
    body = (await c.get("/savings")).json()
    bob = next(a for a in body["accounts"] if a["last4"] == "2404")
    assert bob["balance"] == 496386.50
    assert bob["as_of"] == "2026-04-04"
    assert bob["source"] == "sms"
    assert body["savings_total"] >= 496386.50


async def test_manual_balance_overrides_when_newer(client_and_session):
    c, Session = client_and_session
    await _seed(Session)
    acc_id = next(a for a in (await c.get("/savings")).json()["accounts"] if a["last4"] == "2404")["id"]
    # Manual entry dated after the SMS -> wins.
    await c.put(f"/accounts/{acc_id}/balance", json={"balance": 500000.0, "as_of": "2026-06-03"})
    bob = next(a for a in (await c.get("/savings")).json()["accounts"] if a["last4"] == "2404")
    assert bob["balance"] == 500000.0
    assert bob["source"] == "manual"
    assert bob["as_of"] == "2026-06-03"


async def test_fixed_deposit_crud_and_totals(client_and_session):
    c, Session = client_and_session
    await _seed(Session)
    r = await c.post("/fixed-deposits", json={
        "bank": "HDFC", "principal": 200000.0, "interest_rate": 7.1,
        "maturity_date": "2027-01-01", "maturity_amount": 214200.0})
    fd_id = r.json()["id"]

    body = (await c.get("/savings")).json()
    assert body["fd_total"] == 200000.0
    assert body["liquid_total"] == round(body["savings_total"] + 200000.0, 2)
    assert any(f["id"] == fd_id and f["bank"] == "HDFC" for f in body["fixed_deposits"])

    await c.delete(f"/fixed-deposits/{fd_id}")
    assert (await c.get("/savings")).json()["fd_total"] == 0.0
