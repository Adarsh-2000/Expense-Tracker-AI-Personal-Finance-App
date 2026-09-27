from __future__ import annotations

import re
from datetime import datetime

from app.parsing._util import (
    extract_amount_currency,
    parse_dt,
    search_amount,
    should_reject,
    to_inr_fields,
)
from app.parsing.base import BankParser
from app.parsing.types import ParsedTransaction

# Prefix-agnostic spend amount: works for SMS ("Transaction Successful! INR X spent")
# and EMAIL ("Happy Shopping! INR X spent"). Currency token is captured so foreign
# (USD/EUR/GBP) spends like "USD 23.60 spent" also parse.
_AMOUNT = re.compile(r"%s\s+spent", re.IGNORECASE)
# last4 after "ending XX2198", "Card XX0684" or "ending with XX0684"
_CARD = re.compile(r"(?:ending\s+(?:with\s+)?)?XX(\d{4})", re.IGNORECASE)
# Non-transactional IDFC notices that share spend-like wording but are NOT new spends.
_IDFC_REJECT = (
    "converted into emi",
    "closure notification",
    "crossed",
    "amount due",
)
# Merchant between " at " and " on "
_AT = re.compile(r"\bat\s+(.+?)\s+on\b", re.IGNORECASE)
# Balance after "Avbl Limit:" or "Available limit:"
_BAL = re.compile(r"(?:Avbl|Available)\s+limit:\s*INR\s+([\d.,]+)", re.IGNORECASE)


class IdfcParser(BankParser):
    def matches(self, sender: str, body: str) -> bool:
        s = sender.lower()
        return "idfc" in s or "idfcfirstbank.com" in s or "idfc first" in body.lower()

    def parse(self, sender: str, body: str, received_at: datetime) -> ParsedTransaction | None:
        low = body.lower()
        if should_reject(body) or any(p in low for p in _IDFC_REJECT):
            return None

        native_amount, native_currency = extract_amount_currency(body, _AMOUNT)
        if native_amount is None:
            return None
        amount, currency, original_amount = to_inr_fields(native_amount, native_currency)

        card_m = _CARD.search(body)
        if not card_m:
            return None
        last4 = card_m.group(1)

        at_m = _AT.search(body)
        merchant = at_m.group(1).strip() if at_m else "UNKNOWN"

        return ParsedTransaction(
            bank="IDFC", account_type="card", last4=last4, amount=amount,
            direction="debit", merchant=merchant, occurred_at=received_at,
            balance_after=search_amount(_BAL, body),
            currency=currency, original_amount=original_amount,
            txn_time=parse_dt(body),
        )
