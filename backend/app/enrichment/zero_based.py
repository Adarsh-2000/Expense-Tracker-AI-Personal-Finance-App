from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from app.enrichment.budgets import evaluate_budget
from app.repositories import (
    BudgetAssignmentRepository, MonthlyIncomeRepository,
    TransactionRepository, TransactionSplitRepository,
)


def _month_range(month: str) -> tuple[datetime, datetime]:
    year, mon = int(month[:4]), int(month[5:7])
    start = datetime(year, mon, 1)
    end = datetime(year + 1, 1, 1) if mon == 12 else datetime(year, mon + 1, 1)
    return start, end


@dataclass
class CategoryStatus:
    category_id: int
    category: str
    assigned: float
    spent: float
    remaining: float
    status: str            # ok | warning | exceeded (absolute, vs the cap)
    projected: float       # projected month-end spend at the current daily pace
    pace_status: str       # on_track | at_risk | over (pace-based, current month)


class ZeroBasedBudgetService:
    def __init__(self, *, income: MonthlyIncomeRepository, assignments: BudgetAssignmentRepository,
                 transactions: TransactionRepository, splits: TransactionSplitRepository):
        self.income = income
        self.assignments = assignments
        self.transactions = transactions
        self.splits = splits

    async def ready_to_assign(self, month: str) -> float:
        inc = await self.income.get(month)
        income = inc.amount if inc is not None else 0.0
        assigned = await self.assignments.total_assigned(month)
        return income - assigned

    async def _spent_by_category(self, month: str) -> dict[int, float]:
        start, end = _month_range(month)
        spent: dict[int, float] = {}
        for txn in await self.transactions.list_in_month(start, end):
            if txn.direction != "debit":
                continue
            splits = await self.splits.list_for(txn.id)
            if splits:
                for s in splits:
                    if s.category_id is not None:
                        spent[s.category_id] = spent.get(s.category_id, 0.0) + s.amount
            elif txn.category_id is not None:
                spent[txn.category_id] = spent.get(txn.category_id, 0.0) + txn.amount
        return spent

    async def category_status(self, month: str, *, today=None,
                              names: dict[int, str] | None = None) -> list[CategoryStatus]:
        from datetime import date

        spent_map = await self._spent_by_category(month)
        names = names or {}
        today = today or date.today()

        # Pacing applies only to the in-progress current month.
        year, mon = int(month[:4]), int(month[5:7])
        if mon == 12:
            days_in_month = 31
        else:
            days_in_month = (date(year + (1 if mon == 12 else 0),
                                  (mon % 12) + 1, 1) - date(year, mon, 1)).days
        is_current = (today.year == year and today.month == mon)
        day = today.day if is_current else days_in_month
        elapsed_frac = max(day / days_in_month, 1e-9)

        statuses: list[CategoryStatus] = []
        for a in await self.assignments.list_for_month(month):
            spent = spent_map.get(a.category_id, 0.0)
            st = evaluate_budget(limit=a.assigned_amount, spent=spent)
            projected = spent / elapsed_frac if is_current else spent
            if a.assigned_amount <= 0:
                pace = "on_track"
            elif spent >= a.assigned_amount:
                pace = "over"
            elif projected > a.assigned_amount:
                pace = "at_risk"          # on current pace will blow the cap
            else:
                pace = "on_track"
            statuses.append(CategoryStatus(
                category_id=a.category_id, category=names.get(a.category_id, "category"),
                assigned=a.assigned_amount, spent=spent,
                remaining=a.assigned_amount - spent, status=st.status,
                projected=round(projected, 2), pace_status=pace,
            ))
        return statuses
