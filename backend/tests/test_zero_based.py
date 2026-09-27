from datetime import datetime

from app.enrichment.zero_based import ZeroBasedBudgetService
from app.repositories import (
    BudgetAssignmentRepository, CategoryRepository, MonthlyIncomeRepository,
    TransactionRepository, TransactionSplitRepository,
)


def _service(session):
    return ZeroBasedBudgetService(
        income=MonthlyIncomeRepository(session),
        assignments=BudgetAssignmentRepository(session),
        transactions=TransactionRepository(session),
        splits=TransactionSplitRepository(session),
    )


async def test_ready_to_assign_and_category_status(session):
    food = await CategoryRepository(session).get_or_create("food")
    await session.flush()
    await MonthlyIncomeRepository(session).set(month="2026-06", amount=50000.0)
    await BudgetAssignmentRepository(session).set(category_id=food.id, month="2026-06", assigned_amount=1000.0)
    await TransactionRepository(session).create(
        amount=850.0, direction="debit", merchant="SWIGGY",
        occurred_at=datetime(2026, 6, 10, 12, 0), account_id=1,
        fingerprint="x", category_id=food.id,
    )
    await session.flush()

    svc = _service(session)
    assert await svc.ready_to_assign("2026-06") == 49000.0
    statuses = {s.category_id: s for s in await svc.category_status("2026-06")}
    assert statuses[food.id].spent == 850.0
    assert statuses[food.id].remaining == 150.0
    assert statuses[food.id].status == "warning"


async def test_spent_uses_splits_when_present(session):
    cats = CategoryRepository(session)
    food = await cats.get_or_create("food")
    travel = await cats.get_or_create("travel")
    await session.flush()
    await MonthlyIncomeRepository(session).set(month="2026-06", amount=10000.0)
    await BudgetAssignmentRepository(session).set(category_id=food.id, month="2026-06", assigned_amount=1000.0)
    await BudgetAssignmentRepository(session).set(category_id=travel.id, month="2026-06", assigned_amount=1000.0)
    txn = await TransactionRepository(session).create(
        amount=500.0, direction="debit", merchant="MIX",
        occurred_at=datetime(2026, 6, 5, 12, 0), account_id=1,
        fingerprint="y", category_id=food.id,
    )
    await session.flush()
    splits = TransactionSplitRepository(session)
    await splits.add(transaction_id=txn.id, category_id=food.id, amount=300.0)
    await splits.add(transaction_id=txn.id, category_id=travel.id, amount=200.0)
    await session.flush()

    svc = _service(session)
    statuses = {s.category_id: s for s in await svc.category_status("2026-06")}
    assert statuses[food.id].spent == 300.0    # from split, not the 500 parent
    assert statuses[travel.id].spent == 200.0
