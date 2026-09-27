from __future__ import annotations

from datetime import datetime

from sqlalchemy import Boolean, DateTime, Float, ForeignKey, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db import Base


class Account(Base):
    __tablename__ = "accounts"
    __table_args__ = (UniqueConstraint("bank", "type", "last4", name="uq_account_identity"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    bank: Mapped[str] = mapped_column(String(64))
    type: Mapped[str] = mapped_column(String(16))  # "card" | "bank" | "cash"
    last4: Mapped[str] = mapped_column(String(4))
    currency: Mapped[str] = mapped_column(String(8), default="INR")
    current_balance: Mapped[float | None] = mapped_column(Float, nullable=True)
    # User-entered balance override + the date it applies to (ISO YYYY-MM-DD).
    # When newer than the latest SMS-scanned balance, it wins.
    manual_balance: Mapped[float | None] = mapped_column(Float, nullable=True)
    manual_balance_at: Mapped[str | None] = mapped_column(String(10), nullable=True)
    credit_limit: Mapped[float | None] = mapped_column(Float, nullable=True)
    # Per-card payment network override ("visa" | "mastercard" | "rupay"), set by
    # the user when the SMS/email did not reveal it. None = unknown.
    network: Mapped[str | None] = mapped_column(String, nullable=True)


class FixedDeposit(Base):
    __tablename__ = "fixed_deposits"
    id: Mapped[int] = mapped_column(primary_key=True)
    bank: Mapped[str] = mapped_column(String(64))
    principal: Mapped[float] = mapped_column(Float)
    interest_rate: Mapped[float | None] = mapped_column(Float, nullable=True)  # % p.a.
    start_date: Mapped[str | None] = mapped_column(String(10), nullable=True)  # ISO
    maturity_date: Mapped[str | None] = mapped_column(String(10), nullable=True)  # ISO
    maturity_amount: Mapped[float | None] = mapped_column(Float, nullable=True)
    note: Mapped[str | None] = mapped_column(String(128), nullable=True)


class Category(Base):
    __tablename__ = "categories"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(64), unique=True)
    parent_id: Mapped[int | None] = mapped_column(ForeignKey("categories.id"), nullable=True)


class RawMessage(Base):
    __tablename__ = "raw_messages"

    id: Mapped[int] = mapped_column(primary_key=True)
    channel: Mapped[str] = mapped_column(String(8))  # "email" | "sms"
    sender: Mapped[str] = mapped_column(String(128))
    body: Mapped[str] = mapped_column(String)
    received_at: Mapped[datetime] = mapped_column(DateTime)
    external_id: Mapped[str | None] = mapped_column(String(256), nullable=True, unique=True)
    parsed_status: Mapped[str] = mapped_column(String(16), default="pending")
    transaction_id: Mapped[int | None] = mapped_column(ForeignKey("transactions.id"), nullable=True)

    transaction: Mapped["Transaction | None"] = relationship(back_populates="sources")


class Transaction(Base):
    __tablename__ = "transactions"

    id: Mapped[int] = mapped_column(primary_key=True)
    amount: Mapped[float] = mapped_column(Float)
    direction: Mapped[str] = mapped_column(String(8))  # "debit" | "credit"
    merchant: Mapped[str] = mapped_column(String(256))
    occurred_at: Mapped[datetime] = mapped_column(DateTime)
    account_id: Mapped[int] = mapped_column(ForeignKey("accounts.id"))
    category_id: Mapped[int | None] = mapped_column(ForeignKey("categories.id"), nullable=True)
    fingerprint: Mapped[str] = mapped_column(String(512), index=True)
    entry_type: Mapped[str] = mapped_column(String(8), default="auto")
    note: Mapped[str | None] = mapped_column(String(512), nullable=True)
    balance_after: Mapped[float | None] = mapped_column(Float, nullable=True)
    currency: Mapped[str] = mapped_column(String(8), default="INR")
    original_amount: Mapped[float | None] = mapped_column(Float, nullable=True)
    txn_ref: Mapped[str | None] = mapped_column(String(64), nullable=True)

    sources: Mapped[list[RawMessage]] = relationship(back_populates="transaction")


class Budget(Base):
    __tablename__ = "budgets"

    id: Mapped[int] = mapped_column(primary_key=True)
    category_id: Mapped[int] = mapped_column(ForeignKey("categories.id"))
    period: Mapped[str] = mapped_column(String(16), default="monthly")
    limit_amount: Mapped[float] = mapped_column(Float)


class RecurringSeries(Base):
    __tablename__ = "recurring_series"

    id: Mapped[int] = mapped_column(primary_key=True)
    merchant: Mapped[str] = mapped_column(String(256))
    account_id: Mapped[int] = mapped_column(ForeignKey("accounts.id"))
    cadence_days: Mapped[int] = mapped_column(default=30)
    expected_amount: Mapped[float] = mapped_column(Float)
    last_seen_at: Mapped[datetime] = mapped_column(DateTime)
    next_expected_at: Mapped[datetime] = mapped_column(DateTime)


class TransactionSplit(Base):
    __tablename__ = "transaction_splits"
    id: Mapped[int] = mapped_column(primary_key=True)
    transaction_id: Mapped[int] = mapped_column(ForeignKey("transactions.id"))
    category_id: Mapped[int | None] = mapped_column(ForeignKey("categories.id"), nullable=True)
    amount: Mapped[float] = mapped_column(Float)


class BalanceSnapshot(Base):
    __tablename__ = "balance_snapshots"
    id: Mapped[int] = mapped_column(primary_key=True)
    account_id: Mapped[int] = mapped_column(ForeignKey("accounts.id"))
    balance: Mapped[float] = mapped_column(Float)
    captured_at: Mapped[datetime] = mapped_column(DateTime)


class BudgetAssignment(Base):
    __tablename__ = "budget_assignments"
    id: Mapped[int] = mapped_column(primary_key=True)
    category_id: Mapped[int] = mapped_column(ForeignKey("categories.id"))
    month: Mapped[str] = mapped_column(String(7))  # "YYYY-MM"
    assigned_amount: Mapped[float] = mapped_column(Float)


class MonthlyIncome(Base):
    __tablename__ = "monthly_income"
    id: Mapped[int] = mapped_column(primary_key=True)
    month: Mapped[str] = mapped_column(String(7))
    amount: Mapped[float] = mapped_column(Float)


class UserProfile(Base):
    # Single-row table (id=1 convention) holding the user's identity bits needed
    # to decrypt Zerodha statements: PAN (also the statement password) and DOB.
    __tablename__ = "user_profile"
    id: Mapped[int] = mapped_column(primary_key=True)
    pan: Mapped[str | None] = mapped_column(String(10), nullable=True)
    dob: Mapped[str | None] = mapped_column(String(10), nullable=True)  # "DD-MM-YYYY"
    name: Mapped[str | None] = mapped_column(String(64), nullable=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)


class Holding(Base):
    # A flat snapshot of the latest demat holdings; replace_all swaps the whole
    # snapshot on each sync (no history kept).
    __tablename__ = "holdings"
    id: Mapped[int] = mapped_column(primary_key=True)
    isin: Mapped[str] = mapped_column(String(12))
    name: Mapped[str] = mapped_column(String(128))
    quantity: Mapped[float] = mapped_column(Float)
    rate: Mapped[float] = mapped_column(Float)
    value: Mapped[float] = mapped_column(Float)
    type: Mapped[str] = mapped_column(String(16))  # "stock" | "mutual_fund"
    as_of: Mapped[str] = mapped_column(String(10))  # YYYY-MM-DD


class UsPortfolio(Base):
    # Single-row (id=1) aggregate US-stocks summary from INDmoney's Analytics
    # Report email. INDmoney does not email a per-stock breakdown, so we store
    # only the aggregate (USD). Converted to INR at read time.
    __tablename__ = "us_portfolio"
    id: Mapped[int] = mapped_column(primary_key=True)
    invested_usd: Mapped[float | None] = mapped_column(Float, nullable=True)
    current_usd: Mapped[float] = mapped_column(Float)
    returns_pct: Mapped[float | None] = mapped_column(Float, nullable=True)
    account_last4: Mapped[str | None] = mapped_column(String(4), nullable=True)
    as_of: Mapped[str] = mapped_column(String(10))  # YYYY-MM-DD (email date)
    broker: Mapped[str] = mapped_column(String(32), default="INDmoney")


class Goal(Base):
    __tablename__ = "goals"
    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(128))
    target_amount: Mapped[float] = mapped_column(Float)
    target_date: Mapped[str | None] = mapped_column(String(10), nullable=True)  # ISO
    created_at: Mapped[str | None] = mapped_column(String(10), nullable=True)


