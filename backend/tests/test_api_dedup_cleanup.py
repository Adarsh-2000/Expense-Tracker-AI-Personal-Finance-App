from datetime import datetime

import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.db import Base, new_session_factory
from app.deps import get_session
from app.entities import Account, Category, RawMessage, Transaction
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
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c, Session
    app.dependency_overrides.clear()
    await engine.dispose()


async def test_admin_dedup_merges_existing_cross_channel_duplicate(client_and_session):
    client, Session = client_and_session
    # Seed two transactions for the SAME account/amount/direction, occurred_at 5h
    # apart, each linked to a raw_message of a DIFFERENT channel (email + sms).
    # These are a cross-channel dup the old ingest logic missed.
    async with Session() as s:
        acc = Account(bank="AXIS", type="card", last4="1234")
        cat = Category(name="food")
        s.add_all([acc, cat])
        await s.flush()
        t_a = Transaction(amount=100.0, direction="debit", merchant="SWIGGY",
                          occurred_at=datetime(2026, 6, 1, 8, 0), account_id=acc.id,
                          category_id=cat.id, fingerprint="fp-a", entry_type="auto")
        t_b = Transaction(amount=100.0, direction="debit", merchant="SWIGGY",
                          occurred_at=datetime(2026, 6, 1, 13, 0), account_id=acc.id,
                          category_id=cat.id, fingerprint="fp-b", entry_type="auto")
        s.add_all([t_a, t_b])
        await s.flush()
        s.add_all([
            RawMessage(channel="email", sender="alerts@axis", body="b",
                       received_at=datetime(2026, 6, 1, 8, 0), parsed_status="parsed",
                       transaction_id=t_a.id),
            RawMessage(channel="sms", sender="AX-AXISBK", body="b",
                       received_at=datetime(2026, 6, 1, 13, 0), parsed_status="parsed",
                       transaction_id=t_b.id),
        ])
        keep_id = t_a.id
        await s.commit()

    resp = await client.post("/admin/dedup")
    assert resp.status_code == 200
    body = resp.json()
    assert body["merged"] == 1

    # Afterward exactly one transaction remains for the group, with BOTH raw_messages.
    from sqlalchemy import select
    async with Session() as s:
        txns = list((await s.execute(select(Transaction))).scalars())
        assert len(txns) == 1
        assert txns[0].id == keep_id
        msgs = list((await s.execute(
            select(RawMessage).where(RawMessage.transaction_id == keep_id)
        )).scalars())
        assert len(msgs) == 2
        assert {m.channel for m in msgs} == {"email", "sms"}
