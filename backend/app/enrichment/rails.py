from __future__ import annotations

import re

# Payment rail labels.
CREDIT_CARD = "credit_card"
UPI = "upi"
DEBIT_CARD = "debit_card"
BANK_TRANSFER = "bank_transfer"
OTHER = "other"

RAIL_LABELS = {
    CREDIT_CARD: "Credit Card",
    UPI: "UPI",
    DEBIT_CARD: "Debit Card",
    BANK_TRANSFER: "Bank Transfer",
    OTHER: "Other",
}

_VPA = re.compile(r"@[a-z]", re.IGNORECASE)          # a VPA handle like name@ybl
_UPI_TOKEN = re.compile(r"\bupi\b", re.IGNORECASE)
_NEFT = re.compile(r"\b(neft|imps|rtgs|ach)\b", re.IGNORECASE)
_ATM = re.compile(r"\b(atm|cash withdrawal|cwdr)\b", re.IGNORECASE)


def classify_rail(*, account_type: str, merchant: str | None) -> str:
    """Derive the payment rail for a transaction from its account type + merchant.

    - account_type == 'card'           -> credit_card (the card accounts we track
      are credit cards; debit happens on 'bank' accounts).
    - merchant has a VPA / 'UPI' token  -> upi
    - merchant names NEFT/IMPS/RTGS/ACH -> bank_transfer
    - bank-account ATM/cash withdrawal  -> debit_card
    - else                              -> other
    """
    if account_type == "card":
        return CREDIT_CARD
    m = merchant or ""
    if _NEFT.search(m):
        return BANK_TRANSFER
    if _ATM.search(m):
        return DEBIT_CARD
    if _VPA.search(m) or _UPI_TOKEN.search(m):
        return UPI
    # Bank-account debits in India are overwhelmingly UPI (P2P/P2M to a payee
    # name). The merchant is the cleaned payee, so there's no UPI token left —
    # default bank debits to UPI once NEFT/ATM are ruled out.
    if account_type == "bank":
        return UPI
    return OTHER
