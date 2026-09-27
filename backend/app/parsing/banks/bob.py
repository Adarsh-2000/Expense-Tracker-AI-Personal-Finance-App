from __future__ import annotations

import re
from datetime import datetime

from app.parsing._util import (
    CURRENCY_AMOUNT,
    normalize_currency,
    parse_dt,
    search_amount,
    should_reject,
    to_amount,
    to_inr_fields,
)
from app.parsing.base import BankParser
from app.parsing.types import ParsedTransaction

# Transfer/credit format: "Rs.20 transferred ..." / "Rs.1568 Credited ...".
# Currency token is captured generically (BOB is usually INR/Rs but harmless).
_AMOUNT_XFER = re.compile(
    rf"{CURRENCY_AMOUNT}\s+(?:transferred|Credited)", re.IGNORECASE
)
# UPI debit format: amount may precede ("Rs 149.00 debited from ...") or
# follow ("is debited for Rs. 50.0 ...") the word "debited".
_AMOUNT_UPI = re.compile(
    rf"{CURRENCY_AMOUNT}\s+debited|debited\s+for\s+{CURRENCY_AMOUNT}",
    re.IGNORECASE,
)

# Account last4: "A/c ...2404" / "A/C XXXXXX2404" / "a/c no. XXXXXX2404"
_ACCT = re.compile(r"a/?c\s*(?:no\.?\s*)?[.X]*(\d{4})", re.IGNORECASE)

# Credit merchant: " by abhi.prabal_ibl." -> token up to whitespace,
# then strip a trailing sentence period.
_BY = re.compile(r"\bby\s+(\S+)", re.IGNORECASE)
# UPI merchant: "credited to HOTSTARONLINE@ybl" / "credited to VPA rapido.rzp@icici"
_CREDITED_TO = re.compile(r"credited\s+to\s+(?:VPA\s+)?(\S+)", re.IGNORECASE)

# Balance only from "Avlbl Amt:Rs.<amount>" (never "Total Bal").
_BAL = re.compile(r"Avlbl\s+Amt:\s*Rs\.?\s*([\d,]+\.?\d*)", re.IGNORECASE)

# UPI credit SMS (no last4): "your account is credited INR 290.00 ... by UPI Ref No <digits>"
_UPI_CREDIT = re.compile(
    rf"account\s+is\s+credited\s+{CURRENCY_AMOUNT}", re.IGNORECASE
)
_UPI_REF_NO = re.compile(r"UPI\s+Ref(?:\s+No)?\.?:?\s*(\d+)", re.IGNORECASE)
# Bare slash-ref form: "to:UPI/320648725455" / "thru UPI/2901633..." -> the digits.
_UPI_SLASH_REF = re.compile(r"UPI/(\d{6,})", re.IGNORECASE)


class BobParser(BankParser):
    def matches(self, sender: str, body: str) -> bool:
        s = sender.upper()
        return "BOB" in s or "BOBTXN" in s or "BOBSMS" in s or "bank of baroda" in body.lower()

    def parse(self, sender: str, body: str, received_at: datetime) -> ParsedTransaction | None:
        if should_reject(body):
            return None

        # UPI credit SMS: account credited, no account/card last4 present.
        upi_credit = self._parse_upi_credit(body, received_at)
        if upi_credit is not None:
            return upi_credit

        low = body.lower()
        is_transfer = "transferred" in low
        # Account credit ("Credited to A/c ...") vs UPI debit / transfer debit.
        is_credit = bool(re.search(r"\bcredited to a/?c", low))

        if is_transfer or is_credit:
            m = _AMOUNT_XFER.search(body)
            if m is None:
                return None
            native_amount = to_amount(m.group(2))
            native_currency = normalize_currency(m.group(1))
        else:
            m = _AMOUNT_UPI.search(body)
            if m is None:
                return None
            # Branch 1 (groups 1,2) = "<cur> <amt> debited"; branch 2 (groups 3,4)
            # = "debited for <cur> <amt>".
            if m.group(2) is not None:
                native_amount = to_amount(m.group(2))
                native_currency = normalize_currency(m.group(1))
            else:
                native_amount = to_amount(m.group(4))
                native_currency = normalize_currency(m.group(3))
        amount, currency, original_amount = to_inr_fields(native_amount, native_currency)

        acct_m = _ACCT.search(body)
        if not acct_m:
            return None
        last4 = acct_m.group(1)

        if is_credit:
            direction = "credit"
            by_m = _BY.search(body)
            merchant = by_m.group(1).rstrip(".") if by_m else "UNKNOWN"
        elif is_transfer:
            direction = "debit"
            # "to:UPI/<num>" has no real payee name — surface the UPI ref instead so
            # the transfer is identifiable.
            slash_ref = _UPI_SLASH_REF.search(body)
            merchant = f"UPI {slash_ref.group(1)}" if slash_ref else "UPI transfer"
        else:
            direction = "debit"
            to_m = _CREDITED_TO.search(body)
            merchant = to_m.group(1).rstrip(".") if to_m else "UNKNOWN"

        ref_m = _UPI_REF_NO.search(body) or _UPI_SLASH_REF.search(body)
        txn_ref = ref_m.group(1) if ref_m else None

        return ParsedTransaction(
            bank="BOB", account_type="bank", last4=last4, amount=amount,
            direction=direction, merchant=merchant, occurred_at=received_at,
            balance_after=search_amount(_BAL, body),
            # All BOB non-card flows are account/UPI money movements.
            is_transfer=True,
            currency=currency, original_amount=original_amount,
            txn_ref=txn_ref, txn_time=parse_dt(body),
        )

    def _parse_upi_credit(self, body: str, received_at: datetime) -> ParsedTransaction | None:
        m = _UPI_CREDIT.search(body)
        if not m:
            return None
        native_amount = to_amount(m.group(2))
        native_currency = normalize_currency(m.group(1))
        amount, currency, original_amount = to_inr_fields(native_amount, native_currency)

        ref_m = _UPI_REF_NO.search(body)
        txn_ref = ref_m.group(1) if ref_m else None

        return ParsedTransaction(
            bank="BOB", account_type="bank", last4=None, amount=amount,
            direction="credit", merchant="UPI credit", occurred_at=received_at,
            is_transfer=True, currency=currency, original_amount=original_amount,
            txn_ref=txn_ref, txn_time=parse_dt(body),
        )