class CardBill(Base):
    __tablename__ = "card_bills"
    id: Mapped[int] = mapped_column(primary_key=True)
    bank: Mapped[str] = mapped_column(String(32))
    last4: Mapped[str | None] = mapped_column(String(4), nullable=True)
    total_due: Mapped[float] = mapped_column(Float)
    min_due: Mapped[float | None] = mapped_column(Float, nullable=True)
    due_date: Mapped[str] = mapped_column(String(10))  # YYYY-MM-DD
    statement_period: Mapped[str | None] = mapped_column(String(32), nullable=True)
    received_at: Mapped[datetime] = mapped_column(DateTime)
    paid: Mapped[bool] = mapped_column(Boolean, default=False)


class Finding(Base):
    __tablename__ = "findings"

    id: Mapped[int] = mapped_column(primary_key=True)
    type: Mapped[str] = mapped_column(String(32))
    severity: Mapped[str] = mapped_column(String(16))
    title: Mapped[str] = mapped_column(String(256))
    detail: Mapped[str] = mapped_column(String(512))
    amount: Mapped[float | None] = mapped_column(Float, nullable=True)
    currency: Mapped[str] = mapped_column(String(8), default="INR")
    occurred_at: Mapped[datetime] = mapped_column(DateTime)
    detected_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)
    dedup_key: Mapped[str] = mapped_column(String(256), unique=True, index=True)
    status: Mapped[str] = mapped_column(String(16), default="new")  # new|seen|dismissed|resolved
    evidence_kind: Mapped[str | None] = mapped_column(String(16), nullable=True)
    evidence_ref: Mapped[str | None] = mapped_column(String(256), nullable=True)
    query_json: Mapped[str | None] = mapped_column(String(512), nullable=True)


