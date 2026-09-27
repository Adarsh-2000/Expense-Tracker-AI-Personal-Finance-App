from __future__ import annotations

import re

_AMOUNT = re.compile(r"(?:INR|Rs\.?|₹)\s*(\d[\d,]*\.?\d*)", re.IGNORECASE)
_LAST4 = re.compile(r"(?:XX|no\.?\s*XX|ending\s*XX?)(\d{4})", re.IGNORECASE)


def parse_refund(sender: str, body: str) -> dict | None:
    low = body.lower()
    if "credit balance" in low:
        kind = "credit_balance"
    elif "reversed" in low:
        kind = "reversal"
    elif "refunded" in low or "refund" in low:
        kind = "refund"
    else:
        return None
    amt = _AMOUNT.search(body)
    if not amt:
        return None
    last4 = _LAST4.search(body)
    return {
        "kind": kind,
        "amount": float(amt.group(1).replace(",", "")),
        "last4": last4.group(1) if last4 else None,
    }
