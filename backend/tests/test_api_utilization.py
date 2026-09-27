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
        yield c, Session
    app.dependency_overrides.clear()
    await engine.dispose()


async def test_set_limit_rejects_non_positive(client):
    c, Session = client
    from app.repositories import AccountRepository
    async with Session() as s:
        acc = await AccountRepository(s).get_or_create(bank="AXIS", type="card", last4="6978")
        await s.commit()
        acc_id = acc.id
    r = await c.put(f"/cards/{acc_id}/limit", json={"credit_limit": -5000.0})
    assert r.status_code == 422


async def test_get_utilization_and_set_limit(client):
    c, Session = client
    from datetime import datetime
    from app.entities import RawMessage
    from app.repositories import AccountRepository

    async with Session() as s:
        acc = await AccountRepository(s).get_or_create(bank="AXIS", type="card", last4="6978")
        s.add_all([
            RawMessage(channel="sms", sender="AXISBK", external_id="m1", parsed_status="parsed",
                       received_at=datetime(2026, 5, 1), body="XX6978 Avl Limit: INR 1000000.00"),
            RawMessage(channel="sms", sender="AXISBK", external_id="m2", parsed_status="parsed",
                       received_at=datetime(2026, 6, 1), body="XX6978 Avl Limit: INR 700000.00"),
        ])
        await s.commit()
        acc_id = acc.id

    r = await c.get("/cards/utilization")
    assert r.status_code == 200
    card = r.json()["cards"][0]
    assert card["utilization_pct"] == 30.0
    assert card["source"] == "observed"

    r2 = await c.put(f"/cards/{acc_id}/limit", json={"credit_limit": 800000.0})
    assert r2.status_code == 200
    r3 = await c.get("/cards/utilization")
    card3 = r3.json()["cards"][0]
    assert card3["source"] == "manual"
    assert card3["total_limit"] == 800000.0


def _find_card(cards, acc_id):
    return next(c for c in cards if c["account_id"] == acc_id)


async def test_set_network_surfaces_on_cards_state(client):
    c, Session = client
    from app.repositories import AccountRepository
    async with Session() as s:
        acc = await AccountRepository(s).get_or_create(bank="AXIS", type="card", last4="6978")
        await s.commit()
        acc_id = acc.id

    # Card starts with no network override.
    r = await c.get("/cards")
    assert r.status_code == 200
    assert _find_card(r.json()["cards"], acc_id)["network"] is None

    # Setting only the network surfaces on the per-card (CardStateDto) response.
    r2 = await c.put(f"/cards/{acc_id}/limit", json={"network": "rupay"})
    assert r2.status_code == 200
    r3 = await c.get("/cards")
    assert _find_card(r3.json()["cards"], acc_id)["network"] == "rupay"


async def test_network_and_limit_are_independent(client):
    c, Session = client
    from app.repositories import AccountRepository
    async with Session() as s:
        acc = await AccountRepository(s).get_or_create(bank="AXIS", type="card", last4="6978")
        await s.commit()
        acc_id = acc.id

    # Set only the limit; network must stay None.
    r = await c.put(f"/cards/{acc_id}/limit", json={"credit_limit": 500000.0})
    assert r.status_code == 200
    async with Session() as s:
        acc = await AccountRepository(s).get(acc_id)
        assert acc.credit_limit == 500000.0
        assert acc.network is None

    # Set only the network; the previously set limit must be untouched.
    r2 = await c.put(f"/cards/{acc_id}/limit", json={"network": "VISA"})
    assert r2.status_code == 200
    async with Session() as s:
        acc = await AccountRepository(s).get(acc_id)
        assert acc.credit_limit == 500000.0  # untouched
        assert acc.network == "visa"  # normalized to lowercase

    # An unrecognized network normalizes to None.
    r3 = await c.put(f"/cards/{acc_id}/limit", json={"network": "amex"})
    assert r3.status_code == 200
    async with Session() as s:
        acc = await AccountRepository(s).get(acc_id)
        assert acc.network is None
        assert acc.credit_limit == 500000.0
