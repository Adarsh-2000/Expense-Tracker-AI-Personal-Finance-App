from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, Field


class SmsIn(BaseModel):
    sender: str
    body: str
    received_at: datetime
    external_id: str


class TransactionOut(BaseModel):
    id: int
    amount: float
    direction: str
    merchant: str
    occurred_at: datetime
    account_id: int
    category_id: int | None
    note: str | None = None
    entry_type: str | None = None
    balance_after: float | None = None
    currency: str | None = None
    original_amount: float | None = None
    txn_ref: str | None = None

    model_config = {"from_attributes": True}


class ManualTxnIn(BaseModel):
    amount: float
    direction: str = "debit"
    merchant: str
    occurred_at: datetime
    account_id: int | None = None
    category_id: int | None = None
    note: str | None = None


class TxnPatch(BaseModel):
    amount: float | None = None
    merchant: str | None = None
    category_id: int | None = None
    occurred_at: datetime | None = None
    note: str | None = None


class SplitItem(BaseModel):
    category_id: int | None = None
    amount: float


class SplitIn(BaseModel):
    splits: list[SplitItem]


class IncomeIn(BaseModel):
    month: str
    amount: float


class AssignIn(BaseModel):
    category_id: int
    month: str
    assigned_amount: float


class PinIn(BaseModel):
    pin: str


class GoogleIdTokenIn(BaseModel):
    id_token: str
    nonce: str | None = None


class ProfileIn(BaseModel):
    pan: str | None = None
    dob: str | None = None
    name: str | None = None


class ManualBalanceIn(BaseModel):
    balance: float
    as_of: str | None = None  # ISO YYYY-MM-DD; defaults to today server-side


class SetLimitIn(BaseModel):
    credit_limit: float = Field(gt=0)


class SetCardDetailsIn(BaseModel):
    credit_limit: float | None = Field(default=None, gt=0)
    network: str | None = None


class FixedDepositIn(BaseModel):
    bank: str
    principal: float
    interest_rate: float | None = None
    start_date: str | None = None
    maturity_date: str | None = None
    maturity_amount: float | None = None
    note: str | None = None


class GoalIn(BaseModel):
    name: str
    target_amount: float
    target_date: str | None = None


class FindingOut(BaseModel):
    id: int
    type: str
    severity: str
    title: str
    detail: str
    amount: float | None
    currency: str
    occurred_at: datetime
    status: str
    dedup_key: str
    query: dict | None = None


class MoneyRadarResponse(BaseModel):
    findings: list[FindingOut]
    new_count: int


class RadarSummary(BaseModel):
    new_count: int
    top: list[FindingOut]
