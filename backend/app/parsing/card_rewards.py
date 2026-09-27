"""Credit-card rewards: statement-password derivation, statement points-summary
extraction, and SMS/email redemption/voucher parsing.

Reuses holdings._decrypted_text to open password-protected statement PDFs."""
from __future__ import annotations

import re


def _name4(name: str | None) -> str | None:
    if not name:
        return None
    letters = re.sub(r"[^A-Za-z]", "", name)
    return letters[:4].upper() if len(letters) >= 4 else None


def _ddmm(dob: str | None) -> str | None:
    # dob stored as "DD-MM-YYYY"
    if not dob:
        return None
    m = re.match(r"(\d{2})-(\d{2})-(\d{4})", dob.strip())
    return m.group(1) + m.group(2) if m else None


def _ddmmyyyy(dob: str | None) -> str | None:
    if not dob:
        return None
    m = re.match(r"(\d{2})-(\d{2})-(\d{4})", dob.strip())
    return m.group(1) + m.group(2) + m.group(3) if m else None


def derive_statement_passwords(*, name: str | None, dob: str | None,
                               last4: str | None) -> list[str]:
    """Ordered candidate passwords for Axis/IDFC statement PDFs. The decrypt step
    tries each until one opens the file. Axis = name4+DDMM; others fall back to
    DDMMYYYY and name4+last4."""
    n4, dm, dmy = _name4(name), _ddmm(dob), _ddmmyyyy(dob)
    out: list[str] = []
    if n4 and dm:
        out.append(n4 + dm)          # Axis primary: HARS2903
    if dmy:
        out.append(dmy)              # IDFC candidate: 29031998
    if n4 and last4:
        out.append(n4 + last4)       # name4 + last4
    seen: set[str] = set()
    return [p for p in out if not (p in seen or seen.add(p))]


_INT = r"([\d,]+)"


def _find_int(text: str, *label_patterns: str) -> int | None:
    """First integer following any of the given label patterns (case-insensitive)."""
    for pat in label_patterns:
        m = re.search(pat + r"[^\d-]*" + _INT, text, re.I)
        if m:
            return int(m.group(1).replace(",", ""))
    return None


def _find_expiry(text: str) -> tuple[int | None, str | None]:
    """Points-expiring amount + ISO date from either
    'Points expiring on DD-MM-YYYY: N' or 'N points expiring on DD-MM-YYYY'."""
    m = re.search(r"expiring on\s+(\d{2})-(\d{2})-(\d{4})\s*[:\-]?\s*" + _INT, text, re.I)
    if m:
        iso = f"{m.group(3)}-{m.group(2)}-{m.group(1)}"
        return int(m.group(4).replace(",", "")), iso
    m = re.search(_INT + r"\s+points?\s+expiring on\s+(\d{2})-(\d{2})-(\d{4})", text, re.I)
    if m:
        iso = f"{m.group(4)}-{m.group(3)}-{m.group(2)}"
        return int(m.group(1).replace(",", "")), iso
    return None, None


def _find_period(text: str) -> str | None:
    m = re.search(r"Statement Period:\s*(.+)", text, re.I)
    return m.group(1).strip()[:32] if m else None


def extract_points_summary(text: str, program: str) -> dict | None:
    """Parse a reward-points summary out of decrypted statement text. Returns a
    dict with balance/earned/redeemed/expiring, or None when no closing balance
    can be found (so callers don't persist an empty snapshot)."""
    balance = _find_int(text, r"Closing Balance", r"Closing Points", r"Available Reward Points")
    if balance is None:
        return None
    earned = _find_int(text, r"Points Earned", r"\bEarned\b") or 0
    redeemed = _find_int(text, r"Points Redeemed", r"\bRedeemed\b") or 0
    expiring_points, expiring_date = _find_expiry(text)
    return {
        "program": program,
        "points_balance": balance,
        "earned": earned,
        "redeemed": redeemed,
        "expiring_points": expiring_points or 0,
        "expiring_date": expiring_date,
        "statement_period": _find_period(text),
    }


_MONTHS = {m: i for i, m in enumerate(
    ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"], 1)}


def _bank_from_sender(sender: str, body: str) -> str | None:
    s = (sender + " " + body).lower()
    if "idfc" in s:
        return "IDFC"
    if "axis" in s or "edge" in s:
        return "AXIS"
    return None


def _last4(body: str) -> str | None:
    m = re.search(r"X{2,}(\d{4})", body) or re.search(r"ending\s+\w*?(\d{4})", body, re.I)
    return m.group(1) if m else None


def parse_redemption_message(channel: str, sender: str, body: str, *,
                             occurred_at: str) -> dict | None:
    """Extract a redemption event ('N reward points redeemed ... at MERCHANT') from
    an SMS/email body. Returns None when the body isn't a redemption."""
    m = re.search(r"([\d,]+)\s+reward points?\s+redeemed", body, re.I)
    if not m:
        return None
    bank = _bank_from_sender(sender, body)
    if not bank:
        return None
    merchant_m = re.search(r"\bat\s+([A-Z0-9][A-Z0-9 &.\-]{2,40})", body)
    merchant = None
    if merchant_m:
        # Statements pad the merchant with multiple spaces before trailing text
        # ("...PVT L   . Available Reward Points"). Keep only the first run and
        # drop a dangling single-letter fragment left by the padding split.
        merchant = re.split(r"\s{2,}", merchant_m.group(1))[0].strip(" .")
        merchant = re.sub(r"\s+[A-Z]$", "", merchant).strip(" .") or None
    return {
        "bank": bank, "last4": _last4(body),
        "points": int(m.group(1).replace(",", "")),
        "merchant": merchant, "occurred_at": occurred_at,
        "voucher_code": None, "voucher_expiry": None, "kind": "redemption",
    }


def parse_voucher_email(body: str, *, occurred_at: str) -> dict | None:
    """Extract a redeemed-voucher event (code + expiry) from an EDGE voucher email."""
    code_m = re.search(r"Voucher Code\s+([A-Z0-9\-]{6,})", body, re.I)
    if not code_m:
        return None
    exp_m = re.search(r"(\d{1,2})\s+([A-Za-z]{3})[a-z]*\s+(\d{4})", body)
    expiry = None
    if exp_m:
        mon = _MONTHS.get(exp_m.group(2).lower()[:3])
        if mon:
            expiry = f"{exp_m.group(3)}-{mon:02d}-{int(exp_m.group(1)):02d}"
    return {
        "bank": "AXIS", "last4": None, "points": None, "merchant": "Voucher",
        "occurred_at": occurred_at, "voucher_code": code_m.group(1),
        "voucher_expiry": expiry, "kind": "voucher",
    }
