from __future__ import annotations

import re
from datetime import datetime

from app.parsing._util import (
    clean_merchant,
    extract_amount_currency,
    normalize_currency,
    parse_dt,
    parse_upi_info,
    search_amount,
    should_reject,
    to_amount,
    to_inr_fields,
)
from app.parsing.base import BankParser
from app.parsing.types import ParsedTransaction

# Single-line spend: "INR 320 spent ..." or "USD 47.35 spent ..."
_SINGLE_SPEND = re.compile(r"%s\s+spent", re.IGNORECASE)
# Single-line account credit: "INR 229911.00 credited to A/c no. XX4400 ..."
_SINGLE_CREDIT = re.compile(r"%s\s+credited", re.IGNORECASE)
# Multiline standalone amount line: a line that is exactly "<currency> <amount>",
# e.g. "INR 257.53" or "USD 47.35" (NOT the "Avl Lmt INR ..." line).
_STANDALONE_AMOUNT = re.compile(
    r"^\s*(INR|Rs\.?|USD|EUR|GBP|\$)\s*([\d,]+\.?\d*)\s*$", re.IGNORECASE
)

_CARD = re.compile(r"card\s+no\.?\s*xx(\d{4})", re.IGNORECASE)
_ACCT = re.compile(r"a/?c\s+no\.?\s*xx(\d{4})", re.IGNORECASE)
# Account number labeled form: "Account Number: XX4400"
_ACCT_LABEL = re.compile(r"account\s+number:\s*xx(\d{4})", re.IGNORECASE)

# Single-line merchant: "at UBER on"
_AT = re.compile(r"\bat\s+(.+?)\s+on\b", re.IGNORECASE)
# Account credit info token: "Info - NEFT/..." -> "NEFT"
_INFO = re.compile(r"Info\s*-\s*([A-Za-z0-9]+)", re.IGNORECASE)

# Balance only from "Avl Lmt INR <amount>" (never from a plain "INR" line).
_BAL = re.compile(r"Avl\s+Lmt\s+INR\s+([\d,]+\.?\d*)", re.IGNORECASE)

# Labeled email-alert format (axis.bank.in):
#   "... Transaction Amount: USD 29.5 Merchant Name: ANTHROPIC Axis Bank Credit Card No. XX6978 ..."
_TXN_AMOUNT_LABEL = re.compile(
    r"Transaction\s+Amount:\s*(INR|Rs\.?|USD|EUR|GBP|\$)\s*([\d,]+\.?\d*)", re.IGNORECASE
)
_MERCHANT_LABEL = re.compile(
    r"Merchant\s+Name:\s*(.+?)\s+(?:Axis\s+Bank|Card\s+No|Date\s*&)", re.IGNORECASE
)
# Balance for the labeled email format: "Available Limit*: INR <amount>"
_AVAIL_LIMIT = re.compile(r"Available\s+Limit\*?:\s*INR\s*([\d,]+\.?\d*)", re.IGNORECASE)

# SMS UPI account debit:
#   "INR 818.60 debited A/c no. XX4400 06-05-25, 18:47:40 UPI/P2M/<ref>/<name> ..."
_SMS_UPI_DEBIT = re.compile(
    r"(INR|Rs\.?|USD|EUR|GBP|\$)\s*([\d,]+\.?\d*)\s+debited\s+A/?c\s+no\.?\s*xx(\d{4})",
    re.IGNORECASE,
)

# Email savings "Amount Debited: INR <n>"
_AMOUNT_DEBITED_LABEL = re.compile(
    r"Amount\s+Debited:\s*(INR|Rs\.?|USD|EUR|GBP|\$)\s*([\d,]+\.?\d*)", re.IGNORECASE
)
# Email "Transaction Info: <upi token>"
_TXN_INFO_LABEL = re.compile(r"Transaction\s+Info:\s*(.+)", re.IGNORECASE | re.DOTALL)

