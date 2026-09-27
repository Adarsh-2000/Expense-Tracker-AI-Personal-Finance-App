from datetime import datetime

from app.parsing.types import ParsedTransaction, normalize_merchant


def test_normalize_merchant_lowercases_and_strips():
    assert normalize_merchant("  SWIGGY*Order 123 ") == "swiggy order 123"


def test_parsed_transaction_fingerprint():
    pt = ParsedTransaction(
        bank="HDFC", account_type="card", last4="1234", amount=100.0,
        direction="debit", merchant="SWIGGY", occurred_at=datetime(2026, 6, 1),
    )
    assert pt.fingerprint() == "1234|100.0|debit|swiggy"


def test_parsed_transaction_currency_defaults_inr():
    pt = ParsedTransaction(
        bank="HDFC", account_type="card", last4="1234", amount=100.0,
        direction="debit", merchant="SWIGGY", occurred_at=datetime(2026, 6, 1),
    )
    assert pt.currency == "INR"
    assert pt.original_amount is None


def test_parsed_transaction_foreign_currency_fields():
    pt = ParsedTransaction(
        bank="IDFC", account_type="card", last4="0684", amount=2029.6,
        direction="debit", merchant="CLAUDE AI SUBSCRIPTION",
        occurred_at=datetime(2026, 5, 31), currency="USD", original_amount=23.60,
    )
    assert pt.currency == "USD"
    assert pt.original_amount == 23.60
    # fingerprint still uses the (INR-equivalent) amount
    assert pt.fingerprint() == "0684|2029.6|debit|claude ai subscription"


def test_parsed_transaction_balance_after_defaults_none_and_settable():
    pt = ParsedTransaction(
        bank="AXIS", account_type="bank", last4="5678", amount=50.0,
        direction="debit", merchant="X", occurred_at=datetime(2026, 6, 1),
    )
    assert pt.balance_after is None
    pt2 = ParsedTransaction(
        bank="AXIS", account_type="bank", last4="5678", amount=50.0,
        direction="debit", merchant="X", occurred_at=datetime(2026, 6, 1),
        balance_after=950.0,
    )
    assert pt2.balance_after == 950.0
