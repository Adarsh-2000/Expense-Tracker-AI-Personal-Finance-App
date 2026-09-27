from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime


def normalize_merchant(raw: str) -> str:
    cleaned = re.sub(r"[^a-z0-9]+", " ", raw.lower())
    return cleaned.strip()


@dataclass(frozen=True)
class ParsedTransaction:
    bank: str
    account_type: str  # "card" | "bank"
    last4: str | None  # None when the source message omits an account/card number
    amount: float  # INR-normalized amount used for totals/aggregation
    direction: str  # "debit" | "credit"
    merchant: str
    occurred_at: datetime
    balance_after: float | None = None
    is_transfer: bool = False
    currency: str = "INR"  # native currency of the transaction
    # Native amount when currency != INR (amount holds the INR-equivalent); None for INR.
    original_amount: float | None = None
    # Transaction reference id (UPI ref / RRN / NEFT id) when present in the body.
    txn_ref: str | None = None
    # Precise transaction datetime parsed from the body; preferred over received_at
    # for occurred_at when set. Intentionally NOT part of fingerprint().
    txn_time: datetime | None = None

    def fingerprint(self) -> str:
        return f"{self.last4}|{self.amount}|{self.direction}|{normalize_merchant(self.merchant)}"
