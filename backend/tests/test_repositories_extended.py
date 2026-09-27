from datetime import datetime

import pytest

from app.entities import Account, Category, Transaction
from app.repositories import (
    BalanceSnapshotRepository,
    BudgetAssignmentRepository,
    MonthlyIncomeRepository,
    TransactionSplitRepository,
)


async def _make_account(session) -> Account:
    acc = Account(bank="CASH", type="cash", last4="0000", currency="INR")
    session.add(acc)
    await session.flush()
    return acc


async def _make_transaction(session, account_id: int) -> Transaction:
    txn = Transaction(
        amount=100.0, direction="debit", merchant="TEST", occurred_at=datetime(2026, 6, 1),
        account_id=account_id, fingerprint="test|100|debit|auto",
    )
    session.add(txn)
    await session.flush()
    return txn


@pytest.mark.asyncio
async def test_transaction_split_add_and_list(session):
    acc = await _make_account(session)
    txn = await _make_transaction(session, acc.id)

    repo = TransactionSplitRepository(session)
    split = await repo.add(transaction_id=txn.id, category_id=None, amount=100.0)
    await session.flush()

    splits = await repo.list_for(txn.id)
    assert len(splits) == 1
    assert splits[0].amount == 100.0
    assert splits[0].transaction_id == txn.id
    assert splits[0].category_id is None


@pytest.mark.asyncio
async def test_transaction_split_delete_for(session):
    acc = await _make_account(session)
    txn = await _make_transaction(session, acc.id)

    repo = TransactionSplitRepository(session)
    await repo.add(transaction_id=txn.id, category_id=None, amount=60.0)
    await repo.add(transaction_id=txn.id, category_id=None, amount=40.0)
    await session.flush()

    await repo.delete_for(txn.id)
    await session.flush()

    splits = await repo.list_for(txn.id)
    assert splits == []


@pytest.mark.asyncio
async def test_balance_snapshot_latest_for_returns_most_recent(session):
    acc = await _make_account(session)

    repo = BalanceSnapshotRepository(session)
    await repo.add(account_id=acc.id, balance=1000.0, captured_at=datetime(2026, 5, 1))
    await repo.add(account_id=acc.id, balance=1500.0, captured_at=datetime(2026, 6, 1))
    await repo.add(account_id=acc.id, balance=1200.0, captured_at=datetime(2026, 5, 15))
    await session.flush()

    latest = await repo.latest_for(acc.id)
    assert latest is not None
    assert latest.balance == 1500.0
    assert latest.captured_at == datetime(2026, 6, 1)


@pytest.mark.asyncio
async def test_balance_snapshot_history_ordered_ascending(session):
    acc = await _make_account(session)

    repo = BalanceSnapshotRepository(session)
    await repo.add(account_id=acc.id, balance=200.0, captured_at=datetime(2026, 6, 3))
    await repo.add(account_id=acc.id, balance=100.0, captured_at=datetime(2026, 6, 1))
    await repo.add(account_id=acc.id, balance=150.0, captured_at=datetime(2026, 6, 2))
    await session.flush()

    history = await repo.history(acc.id)
    assert [s.balance for s in history] == [100.0, 150.0, 200.0]


@pytest.mark.asyncio
async def test_budget_assignment_set_upserts(session):
    repo = BudgetAssignmentRepository(session)

    # First call creates
    a1 = await repo.set(category_id=1, month="2026-06", assigned_amount=1000.0)
    await session.flush()

    # Second call with same category+month updates, not duplicates
    a2 = await repo.set(category_id=1, month="2026-06", assigned_amount=1500.0)
    await session.flush()

    assignments = await repo.list_for_month("2026-06")
    assert len(assignments) == 1
    assert assignments[0].assigned_amount == 1500.0


@pytest.mark.asyncio
async def test_budget_assignment_total_assigned_sums_categories(session):
    repo = BudgetAssignmentRepository(session)

    await repo.set(category_id=1, month="2026-06", assigned_amount=1000.0)
    await repo.set(category_id=2, month="2026-06", assigned_amount=500.0)
    await session.flush()

    total = await repo.total_assigned("2026-06")
    assert total == 1500.0


@pytest.mark.asyncio
async def test_budget_assignment_total_assigned_empty_month(session):
    repo = BudgetAssignmentRepository(session)
    total = await repo.total_assigned("2025-01")
    assert total == 0.0


@pytest.mark.asyncio
async def test_monthly_income_set_upserts_and_get(session):
    repo = MonthlyIncomeRepository(session)

    # Create
    await repo.set(month="2026-06", amount=50000.0)
    await session.flush()

    # Upsert
    await repo.set(month="2026-06", amount=60000.0)
    await session.flush()

    income = await repo.get("2026-06")
    assert income is not None
    assert income.amount == 60000.0


@pytest.mark.asyncio
async def test_monthly_income_get_returns_none_when_missing(session):
    repo = MonthlyIncomeRepository(session)
    income = await repo.get("2025-01")
    assert income is None
