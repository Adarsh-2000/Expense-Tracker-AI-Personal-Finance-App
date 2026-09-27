from datetime import datetime

from app.entities import Account
from app.enrichment.balances import BalanceService
from app.repositories import AccountRepository, BalanceSnapshotRepository


async def _service(session):
    return BalanceService(
        accounts=AccountRepository(session),
        snapshots=BalanceSnapshotRepository(session),
    )


async def test_apply_updates_balance_and_writes_snapshot(session):
    acc = await AccountRepository(session).get_or_create(bank="AXIS", type="bank", last4="5678")
    await session.flush()
    svc = await _service(session)
    await svc.apply(acc, 10000.0, datetime(2026, 6, 1, 10, 0))
    await session.flush()
    assert acc.current_balance == 10000.0
    snap = await BalanceSnapshotRepository(session).latest_for(acc.id)
    assert snap is not None and snap.balance == 10000.0


async def test_apply_none_is_noop(session):
    acc = await AccountRepository(session).get_or_create(bank="AXIS", type="bank", last4="5678")
    await session.flush()
    svc = await _service(session)
    await svc.apply(acc, None, datetime(2026, 6, 1, 10, 0))
    await session.flush()
    assert acc.current_balance is None
    assert await BalanceSnapshotRepository(session).latest_for(acc.id) is None


async def test_net_worth_sums_accounts(session):
    repo = AccountRepository(session)
    a1 = await repo.get_or_create(bank="AXIS", type="bank", last4="1111")
    a2 = await repo.get_or_create(bank="BOB", type="bank", last4="2222")
    await session.flush()
    a1.current_balance = 10000.0
    a2.current_balance = 5000.0
    await session.flush()
    svc = await _service(session)
    assert await svc.net_worth() == 15000.0
