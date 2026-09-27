from __future__ import annotations

from datetime import datetime

from app.entities import Account
from app.repositories import AccountRepository, BalanceSnapshotRepository


class BalanceService:
    def __init__(self, *, accounts: AccountRepository, snapshots: BalanceSnapshotRepository):
        self.accounts = accounts
        self.snapshots = snapshots

    async def apply(self, account: Account, balance_after: float | None, at: datetime) -> None:
        if balance_after is None:
            return
        account.current_balance = balance_after
        await self.snapshots.add(account_id=account.id, balance=balance_after, captured_at=at)

    async def net_worth(self) -> float:
        accounts = await self.accounts.list_all()
        return sum((a.current_balance or 0.0) for a in accounts)
