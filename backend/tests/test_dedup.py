from datetime import datetime

from app.dedup import DedupEngine
from app.enrichment.balances import BalanceService
from app.enrichment.categorizer import RuleCategorizer
from app.enrichment.default_rules import DEFAULT_RULES
from app.entities import RawMessage
from app.parsing.types import ParsedTransaction
from app.repositories import (
    AccountRepository, BalanceSnapshotRepository, CategoryRepository, TransactionRepository,
)


def _pt(amount=100.0, when=datetime(2026, 6, 1, 10, 0), merchant="SWIGGY", balance_after=None):
    return ParsedTransaction(
        bank="AXIS", account_type="card", last4="1234", amount=amount,
        direction="debit", merchant=merchant, occurred_at=when, balance_after=balance_after,
    )


def _engine(session, amount_window_hours=12):
    return DedupEngine(
        accounts=AccountRepository(session),
        transactions=TransactionRepository(session),
        categories=CategoryRepository(session),
        categorizer=RuleCategorizer(DEFAULT_RULES),
        balances=BalanceService(
            accounts=AccountRepository(session), snapshots=BalanceSnapshotRepository(session)
        ),
        window_hours=36,
        amount_window_hours=amount_window_hours,
    )


async def test_txn_time_preferred_over_received_at_for_occurred_at(session):
    # When the parser extracts a precise body datetime (txn_time), it must be
    # used as the transaction's occurred_at in preference to the message
    # received_at carried on the ParsedTransaction.
    engine = _engine(session)
    received_at = datetime(2026, 6, 1, 9, 0)
    body_time = datetime(2026, 5, 6, 18, 47, 40)
    sms = RawMessage(channel="sms", sender="AX-AXISBK", body="b", received_at=received_at)
    session.add(sms)
    await session.flush()
    pt = ParsedTransaction(
        bank="AXIS", account_type="bank", last4="4400", amount=818.60,
        direction="debit", merchant="IRCTC Web UPI", occurred_at=received_at,
        txn_ref="758363491265", txn_time=body_time,
    )
    txn = await engine.ingest(pt, sms)
    await session.flush()
    assert txn.occurred_at == body_time
    assert txn.txn_ref == "758363491265"


async def test_txn_ref_persisted_and_received_at_used_when_no_txn_time(session):
    engine = _engine(session)
    received_at = datetime(2026, 6, 1, 9, 0)
    sms = RawMessage(channel="sms", sender="AX-AXISBK", body="b", received_at=received_at)
    session.add(sms)
    await session.flush()
    pt = ParsedTransaction(
        bank="AXIS", account_type="card", last4="1234", amount=100.0,
        direction="debit", merchant="SWIGGY", occurred_at=received_at, txn_ref="REF123",
    )
    txn = await engine.ingest(pt, sms)
    await session.flush()
    assert txn.occurred_at == received_at
    assert txn.txn_ref == "REF123"


async def test_cross_channel_duplicate_collapses(session):
    engine = _engine(session)
    sms = RawMessage(channel="sms", sender="AX-AXISBK", body="b", received_at=datetime(2026, 6, 1, 10, 0))
    email = RawMessage(channel="email", sender="alerts@axis", body="b", received_at=datetime(2026, 6, 1, 10, 30))
    session.add_all([sms, email])
    await session.flush()
    t1 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 10, 0)), sms)
    t2 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 10, 30)), email)
    await session.flush()
    assert t1.id == t2.id


async def test_same_channel_repeat_is_new_transaction(session):
    engine = _engine(session)
    sms1 = RawMessage(channel="sms", sender="AX-AXISBK", body="b", received_at=datetime(2026, 6, 1, 9, 0))
    sms2 = RawMessage(channel="sms", sender="AX-AXISBK", body="b", received_at=datetime(2026, 6, 1, 9, 5))
    session.add_all([sms1, sms2])
    await session.flush()
    t1 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 9, 0)), sms1)
    t2 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 9, 5)), sms2)
    await session.flush()
    assert t1.id != t2.id


async def test_outside_window_is_new_transaction(session):
    engine = _engine(session)
    sms = RawMessage(channel="sms", sender="AX-AXISBK", body="b", received_at=datetime(2026, 6, 1, 10, 0))
    email = RawMessage(channel="email", sender="alerts@axis", body="b", received_at=datetime(2026, 6, 5, 10, 0))
    session.add_all([sms, email])
    await session.flush()
    t1 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 10, 0)), sms)
    t2 = await engine.ingest(_pt(when=datetime(2026, 6, 5, 10, 0)), email)
    await session.flush()
    assert t1.id != t2.id


async def test_tier2_cross_channel_different_merchant_collapses(session):
    # Same account+amount+direction, DIFFERENT merchant names, different channels,
    # within the tight amount-window -> collapse (catches SMS "Zomato Ltd" +
    # email "ETERNAL LIMITED" for the same purchase).
    engine = _engine(session)
    sms = RawMessage(channel="sms", sender="AX-AXISBK", body="b", received_at=datetime(2026, 6, 1, 10, 0))
    email = RawMessage(channel="email", sender="alerts@axis", body="b", received_at=datetime(2026, 6, 1, 12, 0))
    session.add_all([sms, email])
    await session.flush()
    t1 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 10, 0), merchant="Zomato Ltd"), sms)
    t2 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 12, 0), merchant="ETERNAL LIMITED"), email)
    await session.flush()
    assert t1.id == t2.id


