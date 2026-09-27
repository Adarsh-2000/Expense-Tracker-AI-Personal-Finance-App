from __future__ import annotations

import re

_AMOUNT = re.compile(r"INR\s*(\d[\d,]*\.?\d*)", re.IGNORECASE)
_LAST4 = re.compile(r"(?:XX|ending with\s*)(\d{4})", re.IGNORECASE)
_REASON = re.compile(r"(?:due to|declined as|is declined as)\s+(.+?)[\.\n]", re.IGNORECASE)


def _bank(blob: str) -> str:
    blob = blob.lower()
    if "idfc" in blob:
        return "IDFC"
    if "axis" in blob:
        return "AXIS"
    if "baroda" in blob or "bob" in blob:
        return "BOB"
    return "UNKNOWN"


def parse_decline(sender: str, body: str) -> dict | None:
    low = body.lower()
    if "declined" not in low and "is blocked" not in low:
        return None
    if "mandate" in low:        # mandate revokes are handled by the mandate parser
        return None
    amt = _AMOUNT.search(body)
    last4 = _LAST4.search(body)
    reason = _REASON.search(body)
    return {
        "bank": _bank(f"{sender} {body}"),
        "last4": last4.group(1) if last4 else None,
        "amount": float(amt.group(1).replace(",", "")) if amt else None,
        "reason": (reason.group(1).strip() if reason else "declined"),
    }
