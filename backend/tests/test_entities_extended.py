from datetime import datetime

import pytest

from app.entities import (
    Account, BalanceSnapshot, BudgetAssignment, MonthlyIncome,
    Transaction, TransactionSplit,
)


@pytest.mark.asyncio
async def test_new_columns_and_entities_persist(session):
    acc = Account(bank="CASH", type="cash", last4="0000", currency="INR", current_balance=500.0)
    session.add(acc)
    await session.flush()

    txn = Transaction(
        amount=300.0, direction="debit", merchant="MANUAL", occurred_at=datetime(2026, 6, 1),
        account_id=acc.id, fingerprint="0000|300.0|debit|manual",
        entry_type="manual", note="lunch", balance_after=200.0,
    )
    session.add(txn)
    await session.flush()

    session.add_all([
        TransactionSplit(transaction_id=txn.id, category_id=None, amount=300.0),
        BalanceSnapshot(account_id=acc.id, balance=200.0, captured_at=datetime(2026, 6, 1)),
        BudgetAssignment(category_id=1, month="2026-06", assigned_amount=1000.0),
        MonthlyIncome(month="2026-06", amount=50000.0),
    ])
    await session.flush()
    assert txn.entry_type == "manual" and txn.balance_after == 200.0
    assert acc.current_balance == 500.0