# Email savings debit narrative: "A/c no. XX4400 has been debited with INR
# 37027.41 on 03-06-2026 15:16:55 IST by CreditCard Payment XX 9966." Captures
# amount + the "by <payee>" merchant (e.g. credit-card bill payments, NEFT).
_DEBITED_WITH = re.compile(
    r"debited\s+with\s+(INR|Rs\.?|USD|EUR|GBP|\$)\s*([\d,]+\.?\d*)\s+on\b"
    r".*?\bby\s+(.+?)(?:\.|\s+To\s+|\s+Please\b|\s+Should\b|$)",
    re.IGNORECASE | re.DOTALL,
)

# Email CC spend "Thank you for using your credit card no. XX#### for INR <n> at <m> on"
_CC_THANKYOU = re.compile(
    r"using\s+your\s+credit\s+card\s+no\.?\s*xx(\d{4})\s+for\s+"
    r"(INR|Rs\.?|USD|EUR|GBP|\$)\s*([\d,]+\.?\d*)\s+at\s+(.+?)\s+on\b",
    re.IGNORECASE,
)

# Email savings NEFT credit:
#   "your A/c no. XX4400 has been credited with INR <n> on <date> ... by NEFT/<ref>"
_EMAIL_CREDITED_WITH = re.compile(
    r"A/?c\s+no\.?\s*xx(\d{4})\s+has\s+been\s+credited\s+with\s+"
    r"(INR|Rs\.?|USD|EUR|GBP|\$)\s*([\d,]+\.?\d*)",
    re.IGNORECASE,
)


