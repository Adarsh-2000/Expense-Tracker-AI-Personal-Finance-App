from __future__ import annotations

import re
from datetime import datetime

# Senders known to emit credit-card statements / payment reminders.
_CC_STATEMENT_SOURCES = ("idfcfirstbank.com", "cc.statements@axisbank.com")

_MONTHS = {
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12,
}

# A bare number, optionally prefixed with a currency symbol/word.
_NUM = r"(?:₹|INR|Rs\.?|\$)?\s*([\d,]+\.?\d*)"

_DATE_DDMMM = re.compile(
    r"(\d{1,2})\s+([A-Za-z]{3,9})[\s,]+(\d{4})"
)
_DATE_DDMMYYYY = re.compile(r"(\d{1,2})[/-](\d{1,2})[/-](\d{4})")
# 2-digit year, e.g. Axis "Due on: 21-06-26".
_DATE_DDMMYY = re.compile(r"(\d{1,2})[/-](\d{1,2})[/-](\d{2})(?!\d)")

_STATEMENT_PERIOD = re.compile(
    r"statement\s+for\s+([A-Za-z]+\s+\d{4})", re.IGNORECASE
)

_LAST4 = re.compile(
    r"(?:ending\s+(?:in|with)|card\s+no\.?|credit\s+card)\s+X+\s*(\d{4})",
    re.IGNORECASE,
)


def _to_float(raw: str) -> float:
    return float(raw.replace(",", "").strip())


def _parse_date(text: str) -> str | None:
    """Parse 'DD MMM, YYYY' or 'DD/MM/YYYY' into ISO 'YYYY-MM-DD'."""
    m = _DATE_DDMMM.search(text)
    if m:
        day, mon, year = m.group(1), m.group(2)[:3].lower(), m.group(3)
        if mon in _MONTHS:
            return f"{int(year):04d}-{_MONTHS[mon]:02d}-{int(day):02d}"
    m = _DATE_DDMMYYYY.search(text)
    if m:
        day, mon, year = m.group(1), m.group(2), m.group(3)
        return f"{int(year):04d}-{int(mon):02d}-{int(day):02d}"
    m = _DATE_DDMMYY.search(text)
    if m:
        day, mon, yy = m.group(1), m.group(2), m.group(3)
        return f"{2000 + int(yy):04d}-{int(mon):02d}-{int(day):02d}"
    return None


def _detect_bank(sender: str, body: str) -> str:
    blob = f"{sender} {body}".lower()
    if "idfc" in blob:
        return "IDFC"
    if "axis" in blob:
        return "AXIS"
    if "baroda" in blob or "bob" in blob:
        return "BOB"
    return "UNKNOWN"


def _extract_after_label(body: str, label: str) -> float | None:
    """Find a numeric amount that appears shortly after `label`.

    Handles '₹195.00', 'INR 195.00', '9512 Dr'. Returns the first number
    within a short window after the label.
    """
    pat = re.compile(
        re.escape(label) + r"[^\d₹$]*" + _NUM, re.IGNORECASE
    )
    m = pat.search(body)
    if m:
        return _to_float(m.group(1))
    return None


def parse_card_bill(sender: str, body: str, received_at: datetime) -> dict | None:
    """Parse a credit-card statement / payment-reminder email into a bill dict.

    Returns None unless the body clearly contains a credit-card bill signal.
    """
    low = body.lower()
    sender_low = (sender or "").lower()
    known_source = any(src in sender_low for src in _CC_STATEMENT_SOURCES)

    # Two phrasings exist: IDFC/Axis email reminders use "Total Amount Due" /
    # "Payment Due Date", while IDFC eStatement SMS uses "Total Due" /
    # "Payment Due by". Accept both.
    has_total_due = "total amount due" in low or "total due" in low or "total amt" in low
    has_due_date = "due date" in low or "due by" in low or "due on" in low
    mentions_card = "credit card" in low

    # Explicitly ignore bank ACCOUNT statements (e.g. BOB e-Statement) that are
    # not credit-card bills.
    if ("account statement" in low or "e-statement" in low) and not (
        mentions_card and has_due_date
    ):
        return None

    # Require BOTH a total-amount-due and a due-date signal, plus a credit-card
    # reference (or a known credit-card statement sender).
    if not (has_total_due and has_due_date and (mentions_card or known_source)):
        return None

    due_date = None
    # Take the text after "Payment Due Date" (or "Due Date") to scope date parse.
    due_anchor = re.search(r"(?:payment\s+)?due\s+(?:date|by|on)", low)
    if due_anchor:
        tail = body[due_anchor.start():]
        due_date = _parse_date(tail)
    if due_date is None:
        due_date = _parse_date(body)
    if due_date is None:
        return None

    # Axis table layout: the three labels appear clustered together
    # ("Total Amount Due ... Minimum Amount Due ... Payment Due Date ..."), then
    # the three VALUES appear later in order (total, min, due-date), each tagged
    # with "Dr". Detect the clustered layout and parse the values positionally;
    # a label-anchored search would otherwise grab the total for the min too.
    clustered = re.search(
        r"total\s+amount\s+due[^\d]{0,40}minimum\s+amount\s+due", low
    )
    dr_nums = re.findall(r"([\d,]+\.?\d*)\s*Dr", body)

    if clustered and len(dr_nums) >= 2:
        total_due = _to_float(dr_nums[0])
        min_due = _to_float(dr_nums[1])
    else:
        total_due = _extract_after_label(body, "Total Amount Due")
        min_due = _extract_after_label(body, "Minimum Amount Due")
        # IDFC eStatement SMS labels ("Total Due: INR ...", "Min Due: INR ...").
        if total_due is None:
            total_due = _extract_after_label(body, "Total Due")
        if min_due is None:
            min_due = _extract_after_label(body, "Min Due")
        # Axis statement SMS ("Total amt: INR Dr. X" / "Min amt due: INR Dr. X").
        if total_due is None:
            total_due = _extract_after_label(body, "Total amt")
        if min_due is None:
            min_due = _extract_after_label(body, "Min amt")
        if (total_due is None or min_due is None) and len(dr_nums) >= 2:
            if total_due is None:
                total_due = _to_float(dr_nums[0])
            if min_due is None:
                min_due = _to_float(dr_nums[1])

    if total_due is None:
        return None

    last4 = None
    m = _LAST4.search(body)
    if m:
        last4 = m.group(1)

    statement_period = None
    m = _STATEMENT_PERIOD.search(body)
    if m:
        statement_period = m.group(1).strip().upper()

    return {
        "bank": _detect_bank(sender, body),
        "last4": last4,
        "total_due": total_due,
        "min_due": min_due,
        "due_date": due_date,
        "statement_period": statement_period,
    }
