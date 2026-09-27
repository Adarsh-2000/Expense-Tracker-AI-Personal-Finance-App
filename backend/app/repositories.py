from __future__ import annotations

from datetime import datetime, timedelta

from sqlalchemy import delete, distinct, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.entities import (
    Account,
    BalanceSnapshot,
    BudgetAssignment,
    CardBill,
    Category,
    Holding,
    InsightSummary,
    MonthlyIncome,
    RawMessage,
    Transaction,
    TransactionSplit,
    UserProfile,
    UsPortfolio,
)


class AccountRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    # NOTE: SELECT-then-create. Safe because ingestion is single-user and sequential
    # (Android posts one SMS at a time; Gmail sync loops sequentially). The DB-level
    # UniqueConstraint on (bank, type, last4) is a safety net against duplicates.
    # If concurrent ingestion is ever added, wrap the INSERT in IntegrityError-retry.
    async def get_or_create(self, *, bank: str, type: str, last4: str, currency: str = "INR") -> Account:
        stmt = select(Account).where(
            Account.bank == bank, Account.type == type, Account.last4 == last4
        )
        existing = (await self.session.execute(stmt)).scalar_one_or_none()
        if existing is not None:
            return existing
        account = Account(bank=bank, type=type, last4=last4, currency=currency)
        self.session.add(account)
        await self.session.flush()
        return account

    async def list_all(self) -> list[Account]:
        return list((await self.session.execute(select(Account))).scalars())

    async def get(self, account_id: int) -> Account | None:
        return await self.session.get(Account, account_id)

    async def set_manual_balance(self, account_id: int, *, balance: float, as_of: str) -> Account | None:
        acc = await self.session.get(Account, account_id)
        if acc is None:
            return None
        acc.manual_balance = balance
        acc.manual_balance_at = as_of
        return acc

    async def set_credit_limit(self, account_id: int, *, credit_limit: float):
        acc = await self.get(account_id)
        if acc is None:
            return None
        acc.credit_limit = credit_limit
        return acc

    # Accepted card payment networks; anything else is treated as "unknown" (None).
    _NETWORKS = {"visa", "mastercard", "rupay"}

    @classmethod
    def _normalize_network(cls, network: str | None) -> str | None:
        if network is None:
            return None
        n = network.strip().lower()
        return n if n in cls._NETWORKS else None

    async def set_card_details(
        self, account_id: int, *, credit_limit: float | None = None,
        network: str | None = None,
    ) -> bool:
        """Set the per-card credit_limit and/or network override. Only fields that
        are provided (non-None) are written, so callers can update one without
        clobbering the other. Network is normalized to one of {visa, mastercard,
        rupay} (else left None). Returns False if the account does not exist."""
        acc = await self.get(account_id)
        if acc is None:
            return False
        if credit_limit is not None:
            acc.credit_limit = credit_limit
        if network is not None:
            acc.network = self._normalize_network(network)
        await self.session.flush()
        return True


class GoalRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def list_all(self) -> list:
        from app.entities import Goal
        return list((await self.session.execute(select(Goal).order_by(Goal.target_date))).scalars())

    async def add(self, **kw):
        from app.entities import Goal
        g = Goal(**kw)
        self.session.add(g)
        return g

    async def delete(self, goal_id: int) -> bool:
        from app.entities import Goal
        g = await self.session.get(Goal, goal_id)
        if g is None:
            return False
        await self.session.delete(g)
        return True


class FixedDepositRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def list_all(self) -> list["FixedDeposit"]:
        from app.entities import FixedDeposit
        return list((await self.session.execute(
            select(FixedDeposit).order_by(FixedDeposit.maturity_date)
        )).scalars())

    async def add(self, **kw) -> "FixedDeposit":
        from app.entities import FixedDeposit
        fd = FixedDeposit(**kw)
        self.session.add(fd)
        return fd

    async def delete(self, fd_id: int) -> bool:
        from app.entities import FixedDeposit
        fd = await self.session.get(FixedDeposit, fd_id)
        if fd is None:
            return False
        await self.session.delete(fd)
        return True


class RawMessageRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def add_if_new(self, message: RawMessage) -> RawMessage | None:
        if message.external_id is not None:
            stmt = select(RawMessage).where(RawMessage.external_id == message.external_id)
            if (await self.session.execute(stmt)).scalar_one_or_none() is not None:
                return None
        self.session.add(message)
        return message

    async def list_pending(self) -> list[RawMessage]:
        stmt = select(RawMessage).where(RawMessage.parsed_status == "pending")
        return list((await self.session.execute(stmt)).scalars())

    async def list_by_status(self, status: str) -> list[RawMessage]:
        stmt = select(RawMessage).where(RawMessage.parsed_status == status)
        return list((await self.session.execute(stmt)).scalars())


class TransactionRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def create(self, **kwargs) -> Transaction:
        txn = Transaction(**kwargs)
        self.session.add(txn)
        return txn

    async def find_recent_by_fingerprint(
        self, fingerprint: str, *, before: datetime, window_hours: int
    ) -> Transaction | None:
        # `before` is the incoming occurred_at; we search a SYMMETRIC window centered
        # on it ([center - window, center + window]) so a matching txn that occurred
        # LATER (e.g. the SMS local time vs the earlier email) is still found. This
        # fixes the backward-only miss that created cross-channel duplicates.
        center = before
        earliest = center - timedelta(hours=window_hours)
        latest = center + timedelta(hours=window_hours)
        stmt = (
            select(Transaction)
            .where(
                Transaction.fingerprint == fingerprint,
                Transaction.occurred_at >= earliest,
                Transaction.occurred_at <= latest,
            )
            .order_by(Transaction.occurred_at.desc())
        )
        return (await self.session.execute(stmt)).scalars().first()

    async def find_recent_amount_match(self, *, account_id, amount, direction, before, window_hours):
        # Symmetric window centered on the incoming occurred_at (see
        # find_recent_by_fingerprint) so forward-in-time matches are caught too.
        center = before
        earliest = center - timedelta(hours=window_hours)
        latest = center + timedelta(hours=window_hours)
        stmt = (select(Transaction).where(
            Transaction.account_id == account_id, Transaction.amount == amount,
            Transaction.direction == direction,
            Transaction.occurred_at >= earliest, Transaction.occurred_at <= latest)
            .order_by(Transaction.occurred_at.desc()))
        return (await self.session.execute(stmt)).scalars().first()

    async def list_in_month(self, start, end) -> list[Transaction]:
        stmt = select(Transaction).where(
            Transaction.occurred_at >= start, Transaction.occurred_at < end
        )
        return list((await self.session.execute(stmt)).scalars())

    async def has_source_from_channel(self, transaction_id: int, channel: str) -> bool:
        stmt = (
            select(RawMessage.id)
            .where(RawMessage.transaction_id == transaction_id, RawMessage.channel == channel)
            .limit(1)
        )
        return (await self.session.execute(stmt)).first() is not None

    async def get(self, txn_id: int) -> Transaction | None:
        return await self.session.get(Transaction, txn_id)

    # Generic tokens that should never drive a merchant match on their own — they
    # appear in countless payee strings (UPI handles, generic rails, legal suffixes).
    _MERCHANT_STOPWORDS = {
        "web", "upi", "app", "pay", "payment", "qr", "com", "in", "india",
        "media", "services", "service", "private", "limited", "ltd", "pvt", "llp",
        "inc", "co", "software", "solutions", "technologies", "the", "and",
    }

    async def search(
        self, *, q=None, merchant_any=None, account_id=None, category_id=None, start=None, end=None,
        min_amount=None, max_amount=None, direction=None,
    ) -> list[Transaction]:
        stmt = select(Transaction)
        if q:
            stmt = stmt.where(or_(Transaction.merchant.ilike(f"%{q}%"), Transaction.note.ilike(f"%{q}%")))
        if merchant_any:
            # Match transactions whose merchant contains ANY significant word of the
            # given name. Lets a Money Radar finding ("Disney Hotstar", "NETFLIX COM")
            # surface its debits even when they're tagged under a different payee
            # string ("Hotstar", "netflixupi..."). Falls back to a plain substring
            # match when no significant tokens remain (e.g. "WEB UPI").
            import re
            tokens = [t for t in re.split(r"[^A-Za-z0-9]+", merchant_any)
                      if len(t) >= 3 and t.lower() not in self._MERCHANT_STOPWORDS]
            if tokens:
                stmt = stmt.where(or_(*[Transaction.merchant.ilike(f"%{t}%") for t in tokens]))
            else:
                stmt = stmt.where(Transaction.merchant.ilike(f"%{merchant_any}%"))
        if account_id is not None:
            stmt = stmt.where(Transaction.account_id == account_id)
        if category_id is not None:
            stmt = stmt.where(Transaction.category_id == category_id)
        if direction is not None:
            stmt = stmt.where(Transaction.direction == direction)
        if start is not None:
            stmt = stmt.where(Transaction.occurred_at >= start)
        if end is not None:
            stmt = stmt.where(Transaction.occurred_at <= end)
        if min_amount is not None:
            stmt = stmt.where(Transaction.amount >= min_amount)
        if max_amount is not None:
            stmt = stmt.where(Transaction.amount <= max_amount)
        return list((await self.session.execute(stmt.order_by(Transaction.occurred_at.desc()))).scalars())

    async def list_all(self) -> list[Transaction]:
        stmt = select(Transaction).order_by(Transaction.occurred_at.desc())
        return list((await self.session.execute(stmt)).scalars())

    async def category_totals(self) -> list[tuple[str, float]]:
        from app.entities import Category
        stmt = (
            select(Category.name, func.sum(Transaction.amount))
            .join(Category, Transaction.category_id == Category.id)
            .where(Transaction.direction == "debit")
            .group_by(Category.name)
        )
        return [(name, float(total)) for name, total in (await self.session.execute(stmt)).all()]

    async def category_totals_in_month(self, start, end) -> list[tuple[str, float]]:
        from app.entities import Category
        stmt = (
            select(Category.name, func.sum(Transaction.amount))
            .join(Category, Transaction.category_id == Category.id)
            .where(Transaction.direction == "debit",
                   Transaction.occurred_at >= start, Transaction.occurred_at < end)
            .group_by(Category.name)
        )
        return [(name, float(total)) for name, total in (await self.session.execute(stmt)).all()]

    async def spent_in_month(self, start, end) -> float:
        stmt = select(func.coalesce(func.sum(Transaction.amount), 0.0)).where(
            Transaction.direction == "debit",
            Transaction.occurred_at >= start, Transaction.occurred_at < end)
        return float((await self.session.execute(stmt)).scalar_one())

    async def spent_in_range(self, start, end) -> float:
        stmt = select(func.coalesce(func.sum(Transaction.amount), 0.0)).where(
            Transaction.direction == "debit",
            Transaction.occurred_at >= start, Transaction.occurred_at < end)
        return float((await self.session.execute(stmt)).scalar_one())

    async def category_totals_in_range(self, start, end) -> list[tuple[str, float]]:
        from app.entities import Category
        stmt = (select(Category.name, func.sum(Transaction.amount))
            .join(Category, Transaction.category_id == Category.id)
            .where(Transaction.direction == "debit",
                   Transaction.occurred_at >= start, Transaction.occurred_at < end)
            .group_by(Category.name))
        return [(n, float(t)) for n, t in (await self.session.execute(stmt)).all()]

    async def breakdown_rows(self, start, end, category_id=None) -> list[tuple[str, str, float]]:
        """Debit rows in [start, end) as (category_name, merchant, amount) for
        sub-category aggregation. Credits excluded so totals match SPENT."""
        from app.entities import Category
        stmt = (select(Category.name, Transaction.merchant, Transaction.amount)
            .join(Category, Transaction.category_id == Category.id)
            .where(Transaction.direction == "debit",
                   Transaction.occurred_at >= start, Transaction.occurred_at < end))
        if category_id is not None:
            stmt = stmt.where(Transaction.category_id == category_id)
        rows = (await self.session.execute(stmt)).all()
        return [(name, merch, float(amt)) for name, merch, amt in rows]

    async def analytics_rows(self, start, end, direction="debit") -> list[dict]:
        """Rows in [start,end) with the fields the analytics endpoints need:
        merchant, amount, occurred_at, account_type, last4, category_name."""
        from app.entities import Account, Category
        stmt = (select(Transaction.merchant, Transaction.amount, Transaction.occurred_at,
                       Account.type, Account.last4, Account.bank, Category.name)
                .join(Account, Transaction.account_id == Account.id)
                .outerjoin(Category, Transaction.category_id == Category.id)
                .where(Transaction.occurred_at >= start, Transaction.occurred_at < end))
        if direction is not None:
            stmt = stmt.where(Transaction.direction == direction)
        rows = (await self.session.execute(stmt)).all()
        return [{"merchant": m, "amount": float(a), "occurred_at": o,
                 "account_type": at, "last4": l4, "bank": bk, "category": cat}
                for m, a, o, at, l4, bk, cat in rows]

    async def months_with_data(self) -> list[str]:
        stmt = select(distinct(func.substr(Transaction.occurred_at, 1, 7))).order_by(
            func.substr(Transaction.occurred_at, 1, 7).desc()
        )
        return [m for (m,) in (await self.session.execute(stmt)).all() if m]

    async def monthly_spend_series(self) -> list[tuple[str, float]]:
        stmt = (select(func.substr(Transaction.occurred_at, 1, 7), func.sum(Transaction.amount))
            .where(Transaction.direction == "debit")
            .group_by(func.substr(Transaction.occurred_at, 1, 7))
            .order_by(func.substr(Transaction.occurred_at, 1, 7)))
        return [(m, float(t)) for m, t in (await self.session.execute(stmt)).all()]

    async def top_merchants(self, *, start=None, end=None, limit=10) -> list[tuple[str, float, int]]:
        stmt = select(Transaction.merchant, func.sum(Transaction.amount), func.count()).where(
            Transaction.direction == "debit")
        if start is not None:
            stmt = stmt.where(Transaction.occurred_at >= start)
        if end is not None:
            stmt = stmt.where(Transaction.occurred_at < end)
        stmt = stmt.group_by(Transaction.merchant).order_by(func.sum(Transaction.amount).desc()).limit(limit)
        return [(m, float(t), int(n)) for m, t, n in (await self.session.execute(stmt)).all()]

    async def daily_spend_in_month(self, start, end) -> list[tuple[str, float]]:
        stmt = (select(func.substr(Transaction.occurred_at, 1, 10), func.sum(Transaction.amount))
            .where(Transaction.direction == "debit",
                   Transaction.occurred_at >= start, Transaction.occurred_at < end)
            .group_by(func.substr(Transaction.occurred_at, 1, 10))
            .order_by(func.substr(Transaction.occurred_at, 1, 10)))
        return [(d, float(t)) for d, t in (await self.session.execute(stmt)).all()]

    async def foreign_charges(self, start, end) -> list[dict]:
        """Debits in [start,end) that were billed in a non-INR currency."""
        from app.entities import Transaction
        stmt = (select(Transaction.merchant, Transaction.amount,
                       Transaction.original_amount, Transaction.currency,
                       Transaction.occurred_at)
                .where(Transaction.direction == "debit",
                       Transaction.occurred_at >= start, Transaction.occurred_at < end,
                       Transaction.currency != "INR"))
        return [{"merchant": m, "amount": float(a),
                 "original_amount": (float(o) if o is not None else None),
                 "currency": c, "occurred_at": ts}
                for m, a, o, c, ts in (await self.session.execute(stmt)).all()]


class TransactionSplitRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def add(self, *, transaction_id: int, category_id: int | None, amount: float) -> TransactionSplit:
        split = TransactionSplit(transaction_id=transaction_id, category_id=category_id, amount=amount)
        self.session.add(split)
        return split

    async def list_for(self, transaction_id: int) -> list[TransactionSplit]:
        stmt = select(TransactionSplit).where(TransactionSplit.transaction_id == transaction_id)
        return list((await self.session.execute(stmt)).scalars())

    async def delete_for(self, transaction_id: int) -> None:
        await self.session.execute(
            delete(TransactionSplit).where(TransactionSplit.transaction_id == transaction_id)
        )


class BalanceSnapshotRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def add(self, *, account_id: int, balance: float, captured_at) -> BalanceSnapshot:
        snap = BalanceSnapshot(account_id=account_id, balance=balance, captured_at=captured_at)
        self.session.add(snap)
        return snap

    async def latest_for(self, account_id: int) -> BalanceSnapshot | None:
        stmt = (
            select(BalanceSnapshot)
            .where(BalanceSnapshot.account_id == account_id)
            .order_by(BalanceSnapshot.captured_at.desc())
        )
        return (await self.session.execute(stmt)).scalars().first()

    async def history(self, account_id: int) -> list[BalanceSnapshot]:
        stmt = (
            select(BalanceSnapshot)
            .where(BalanceSnapshot.account_id == account_id)
            .order_by(BalanceSnapshot.captured_at)
        )
        return list((await self.session.execute(stmt)).scalars())


class BudgetAssignmentRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def set(self, *, category_id: int, month: str, assigned_amount: float) -> BudgetAssignment:
        stmt = select(BudgetAssignment).where(
            BudgetAssignment.category_id == category_id, BudgetAssignment.month == month
        )
        existing = (await self.session.execute(stmt)).scalar_one_or_none()
        if existing is not None:
            existing.assigned_amount = assigned_amount
            return existing
        assignment = BudgetAssignment(category_id=category_id, month=month, assigned_amount=assigned_amount)
        self.session.add(assignment)
        return assignment

    async def list_for_month(self, month: str) -> list[BudgetAssignment]:
        stmt = select(BudgetAssignment).where(BudgetAssignment.month == month)
        return list((await self.session.execute(stmt)).scalars())

    async def total_assigned(self, month: str) -> float:
        stmt = select(func.coalesce(func.sum(BudgetAssignment.assigned_amount), 0.0)).where(
            BudgetAssignment.month == month
        )
        return float((await self.session.execute(stmt)).scalar_one())


class MonthlyIncomeRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def set(self, *, month: str, amount: float) -> MonthlyIncome:
        stmt = select(MonthlyIncome).where(MonthlyIncome.month == month)
        existing = (await self.session.execute(stmt)).scalar_one_or_none()
        if existing is not None:
            existing.amount = amount
            return existing
        income = MonthlyIncome(month=month, amount=amount)
        self.session.add(income)
        return income

    async def get(self, month: str) -> MonthlyIncome | None:
        stmt = select(MonthlyIncome).where(MonthlyIncome.month == month)
        return (await self.session.execute(stmt)).scalar_one_or_none()


class CardBillRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def upsert(
        self, *, bank: str, last4: str | None, due_date: str, total_due: float,
        min_due: float | None, statement_period: str | None, received_at: datetime,
    ) -> CardBill:
        # Dedupe by (bank, last4, due_date): the same statement re-ingested
        # updates amounts instead of creating a duplicate bill.
        stmt = select(CardBill).where(
            CardBill.bank == bank,
            CardBill.last4.is_(last4) if last4 is None else CardBill.last4 == last4,
            CardBill.due_date == due_date,
        )
        existing = (await self.session.execute(stmt)).scalar_one_or_none()
        if existing is not None:
            existing.total_due = total_due
            existing.min_due = min_due
            existing.statement_period = statement_period
            existing.received_at = received_at
            return existing
        bill = CardBill(
            bank=bank, last4=last4, due_date=due_date, total_due=total_due,
            min_due=min_due, statement_period=statement_period, received_at=received_at,
        )
        self.session.add(bill)
        return bill

    async def list_unpaid(self) -> list[CardBill]:
        stmt = select(CardBill).where(CardBill.paid.is_(False)).order_by(CardBill.due_date.asc())
        return list((await self.session.execute(stmt)).scalars())

    async def list_all(self) -> list[CardBill]:
        stmt = select(CardBill).order_by(CardBill.due_date.asc())
        return list((await self.session.execute(stmt)).scalars())

    async def get(self, bill_id: int) -> CardBill | None:
        return await self.session.get(CardBill, bill_id)

    async def mark_paid(self, bill_id: int) -> CardBill | None:
        bill = await self.session.get(CardBill, bill_id)
        if bill is not None:
            bill.paid = True
        return bill


class ProfileRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def get(self) -> UserProfile | None:
        # Single-row table: return the one row (id=1 convention) if it exists.
        return (await self.session.execute(select(UserProfile))).scalars().first()

    async def upsert(self, *, pan: str | None, dob: str | None,
                     name: str | None = None) -> UserProfile:
        existing = await self.get()
        if existing is not None:
            existing.pan = pan
            existing.dob = dob
            if name is not None:
                existing.name = name
            existing.updated_at = datetime.now()
            return existing
        profile = UserProfile(id=1, pan=pan, dob=dob, name=name, updated_at=datetime.now())
        self.session.add(profile)
        return profile


class HoldingRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def replace_all(self, holdings: list[dict], as_of: str) -> None:
        # Snapshot semantics: wipe the prior holdings and insert the new set.
        await self.session.execute(delete(Holding))
        for h in holdings:
            self.session.add(Holding(
                isin=h["isin"], name=h["name"], quantity=h["quantity"],
                rate=h["rate"], value=h["value"], type=h["type"], as_of=as_of,
            ))

    async def list_all(self) -> list[Holding]:
        return list((await self.session.execute(select(Holding))).scalars())


class UsPortfolioRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def get(self) -> UsPortfolio | None:
        return (await self.session.execute(select(UsPortfolio))).scalars().first()

    async def upsert(self, *, invested_usd, current_usd, returns_pct,
                     account_last4, as_of, broker="INDmoney") -> UsPortfolio:
        existing = await self.get()
        if existing is not None:
            existing.invested_usd = invested_usd
            existing.current_usd = current_usd
            existing.returns_pct = returns_pct
            existing.account_last4 = account_last4
            existing.as_of = as_of
            existing.broker = broker
            return existing
        row = UsPortfolio(
            id=1, invested_usd=invested_usd, current_usd=current_usd,
            returns_pct=returns_pct, account_last4=account_last4,
            as_of=as_of, broker=broker)
        self.session.add(row)
        return row


class CategoryRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def get_or_create(self, name: str) -> Category:
        stmt = select(Category).where(Category.name == name)
        existing = (await self.session.execute(stmt)).scalar_one_or_none()
        if existing is not None:
            return existing
        category = Category(name=name)
        self.session.add(category)
        return category

    async def list_all(self) -> list[Category]:
        return list((await self.session.execute(select(Category))).scalars())


