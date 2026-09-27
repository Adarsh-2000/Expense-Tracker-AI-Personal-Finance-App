from __future__ import annotations

import re
from datetime import datetime

# Phrases that indicate a message is NOT a real transaction and must be
# rejected (OTP / e-statement / registration noise). Applied case-insensitively.
REJECT_PHRASES = (
    "amount due",
    "e-statement",
    "statement of",
    "statement for your",
    "set your upi pin",
    "initiated the process",
    "declined",
    # Bill payment confirmations / received-money notices (noise per user).
    "has been received towards",
    "payment received towards",
    "payment of inr",
    # KYC / offers / maintenance / account-link noise.
    "video kyc",
    "details are missing",
    "reward points",
    "maintenance downtime",
    "being linked to your upi",
    "invalid transaction password",
    "closure notification",
)

# OTP messages LOOK like spends (amount + "at MERCHANT") so they MUST be rejected
# — but matched PRECISELY. A naive "otp"/"do not share" substring wrongly rejects
# real transaction emails whose security footer says "never share your CVV/OTP" or
# "do not share your internet banking details". These target OTP as the subject.
_OTP_PATTERNS = (
    "secret otp",
    "is the otp",
    "otp for ",
    "otp for txn",
    "otp to complete",
    "use this otp",
    "one time password",
    "otp valid for",
)


def should_reject(body: str) -> bool:
    """Return True if the body is non-transactional noise to be rejected."""
    low = body.lower()
    if any(phrase in low for phrase in REJECT_PHRASES):
        return True
    # Precise OTP detection (avoids false-rejecting footers that merely mention "otp").
    return any(p in low for p in _OTP_PATTERNS)


# Shared regex piece matching an optional currency token followed by a number.
# Group 1 = currency token, group 2 = numeric amount. Use with re.IGNORECASE.
CURRENCY_AMOUNT = r"(INR|Rs\.?|USD|EUR|GBP|\$)\s*([\d,]+\.?\d*)"


def normalize_currency(token: str) -> str:
    """Normalize a matched currency token to INR/USD/EUR/GBP ($->USD, Rs->INR)."""
    t = token.upper().strip().rstrip(".")
    if t == "$":
        return "USD"
    if t == "RS":
        return "INR"
    return t  # INR / USD / EUR / GBP


def extract_amount_currency(
    body: str, anchor: re.Pattern[str]
) -> tuple[float | None, str | None]:
    """Extract (amount, currency) using an anchor pattern.

    The anchor pattern contains a single ``%s`` placeholder where the
    currency+amount piece should be substituted (e.g. ``r"%s\\s+spent"``).
    Returns (None, None) if no match. Currency is normalized to INR/USD/EUR/GBP.
    """
    pattern = re.compile(anchor.pattern % CURRENCY_AMOUNT, anchor.flags)
    m = pattern.search(body)
    if not m:
        return None, None
    currency = normalize_currency(m.group(1))
    return to_amount(m.group(2)), currency


def to_inr_fields(
    native_amount: float, currency: str
) -> tuple[float, str, float | None]:
    """Map a native (amount, currency) to (inr_amount, currency, original_amount).

    For INR: returns the amount unchanged with original_amount=None.
    For foreign currency: returns the INR-equivalent (via configurable rates) as
    the amount, keeps the currency, and stores the native value as original_amount.
    """
    from app.config import settings

    if currency == "INR":
        return native_amount, "INR", None
    rate = settings.fx_rates_to_inr.get(currency, 1.0)
    return round(native_amount * rate, 2), currency, native_amount


def to_amount(raw: str) -> float:
    """Parse a rupee figure like '1,250.00' into a float."""
    return float(raw.replace(",", ""))


def search_amount(pattern: re.Pattern[str], body: str) -> float | None:
    """Run a precompiled amount pattern and return the captured float, or None."""
    m = pattern.search(body)
    return to_amount(m.group(1)) if m else None


# UPI transaction-info token: "UPI/P2A/<ref>/<name>" or "UPI/P2M/<ref>/<name>".
# The name runs to the end of token / common trailing markers.
_UPI_INFO = re.compile(
    r"UPI/(P2[AM])/(\d+)(?:/(.+?))?"
    r"(?=\s*(?:Not you\?|Not\b|If\b|Call\b|SMS\b|To\s+report|Dispute|Chk Bal|"
    r"Thank|Total|$|\.|\n|-\s*Axis|Available|Avlbl))",
    re.IGNORECASE,
)
# Security/boilerplate that some bank emails append after the payee name.
# Used to defensively truncate any merchant string that slipped through.
_MERCHANT_BOILERPLATE = re.compile(
    r"\s*(?:If\s+this\s+transaction|Not\s+you\??|Call\s|SMS\s|To\s+report|"
    r"Dispute|was\s+not\s+initiated).*$",
    re.IGNORECASE | re.DOTALL,
)


def clean_merchant(name: str | None) -> str | None:
    """Strip trailing security/boilerplate text from a parsed merchant name."""
    if not name:
        return name
    cleaned = _MERCHANT_BOILERPLATE.sub("", name).strip().rstrip(".").strip()
    return cleaned or name
