from __future__ import annotations

import re

# INDmoney "US Stocks Analytics Report" emails carry an aggregate portfolio
# summary (no per-stock breakdown), e.g.:
#   "Invested Value: $1.15 K  Current Value: $1.65 K  Absolute Returns: 43.72%"
#   "US Stock A/C ending with XXXXXXXX1347"
# Values use K/M shorthand. This is the only US-stocks data INDmoney emails in a
# machine-readable form, so we surface it as an aggregate card.

_SUFFIX = {"k": 1_000.0, "m": 1_000_000.0, "b": 1_000_000_000.0}

_INVESTED = re.compile(r"Invested\s+Value\s*:\s*\$?\s*([\d,]+\.?\d*)\s*([KMB])?", re.IGNORECASE)
_CURRENT = re.compile(r"Current\s+Value\s*:\s*\$?\s*([\d,]+\.?\d*)\s*([KMB])?", re.IGNORECASE)
_RETURNS = re.compile(r"Absolute\s+Returns?\s*:\s*(-?[\d.]+)\s*%", re.IGNORECASE)
_ACCT = re.compile(r"A/C\s+ending\s+with\s+X+(\d{4})", re.IGNORECASE)


def _money(num: str, suffix: str | None) -> float:
    value = float(num.replace(",", ""))
    if suffix:
        value *= _SUFFIX[suffix.lower()]
    return round(value, 2)


def parse_us_portfolio(text: str) -> dict | None:
    """Parse the aggregate US-stocks summary from an INDmoney Analytics Report.

    Returns {invested_usd, current_usd, returns_pct, account_last4} or None when
    the body lacks a current-value figure (the one field we require).
    """
    cur = _CURRENT.search(text)
    if not cur:
        return None
    current_usd = _money(cur.group(1), cur.group(2))

    inv = _INVESTED.search(text)
    invested_usd = _money(inv.group(1), inv.group(2)) if inv else None

    ret = _RETURNS.search(text)
    returns_pct = float(ret.group(1)) if ret else None

    acct = _ACCT.search(text)
    account_last4 = acct.group(1) if acct else None

    return {
        "invested_usd": invested_usd,
        "current_usd": current_usd,
        "returns_pct": returns_pct,
        "account_last4": account_last4,
    }
