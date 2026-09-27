from datetime import datetime

from app.entities import RawMessage
from app.repositories import AccountRepository, RawMessageRepository, TransactionRepository


async def test_account_get_or_create_is_idempotent(session):
    repo = AccountRepository(session)
    a1 = await repo.get_or_create(bank="AXIS", type="card", last4="1234")
    a2 = await repo.get_or_create(bank="AXIS", type="card", last4="1234")
    await session.flush()
    assert a1.id == a2.id


async def test_raw_message_external_id_dedup(session):
    repo = RawMessageRepository(session)
    m1 = RawMessage(channel="email", sender="x", body="b", received_at=datetime(2026, 6, 1), external_id="gmail-1")
    created = await repo.add_if_new(m1)
    assert created is not None
    dup = RawMessage(channel="email", sender="x", body="b", received_at=datetime(2026, 6, 1), external_id="gmail-1")
    again = await repo.add_if_new(dup)
    assert again is None


async def test_transaction_find_recent_by_fingerprint(session):
    acc = await AccountRepository(session).get_or_create(bank="AXIS", type="card", last4="1234")
    await session.flush()
    repo = TransactionRepository(session)
    txn = await repo.create(
        amount=100.0, direction="debit", merchant="SWIGGY",
        occurred_at=datetime(2026, 6, 1, 10, 0), account_id=acc.id,
        fingerprint="1234|100.0|debit|swiggy",
    )
    await session.flush()
    found = await repo.find_recent_by_fingerprint(
        "1234|100.0|debit|swiggy", before=datetime(2026, 6, 1, 20, 0), window_hours=36
    )
    assert found is not None and found.id == txn.id
