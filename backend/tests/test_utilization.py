import pytest_asyncio

import app.entities  # noqa: F401 — registers all ORM models with Base.metadata
from app.db import Base, new_session_factory


@pytest_asyncio.fixture
async def session_maker():
    engine, Session = new_session_factory("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield Session
    await engine.dispose()


async def test_set_credit_limit(session_maker):
    from app.repositories import AccountRepository

    async with session_maker() as s:
        repo = AccountRepository(s)
        acc = await repo.get_or_create(bank="AXIS", type="card", last4="6978")
        await s.commit()
        updated = await repo.set_credit_limit(acc.id, credit_limit=1000000.0)
        await s.commit()
        assert updated is not None
        assert updated.credit_limit == 1000000.0


async def test_limit_scan_latest_and_max(session_maker):
    from datetime import datetime
    from app.entities import RawMessage
    from app.enrichment.limit_scan import latest_avl_limit_for, max_avl_limit_for

    async with session_maker() as s:
        s.add_all([
            RawMessage(channel="sms", sender="AXISBK", external_id="a1", parsed_status="parsed",
                       received_at=datetime(2026, 5, 1),
                       body="Spent INR 5000 Axis Bank Card no. XX6978 Avl Limit: INR 980000.00"),
            RawMessage(channel="sms", sender="AXISBK", external_id="a2", parsed_status="parsed",
                       received_at=datetime(2026, 6, 1),
                       body="Spent INR 9000 Axis Bank Card no. XX6978 Avl Limit: INR 760000.50"),
        ])
        await s.commit()
        latest = await latest_avl_limit_for(s, last4="6978")
        assert latest is not None and latest[0] == 760000.50
        assert await max_avl_limit_for(s, last4="6978") == 980000.00
        assert await latest_avl_limit_for(s, last4="0000") is None


async def test_card_utilization(session_maker):
    from datetime import datetime
    from app.entities import RawMessage
    from app.repositories import AccountRepository
    from app.services.utilization import card_utilization

    async with session_maker() as s:
        repo = AccountRepository(s)
        a1 = await repo.get_or_create(bank="AXIS", type="card", last4="6978")
        a2 = await repo.get_or_create(bank="IDFC", type="card", last4="0684")
        s.add_all([
            RawMessage(channel="sms", sender="AXISBK", external_id="m1", parsed_status="parsed",
                       received_at=datetime(2026, 5, 1), body="XX6978 Avl Limit: INR 1000000.00"),
            RawMessage(channel="sms", sender="AXISBK", external_id="m2", parsed_status="parsed",
                       received_at=datetime(2026, 6, 1), body="XX6978 Avl Limit: INR 750000.00"),
        ])
        await s.commit()
        cards = await card_utilization(s)
        by = {c["last4"]: c for c in cards}
        axis = by["6978"]
        assert axis["source"] == "observed"
        assert axis["total_limit"] == 1000000.00
        assert axis["available"] == 750000.00
        assert axis["used"] == 250000.00
        assert axis["utilization_pct"] == 25.0
        assert by["0684"]["source"] == "unknown"


async def test_card_utilization_override(session_maker):
    from datetime import datetime
    from app.entities import RawMessage
    from app.repositories import AccountRepository
    from app.services.utilization import card_utilization

    async with session_maker() as s:
        repo = AccountRepository(s)
        a = await repo.get_or_create(bank="AXIS", type="card", last4="6978")
        await repo.set_credit_limit(a.id, credit_limit=800000.0)
        s.add(RawMessage(channel="sms", sender="AXISBK", external_id="m1", parsed_status="parsed",
                         received_at=datetime(2026, 6, 1), body="XX6978 Avl Limit: INR 600000.00"))
        await s.commit()
        axis = {c["last4"]: c for c in await card_utilization(s)}["6978"]
        assert axis["source"] == "manual"
        assert axis["total_limit"] == 800000.0
        assert axis["used"] == 200000.0
        assert axis["utilization_pct"] == 25.0


async def test_high_utilization_uses_newest_limit(session_maker):
    from datetime import datetime
    from app.entities import RawMessage
    from app.repositories import AccountRepository, RawMessageRepository
    from app.enrichment.money_radar import detect_high_utilization

    async with session_maker() as s:
        repo = AccountRepository(s)
        await repo.get_or_create(bank="AXIS", type="card", last4="6978")
        # Insert OLDEST first (high available => low util), NEWEST second (low available => high util).
        s.add(RawMessage(channel="sms", sender="AXISBK", external_id="o1", parsed_status="parsed",
                         received_at=datetime(2026, 5, 1), body="XX6978 Avl Limit: INR 950000.00"))
        s.add(RawMessage(channel="sms", sender="AXISBK", external_id="o2", parsed_status="parsed",
                         received_at=datetime(2026, 6, 1), body="XX6978 Avl Limit: INR 300000.00"))
        await s.commit()
        accounts = await repo.list_all()
        msgs = await RawMessageRepository(s).list_by_status("parsed")
        drafts = detect_high_utilization(msgs, accounts, datetime(2026, 6, 2))
        # newest available=300k, max=950k => used 650k of 950k => ~68% => action
        assert any(d.type == "utilization" and d.severity == "action" for d in drafts)


async def test_high_utilization_detector(session_maker):
    from datetime import datetime
    from app.entities import RawMessage
    from app.repositories import AccountRepository, RawMessageRepository
    from app.enrichment.money_radar import detect_high_utilization

    async with session_maker() as s:
        repo = AccountRepository(s)
        a = await repo.get_or_create(bank="AXIS", type="card", last4="6978")
        await repo.set_credit_limit(a.id, credit_limit=1000000.0)
        s.add(RawMessage(channel="sms", sender="AXISBK", external_id="m1", parsed_status="parsed",
                         received_at=datetime(2026, 6, 1), body="XX6978 Avl Limit: INR 400000.00"))
        await s.commit()
        accounts = await repo.list_all()
        msgs = await RawMessageRepository(s).list_by_status("parsed")
        drafts = detect_high_utilization(msgs, accounts, datetime(2026, 6, 2))
        assert any(d.type == "utilization" and d.severity == "action" for d in drafts)


async def test_utilization_bill_derived_for_card_without_avl_limit(session_maker):
    """A card with a manual limit but no Avl Limit SMS derives utilization from its
    outstanding (unbilled debit transactions)."""
    from datetime import datetime
    from app.entities import Transaction
    from app.repositories import AccountRepository
    from app.services.utilization import card_utilization

    async with session_maker() as s:
        repo = AccountRepository(s)
        a = await repo.get_or_create(bank="IDFC", type="card", last4="2198")
        await repo.set_credit_limit(a.id, credit_limit=100000.0)
        # Recent debit so it counts as unbilled outstanding; no Avl Limit message.
        s.add(Transaction(account_id=a.id, direction="debit", amount=17630.0,
                          merchant="TEST", occurred_at=datetime.now(),
                          fingerprint="util-test-fp-1"))
        await s.commit()
        card = {c["last4"]: c for c in await card_utilization(s)}["2198"]
        assert card["source"] == "manual"
        assert card["total_limit"] == 100000.0
        assert card["used"] == 17630.0
        assert card["utilization_pct"] == 17.6
