from datetime import datetime

from app.entities import Account, RawMessage, Transaction


async def test_can_persist_account_message_transaction(session):
    account = Account(bank="AXIS", type="card", last4="1234", currency="INR")
    session.add(account)
    await session.flush()

    txn = Transaction(
        amount=100.0,
        direction="debit",
        merchant="SWIGGY",
        occurred_at=datetime(2026, 6, 1, 10, 0),
        account_id=account.id,
        fingerprint="1234|100.0|debit|swiggy",
    )
    session.add(txn)
    await session.flush()

    msg = RawMessage(
        channel="sms", sender="AX-AXISBK", body="spent",
        received_at=datetime(2026, 6, 1, 10, 0), transaction=txn,
    )
    session.add(msg)
    await session.flush()

    # Refresh to load the relationship via async-safe eager load
    await session.refresh(txn, attribute_names=["sources"])

    assert account.id is not None
    assert msg.id is not None
    assert txn.account_id == account.id
    assert msg.transaction_id == txn.id
    assert [s.id for s in txn.sources] == [msg.id]