async def test_tier2_same_channel_different_merchant_is_new(session):
    # Same account+amount+direction, different merchant, SAME channel within the
    # amount-window -> genuine repeat, must NOT be merged.
    engine = _engine(session)
    sms1 = RawMessage(channel="sms", sender="AX-AXISBK", body="b", received_at=datetime(2026, 6, 1, 10, 0))
    sms2 = RawMessage(channel="sms", sender="AX-AXISBK", body="b", received_at=datetime(2026, 6, 1, 12, 0))
    session.add_all([sms1, sms2])
    await session.flush()
    t1 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 10, 0), merchant="Zomato Ltd"), sms1)
    t2 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 12, 0), merchant="ETERNAL LIMITED"), sms2)
    await session.flush()
    assert t1.id != t2.id


async def test_tier2_outside_amount_window_is_new(session):
    # Same account+amount, different merchant, different channel but OUTSIDE the
    # 12h amount-window (20h apart) -> not merged by tier 2.
    engine = _engine(session)
    sms = RawMessage(channel="sms", sender="AX-AXISBK", body="b", received_at=datetime(2026, 6, 1, 10, 0))
    email = RawMessage(channel="email", sender="alerts@axis", body="b", received_at=datetime(2026, 6, 2, 6, 0))
    session.add_all([sms, email])
    await session.flush()
    t1 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 10, 0), merchant="Zomato Ltd"), sms)
    t2 = await engine.ingest(_pt(when=datetime(2026, 6, 2, 6, 0), merchant="ETERNAL LIMITED"), email)
    await session.flush()
    assert t1.id != t2.id


async def test_forward_cross_channel_collapses(session):
    # The case that used to fail: an email arrives FIRST at time T (already stored),
    # then the matching SMS arrives reporting an EARLIER local time T-6h. A
    # backward-only window from T-6h would never look forward to T and would create
    # a duplicate. A symmetric window centered on the incoming occurred_at collapses
    # them. Same account+amount+direction, different channels.
    engine = _engine(session)
    email = RawMessage(channel="email", sender="alerts@axis", body="b", received_at=datetime(2026, 6, 1, 12, 0))
    sms = RawMessage(channel="sms", sender="AX-AXISBK", body="b", received_at=datetime(2026, 6, 1, 6, 0))
    session.add_all([email, sms])
    await session.flush()
    t1 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 12, 0)), email)
    t2 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 6, 0)), sms)
    await session.flush()
    assert t1.id == t2.id


async def test_forward_cross_channel_different_merchant_collapses(session):
    # Forward case via tier-2 (amount match): email first at T, SMS later ingested
    # with EARLIER time T-6h and a different merchant name -> still collapse.
    engine = _engine(session)
    email = RawMessage(channel="email", sender="alerts@axis", body="b", received_at=datetime(2026, 6, 1, 12, 0))
    sms = RawMessage(channel="sms", sender="AX-AXISBK", body="b", received_at=datetime(2026, 6, 1, 6, 0))
    session.add_all([email, sms])
    await session.flush()
    t1 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 12, 0), merchant="ETERNAL LIMITED"), email)
    t2 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 6, 0), merchant="Zomato Ltd"), sms)
    await session.flush()
    assert t1.id == t2.id


async def test_different_channel_20h_apart_stays_separate(session):
    # Two different-channel txns 20h apart: outside the 12h amount-window and (with
    # different merchants) outside fingerprint match -> stay separate even with a
    # symmetric window.
    engine = _engine(session)
    sms = RawMessage(channel="sms", sender="AX-AXISBK", body="b", received_at=datetime(2026, 6, 1, 10, 0))
    email = RawMessage(channel="email", sender="alerts@axis", body="b", received_at=datetime(2026, 6, 2, 6, 0))
    session.add_all([sms, email])
    await session.flush()
    t1 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 10, 0), merchant="Zomato Ltd"), sms)
    t2 = await engine.ingest(_pt(when=datetime(2026, 6, 2, 6, 0), merchant="ETERNAL LIMITED"), email)
    await session.flush()
    assert t1.id != t2.id


async def test_new_transaction_is_categorized_and_balance_applied(session):
    engine = _engine(session)
    sms = RawMessage(channel="sms", sender="AX-AXISBK", body="b", received_at=datetime(2026, 6, 1, 10, 0))
    session.add(sms)
    await session.flush()
    txn = await engine.ingest(_pt(balance_after=9550.0), sms)
    await session.flush()
    food = await CategoryRepository(session).get_or_create("food")
    assert txn.category_id == food.id
    assert txn.balance_after == 9550.0
    acc = await AccountRepository(session).get_or_create(bank="AXIS", type="card", last4="1234")
    assert acc.current_balance == 9550.0