class FindingRepository:
    _SEVERITY_RANK = {"security": 0, "action": 1, "warning": 2, "positive": 3, "info": 4}

    def __init__(self, session: AsyncSession):
        self.session = session

    async def upsert_by_dedup_key(self, draft):
        from app.entities import Finding
        existing = (await self.session.execute(
            select(Finding).where(Finding.dedup_key == draft.dedup_key))).scalar_one_or_none()
        if existing is not None:
            # Refresh display fields; never touch `status` (a dismissed finding stays dismissed).
            existing.type = draft.type
            existing.severity = draft.severity
            existing.title = draft.title
            existing.detail = draft.detail
            existing.amount = draft.amount
            existing.currency = draft.currency
            existing.occurred_at = draft.occurred_at
            existing.evidence_kind = draft.evidence_kind
            existing.evidence_ref = draft.evidence_ref
            existing.query_json = draft.query_json
            return existing
        finding = Finding(
            type=draft.type, severity=draft.severity, title=draft.title,
            detail=draft.detail, amount=draft.amount, currency=draft.currency,
            occurred_at=draft.occurred_at, dedup_key=draft.dedup_key, status="new",
            evidence_kind=draft.evidence_kind, evidence_ref=draft.evidence_ref,
            query_json=draft.query_json)
        self.session.add(finding)
        return finding

    async def list_active(self, *, include_dismissed: bool = False) -> list:
        from app.entities import Finding
        stmt = select(Finding)
        if not include_dismissed:
            stmt = stmt.where(Finding.status != "dismissed")
        rows = list((await self.session.execute(stmt)).scalars())
        rows.sort(key=lambda f: (self._SEVERITY_RANK.get(f.severity, 9), -(f.amount or 0.0)))
        return rows

    async def count_new(self) -> int:
        from app.entities import Finding
        return int((await self.session.execute(
            select(func.count()).select_from(Finding).where(Finding.status == "new"))).scalar_one())

    async def mark_all_seen(self) -> None:
        from sqlalchemy import update
        from app.entities import Finding
        await self.session.execute(
            update(Finding).where(Finding.status == "new").values(status="seen"))

    async def dismiss(self, finding_id: int) -> bool:
        from app.entities import Finding
        f = await self.session.get(Finding, finding_id)
        if f is None:
            return False
        f.status = "dismissed"
        return True


class RecurringSeriesRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def list_all(self) -> list:
        from app.entities import RecurringSeries
        return list((await self.session.execute(select(RecurringSeries))).scalars())

    async def upsert(self, *, merchant: str, account_id: int, expected_amount: float,
                     last_seen_at, cadence_days: int = 30):
        from datetime import timedelta

        from app.entities import RecurringSeries
        existing = (await self.session.execute(
            select(RecurringSeries).where(RecurringSeries.merchant == merchant))).scalar_one_or_none()
        next_at = last_seen_at + timedelta(days=cadence_days)
        if existing is not None:
            existing.expected_amount = expected_amount
            existing.last_seen_at = last_seen_at
            existing.next_expected_at = next_at
            return existing
        s = RecurringSeries(
            merchant=merchant, account_id=account_id, cadence_days=cadence_days,
            expected_amount=expected_amount, last_seen_at=last_seen_at, next_expected_at=next_at)
        self.session.add(s)
        return s


class DeviceTokenRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def register(self, *, token: str, platform: str = "android"):
        """Upsert a device's FCM token, refreshing last_seen. Idempotent — the app
        re-registers the same token on every launch."""
        from datetime import datetime

        from app.entities import DeviceToken
        existing = (await self.session.execute(
            select(DeviceToken).where(DeviceToken.token == token))).scalar_one_or_none()
        if existing is not None:
            existing.last_seen_at = datetime.now()
            existing.platform = platform
            return existing
        dt = DeviceToken(token=token, platform=platform)
        self.session.add(dt)
        return dt

    async def list_tokens(self) -> list[str]:
        from app.entities import DeviceToken
        return [t for (t,) in (await self.session.execute(select(DeviceToken.token))).all()]

    async def delete(self, token: str) -> None:
        from app.entities import DeviceToken
        await self.session.execute(delete(DeviceToken).where(DeviceToken.token == token))


class PushedNotificationRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def filter_unpushed(self, notif_ids: list[str]) -> list[str]:
        """Return the subset of notif_ids not yet pushed."""
        if not notif_ids:
            return []
        from app.entities import PushedNotification
        seen = {n for (n,) in (await self.session.execute(
            select(PushedNotification.notif_id).where(
                PushedNotification.notif_id.in_(notif_ids)))).all()}
        return [n for n in notif_ids if n not in seen]

    async def mark_pushed(self, notif_ids: list[str]) -> None:
        from app.entities import PushedNotification
        for nid in notif_ids:
            self.session.add(PushedNotification(notif_id=nid))


class CardRewardRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def upsert_for_card(self, *, bank: str, last4: str, program: str,
                              points_balance: int, earned: int, redeemed: int,
                              expiring_points: int, expiring_date: str | None,
                              statement_period: str | None, as_of: str):
        from app.entities import CardReward
        existing = (await self.session.execute(
            select(CardReward).where(CardReward.bank == bank, CardReward.last4 == last4)
        )).scalars().first()
        if existing is not None:
            existing.program = program
            existing.points_balance = points_balance
            existing.earned = earned
            existing.redeemed = redeemed
            existing.expiring_points = expiring_points
            existing.expiring_date = expiring_date
            existing.statement_period = statement_period
            existing.as_of = as_of
            return existing
        row = CardReward(bank=bank, last4=last4, program=program,
                         points_balance=points_balance, earned=earned, redeemed=redeemed,
                         expiring_points=expiring_points, expiring_date=expiring_date,
                         statement_period=statement_period, as_of=as_of)
        self.session.add(row)
        return row

    async def list_all(self):
        from app.entities import CardReward
        return list((await self.session.execute(select(CardReward))).scalars())


class RewardEventRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    @staticmethod
    def _key(bank, last4, occurred_at, points, voucher_code) -> str:
        return f"{bank}|{last4 or ''}|{occurred_at}|{points or ''}|{voucher_code or ''}"

    async def add_if_new(self, *, bank: str, last4: str | None, points: int | None,
                         merchant: str | None, occurred_at: str, voucher_code: str | None,
                         voucher_expiry: str | None, kind: str) -> bool:
        from app.entities import RewardEvent
        key = self._key(bank, last4, occurred_at, points, voucher_code)
        exists = (await self.session.execute(
            select(RewardEvent).where(RewardEvent.dedup_key == key)
        )).scalars().first()
        if exists is not None:
            return False
        self.session.add(RewardEvent(
            bank=bank, last4=last4, points=points, merchant=merchant,
            occurred_at=occurred_at, voucher_code=voucher_code,
            voucher_expiry=voucher_expiry, kind=kind, dedup_key=key))
        return True

    async def list_recent(self, limit: int = 20):
        from app.entities import RewardEvent
        return list((await self.session.execute(
            select(RewardEvent).order_by(RewardEvent.occurred_at.desc()).limit(limit)
        )).scalars())

    async def list_for_card(self, bank: str, last4: str | None, limit: int = 5):
        from app.entities import RewardEvent
        q = select(RewardEvent).where(RewardEvent.bank == bank)
        if last4 is not None:
            q = q.where(RewardEvent.last4 == last4)
        return list((await self.session.execute(
            q.order_by(RewardEvent.occurred_at.desc()).limit(limit))).scalars())


class InsightSummaryRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def get(self, period: str) -> InsightSummary | None:
        stmt = select(InsightSummary).where(InsightSummary.period == period)
        return (await self.session.execute(stmt)).scalar_one_or_none()

    async def upsert(self, period: str, payload_hash: str, model: str, *,
                     spending_patterns: str, how_to_save: str,
                     money_radar: str) -> InsightSummary:
        row = await self.get(period)
        if row is None:
            row = InsightSummary(period=period)
            self.session.add(row)
        row.payload_hash = payload_hash
        row.model = model
        row.spending_patterns = spending_patterns
        row.how_to_save = how_to_save
        row.money_radar = money_radar
        row.generated_at = datetime.now()
        await self.session.flush()
        return row