class AxisParser(BankParser):
    def matches(self, sender: str, body: str) -> bool:
        s = sender.upper()
        return "AXIS" in s or "axis" in sender.lower() or "axis bank" in body.lower()

    def parse(self, sender: str, body: str, received_at: datetime) -> ParsedTransaction | None:
        if should_reject(body):
            return None

        # Try each format in order; first that matches wins.
        for handler in (
            self._parse_sms_upi_debit,
            self._parse_email_amount_debited,
            self._parse_email_debited_with,
            self._parse_email_cc_thankyou,
            self._parse_email_neft_credit,
            self._parse_labeled,
        ):
            parsed = handler(body, received_at)
            if parsed is not None:
                return parsed

        lines = body.split("\n")
        is_multiline = len(lines) > 1 and any(
            _STANDALONE_AMOUNT.match(line) for line in lines
        )
        if is_multiline:
            return self._parse_multiline(lines, received_at)
        return self._parse_single_line(body, received_at)

    # --- SMS UPI account debit ------------------------------------------
    def _parse_sms_upi_debit(self, body: str, received_at: datetime) -> ParsedTransaction | None:
        m = _SMS_UPI_DEBIT.search(body)
        if not m:
            return None
        native_amount = to_amount(m.group(2))
        native_currency = normalize_currency(m.group(1))
        amount, currency, original_amount = to_inr_fields(native_amount, native_currency)
        last4 = m.group(3)

        upi = parse_upi_info(body)
        if upi is not None:
            merchant, txn_ref, is_transfer = upi
        else:
            merchant, txn_ref, is_transfer = "UPI transfer", None, True

        return ParsedTransaction(
            bank="AXIS", account_type="bank", last4=last4, amount=amount,
            direction="debit", merchant=merchant, occurred_at=received_at,
            is_transfer=is_transfer, currency=currency, original_amount=original_amount,
            txn_ref=txn_ref, txn_time=parse_dt(body),
        )

    # --- Email savings "debited with INR X ... by <payee>" --------------
    def _parse_email_debited_with(self, body: str, received_at: datetime) -> ParsedTransaction | None:
        m = _DEBITED_WITH.search(body)
        if not m:
            return None
        native_amount = to_amount(m.group(2))
        native_currency = normalize_currency(m.group(1))
        amount, currency, original_amount = to_inr_fields(native_amount, native_currency)

        acct_m = _ACCT_LABEL.search(body) or _ACCT.search(body)
        last4 = acct_m.group(1) if acct_m else None

        merchant = clean_merchant(m.group(3).strip()) or "Bank transfer"
        low_m = merchant.lower()
        # Money moving to settle a card / interbank transfer -> mark as transfer
        # so it buckets out of consumption spend.
        is_transfer = any(k in low_m for k in (
            "payment", "transfer", "neft", "imps", "rtgs", "credit card"))

        return ParsedTransaction(
            bank="AXIS", account_type="bank", last4=last4, amount=amount,
            direction="debit", merchant=merchant, occurred_at=received_at,
            is_transfer=is_transfer, currency=currency, original_amount=original_amount,
            txn_ref=None, txn_time=parse_dt(body),
        )

    # --- Email savings "Amount Debited" ---------------------------------
    def _parse_email_amount_debited(self, body: str, received_at: datetime) -> ParsedTransaction | None:
        m = _AMOUNT_DEBITED_LABEL.search(body)
        if not m:
            return None
        native_amount = to_amount(m.group(2))
        native_currency = normalize_currency(m.group(1))
        amount, currency, original_amount = to_inr_fields(native_amount, native_currency)

        acct_m = _ACCT_LABEL.search(body) or _ACCT.search(body)
        if not acct_m:
            return None
        last4 = acct_m.group(1)

        merchant, txn_ref, is_transfer = "UPI transfer", None, True
        info_m = _TXN_INFO_LABEL.search(body)
        if info_m:
            upi = parse_upi_info(info_m.group(1))
            if upi is not None:
                merchant, txn_ref, is_transfer = upi

        return ParsedTransaction(
            bank="AXIS", account_type="bank", last4=last4, amount=amount,
            direction="debit", merchant=merchant, occurred_at=received_at,
            is_transfer=is_transfer, currency=currency, original_amount=original_amount,
            txn_ref=txn_ref, txn_time=parse_dt(body),
        )

    # --- Email CC spend "Thank you for using your credit card" ----------
    def _parse_email_cc_thankyou(self, body: str, received_at: datetime) -> ParsedTransaction | None:
        m = _CC_THANKYOU.search(body)
        if not m:
            return None
        last4 = m.group(1)
        native_amount = to_amount(m.group(3))
        native_currency = normalize_currency(m.group(2))
        amount, currency, original_amount = to_inr_fields(native_amount, native_currency)
        merchant = m.group(4).strip()

        return ParsedTransaction(
            bank="AXIS", account_type="card", last4=last4, amount=amount,
            direction="debit", merchant=merchant, occurred_at=received_at,
            is_transfer=False, currency=currency, original_amount=original_amount,
            txn_ref=None, txn_time=parse_dt(body),
        )

    # --- Email savings NEFT credit --------------------------------------
    def _parse_email_neft_credit(self, body: str, received_at: datetime) -> ParsedTransaction | None:
        m = _EMAIL_CREDITED_WITH.search(body)
        if not m:
            return None
        last4 = m.group(1)
        native_amount = to_amount(m.group(3))
        native_currency = normalize_currency(m.group(2))
        amount, currency, original_amount = to_inr_fields(native_amount, native_currency)

        merchant, txn_ref, is_transfer = "UNKNOWN", None, True
        upi = parse_upi_info(body)
        if upi is not None:
            merchant, txn_ref, is_transfer = upi

        return ParsedTransaction(
            bank="AXIS", account_type="bank", last4=last4, amount=amount,
            direction="credit", merchant=merchant, occurred_at=received_at,
            is_transfer=is_transfer, currency=currency, original_amount=original_amount,
            txn_ref=txn_ref, txn_time=parse_dt(body),
        )

    def _parse_labeled(self, body: str, received_at: datetime) -> ParsedTransaction | None:
        """Axis email alert: 'Transaction Amount: <cur> <amt> Merchant Name: <m> ... Card No. XX####'."""
        amount_m = _TXN_AMOUNT_LABEL.search(body)
        if not amount_m:
            return None
        native_amount = to_amount(amount_m.group(2))
        native_currency = normalize_currency(amount_m.group(1))
        amount, currency, original_amount = to_inr_fields(native_amount, native_currency)

        card_m = _CARD.search(body)
        if not card_m:
            return None
        last4 = card_m.group(1)

        merch_m = _MERCHANT_LABEL.search(body)
        merchant = merch_m.group(1).strip() if merch_m else "UNKNOWN"

        return ParsedTransaction(
            bank="AXIS", account_type="card", last4=last4, amount=amount,
            direction="debit", merchant=merchant, occurred_at=received_at,
            balance_after=search_amount(_AVAIL_LIMIT, body),
            is_transfer=False,
            currency=currency, original_amount=original_amount,
            txn_ref=None, txn_time=parse_dt(body),
        )

    def _parse_multiline(self, lines: list[str], received_at: datetime) -> ParsedTransaction | None:
        # Amount: first standalone "INR <amount>" line (these are NOT the
        # "Avl Lmt INR ..." line, which has trailing text).
        amount: float | None = None
        currency = "INR"
        original_amount: float | None = None
        amount_idx: int | None = None
        for i, line in enumerate(lines):
            m = _STANDALONE_AMOUNT.match(line)
            if m:
                native_amount = to_amount(m.group(2))
                native_currency = normalize_currency(m.group(1))
                amount, currency, original_amount = to_inr_fields(
                    native_amount, native_currency
                )
                amount_idx = i
                break
        if amount is None:
            return None

        body = "\n".join(lines)
        is_credit = "credited" in body.lower()
        direction = "credit" if is_credit else "debit"

        card_m = _CARD.search(body)
        acct_m = _ACCT.search(body)
        if card_m:
            last4, account_type = card_m.group(1), "card"
        elif acct_m:
            last4, account_type = acct_m.group(1), "bank"
        else:
            return None

        # Merchant: first non-empty line after the date/time line, which
        # immediately follows the amount line.
        merchant = "UNKNOWN"
        for line in lines[(amount_idx or 0) + 2:]:
            stripped = line.strip()
            if stripped and not _BAL.search(stripped):
                merchant = stripped
                break

        return ParsedTransaction(
            bank="AXIS", account_type=account_type, last4=last4, amount=amount,
            direction=direction, merchant=merchant, occurred_at=received_at,
            balance_after=search_amount(_BAL, body),
            # Account NEFT/IMPS/RTGS credits are transfers; card spends are not.
            is_transfer=is_credit,
            currency=currency, original_amount=original_amount,
            txn_time=parse_dt(body),
        )

    def _parse_single_line(self, body: str, received_at: datetime) -> ParsedTransaction | None:
        is_credit = "credited" in body.lower()
        direction = "credit" if is_credit else "debit"

        anchor = _SINGLE_CREDIT if is_credit else _SINGLE_SPEND
        native_amount, native_currency = extract_amount_currency(body, anchor)
        if native_amount is None:
            return None
        amount, currency, original_amount = to_inr_fields(native_amount, native_currency)

        card_m = _CARD.search(body)
        acct_m = _ACCT.search(body)
        if card_m:
            last4, account_type = card_m.group(1), "card"
        elif acct_m:
            last4, account_type = acct_m.group(1), "bank"
        else:
            return None

        txn_ref = None
        if is_credit:
            info_m = _INFO.search(body)
            merchant = info_m.group(1) if info_m else "UNKNOWN"
            upi = parse_upi_info(body)
            if upi is not None:
                merchant, txn_ref, _ = upi
        else:
            at_m = _AT.search(body)
            merchant = at_m.group(1).strip() if at_m else "UNKNOWN"

        return ParsedTransaction(
            bank="AXIS", account_type=account_type, last4=last4, amount=amount,
            direction=direction, merchant=merchant, occurred_at=received_at,
            balance_after=search_amount(_BAL, body),
            # Account NEFT/IMPS/RTGS credits are transfers; card spends are not.
            is_transfer=is_credit,
            currency=currency, original_amount=original_amount,
            txn_ref=txn_ref, txn_time=parse_dt(body),
        )
