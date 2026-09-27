from __future__ import annotations

from dataclasses import dataclass


@dataclass
class BudgetStatus:
    percent: float
    status: str  # "ok" | "warning" | "exceeded"


def evaluate_budget(*, limit: float, spent: float, warn_at: float = 0.8) -> BudgetStatus:
    percent = round((spent / limit) * 100, 2) if limit else 0.0
    if spent >= limit:
        status = "exceeded"
    elif spent >= limit * warn_at:
        status = "warning"
    else:
        status = "ok"
    return BudgetStatus(percent=percent, status=status)