# NEFT/IMPS/RTGS/ACH info token: "NEFT/CITIN.../NAME" or "ACH/SALARY/..." ->
# channel + ref. Covers salary/ACH credits and interbank transfers.
_NEFT_INFO = re.compile(
    r"\b(NEFT|IMPS|RTGS|ACH|UPI)/([A-Za-z0-9]+)",
    re.IGNORECASE,
)


def parse_upi_info(text: str) -> tuple[str | None, str | None, bool] | None:
    """Parse a UPI/NEFT transaction-info token.

    Returns (merchant, txn_ref, is_transfer) or None when no token is found.
    - UPI/P2A/<ref>/<name>: person-to-person -> is_transfer=True, merchant=name.
    - UPI/P2M/<ref>/<name>: merchant payment   -> is_transfer=False, merchant=name.
    - When only the ref is present (no name), merchant defaults to "UPI transfer".
    - NEFT/IMPS/RTGS/<ref>...: merchant=channel, is_transfer=True.
    """
    m = _UPI_INFO.search(text)
    if m:
        kind = m.group(1).upper()
        txn_ref = m.group(2)
        name = clean_merchant((m.group(3) or "").strip()) if m.group(3) else ""
        is_transfer = kind == "P2A"
        merchant = name if name else "UPI transfer"
        return merchant, txn_ref, is_transfer

    n = _NEFT_INFO.search(text)
    if n:
        channel = n.group(1).upper()
        return channel, n.group(2), True

    return None


# Datetime formats found in message bodies, tried in order.
#   "06-05-25, 18:47:40"          DD-MM-YY[, ]HH:MM:SS
#   "13-12-25, 14:52:41 IST"      DD-MM-YY[, ]HH:MM:SS (IST stripped)
#   "01-09-2025 09:59:40"         DD-MM-YYYY HH:MM:SS
#   "02-06-2026, 14:42:29"        DD-MM-YYYY[, ]HH:MM:SS
#   "2025-04-05 03:16:56 PM"      YYYY-MM-DD hh:mm:ss AM/PM
#   "29-SEP-2024 at 08:18 PM"     DD-MON-YYYY[ at ]hh:mm AM/PM (IDFC)
#   "15 MAR 2026"                 DD MON YYYY (IDFC email, date only)
#   "27-AUG-2024"                 DD-MON-YYYY (IDFC, date only)
_DT_PATTERNS = (
    re.compile(
        r"(\d{4}-\d{2}-\d{2})\s+(\d{1,2}:\d{2}:\d{2})\s*([AP]M)",
        re.IGNORECASE,
    ),
    re.compile(
        r"(\d{2}-\d{2}-\d{4})[,]?\s+(?:at\s+)?(\d{1,2}:\d{2}:\d{2})",
    ),
    re.compile(
        r"(\d{2}-\d{2}-\d{2})[,]?\s+(?:at\s+)?(\d{1,2}:\d{2}:\d{2})",
    ),
)

# IDFC month-name dates: "29-SEP-2024 at 08:18 PM", "15 MAR 2026", "27-AUG-2024".
# Optional trailing "at HH:MM AM/PM" time. Day/month/year, sep "-" or " ".
_DT_MONTHNAME = re.compile(
    r"\b(\d{1,2})[\s-]([A-Za-z]{3})[\s-](\d{4})"
    r"(?:\s+at\s+(\d{1,2}:\d{2})\s*([AP]M))?",
    re.IGNORECASE,
)
_MONTHS = {
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12,
}


def parse_dt(s: str) -> datetime | None:
    """Extract the first parseable transaction datetime from a string.

    Handles the SMS/email body formats:
      DD-MM-YY[, ]HH:MM:SS, DD-MM-YYYY[, ]HH:MM:SS (optional IST),
      YYYY-MM-DD hh:mm:ss AM/PM, and IDFC month-name dates
      (DD-MON-YYYY[ at HH:MM AM/PM] / DD MON YYYY). Returns None if none match.
    """
    for pat in _DT_PATTERNS:
        m = pat.search(s)
        if not m:
            continue
        date_part = m.group(1)
        time_part = m.group(2)
        if "-" in date_part and date_part[4] == "-":  # YYYY-MM-DD with AM/PM
            ampm = m.group(3).upper()
            return datetime.strptime(
                f"{date_part} {time_part} {ampm}", "%Y-%m-%d %I:%M:%S %p"
            )
        day, month, year = date_part.split("-")
        fmt_year = "%Y" if len(year) == 4 else "%y"
        return datetime.strptime(
            f"{date_part} {time_part}", f"%d-%m-{fmt_year} %H:%M:%S"
        )

    mn = _DT_MONTHNAME.search(s)
    if mn:
        month = _MONTHS.get(mn.group(2).lower())
        if month is not None:
            day, year = int(mn.group(1)), int(mn.group(3))
            hour, minute = 0, 0
            if mn.group(4):  # "HH:MM AM/PM" present
                t = datetime.strptime(f"{mn.group(4)} {mn.group(5).upper()}", "%I:%M %p")
                hour, minute = t.hour, t.minute
            return datetime(year, month, day, hour, minute)
    return None
