from __future__ import annotations

import re

_AMOUNT = re.compile(r"(?:INR|Rs\.?|₹)\s*(\d[\d,]*\.?\d*)", re.IGNORECASE)
_TENURE = re.compile(r"(\d{1,2})\s*month", re.IGNORECASE)


def parse_emi(sender: str, body: str) -> dict | None:
    low = body.lower()
    # Only real conversions, never the "convert now" promos.
    if "converted to emi" not in low and "emi conversion" not in low:
        return None
    amt = _AMOUNT.search(body)
    if not amt:
        return None
    tenure = _TENURE.search(body)
    return {
        "amount": float(amt.group(1).replace(",", "")),
        "tenure_months": int(tenure.group(1)) if tenure else None,
    }