class DeviceToken(Base):
    # An FCM registration token for a device, so the backend can send push
    # notifications. The token is the unique identity (a device re-registers the
    # same token on every launch; FCM rotates it occasionally via onNewToken).
    __tablename__ = "device_tokens"

    id: Mapped[int] = mapped_column(primary_key=True)
    token: Mapped[str] = mapped_column(String(512), unique=True, index=True)
    platform: Mapped[str] = mapped_column(String(16), default="android")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)
    last_seen_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)


class PushedNotification(Base):
    # Records which notification ids have already been pushed via FCM, so the same
    # event (stable id from build_notifications) is pushed once, not on every poll.
    __tablename__ = "pushed_notifications"

    id: Mapped[int] = mapped_column(primary_key=True)
    notif_id: Mapped[str] = mapped_column(String(256), unique=True, index=True)
    pushed_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)


class CardReward(Base):
    # Latest reward snapshot per credit card (replace-per-card, like Holding).
    __tablename__ = "card_rewards"
    __table_args__ = (UniqueConstraint("bank", "last4", name="uq_card_reward_identity"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    bank: Mapped[str] = mapped_column(String(32))
    last4: Mapped[str] = mapped_column(String(4))
    program: Mapped[str] = mapped_column(String(32))  # "IDFC_FIRST" | "AXIS_EDGE"
    points_balance: Mapped[int] = mapped_column(Integer, default=0)
    earned: Mapped[int] = mapped_column(Integer, default=0)
    redeemed: Mapped[int] = mapped_column(Integer, default=0)
    expiring_points: Mapped[int] = mapped_column(Integer, default=0)
    expiring_date: Mapped[str | None] = mapped_column(String(10), nullable=True)  # YYYY-MM-DD
    statement_period: Mapped[str | None] = mapped_column(String(32), nullable=True)
    as_of: Mapped[str] = mapped_column(String(10))  # YYYY-MM-DD


class RewardEvent(Base):
    # Append-only log of redemptions + vouchers parsed from SMS/email.
    __tablename__ = "reward_events"
    id: Mapped[int] = mapped_column(primary_key=True)
    bank: Mapped[str] = mapped_column(String(32))
    last4: Mapped[str | None] = mapped_column(String(4), nullable=True)
    points: Mapped[int | None] = mapped_column(Integer, nullable=True)
    merchant: Mapped[str | None] = mapped_column(String(128), nullable=True)
    occurred_at: Mapped[str] = mapped_column(String(10))  # YYYY-MM-DD
    voucher_code: Mapped[str | None] = mapped_column(String(64), nullable=True)
    voucher_expiry: Mapped[str | None] = mapped_column(String(10), nullable=True)
    kind: Mapped[str] = mapped_column(String(16))  # "redemption" | "voucher"
    dedup_key: Mapped[str] = mapped_column(String(160), unique=True)


class InsightSummary(Base):
    __tablename__ = "insight_summaries"

    id: Mapped[int] = mapped_column(primary_key=True)
    period: Mapped[str] = mapped_column(String(7), unique=True)        # "YYYY-MM"
    payload_hash: Mapped[str] = mapped_column(String(64))
    model: Mapped[str] = mapped_column(String(128))
    spending_patterns: Mapped[str] = mapped_column(String, default="")
    how_to_save: Mapped[str] = mapped_column(String, default="")
    money_radar: Mapped[str] = mapped_column(String, default="")
    generated_at: Mapped[datetime] = mapped_column(DateTime)
