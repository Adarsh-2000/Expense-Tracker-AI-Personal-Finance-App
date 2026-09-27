from __future__ import annotations

import re

_MERCHANT = re.compile(
    r"(?:towards|set up for|mandate on)\s+(.+?)"
    r"(?:\s+(?:from|for a frequency|for INR|for Rs|for amount|for ₹|for|starting)|\.|,)",
    re.IGNORECASE)
_AMOUNT = re.compile(r"(?:for\s+(?:INR|Rs\.?|₹)|amount)\s*(\d[\d,]*\.?\d*)", re.IGNORECASE)


def parse_mandate(sender: str, body: str) -> dict | None:
    low = body.lower()
    if "mandate" not in low and "autopay" not in low:
        return None
    if not any(k in low for k in ("created", "set up", "set-up", "revoked")):
        return None
    m = _MERCHANT.search(body)
    a = _AMOUNT.search(body)
    if not m or not a:
        return None
    return {
        "merchant": m.group(1).strip(),
        "amount": float(a.group(1).replace(",", "")),
        "action": "revoked" if "revok" in low else "created",
    }
