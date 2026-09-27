from datetime import datetime

from app.parsing.banks.axis import AxisParser


def test_axis_multiline_card_spend():
    parser = AxisParser()
    body = (
        "Spent\n"
        "Card no. XX9966\n"
        "INR 257.53\n"
        "30-03-24 13:19:41\n"
        "ZOMATO\n"
        "Avl Lmt INR 20144.04\n"
        "SMS BLOCK 9966 to 919951860002, if not you - Axis Bank"
    )
    assert parser.matches("AXISBK", body)
    pt = parser.parse("AXISBK", body, datetime(2024, 3, 30, 13, 19, 41))
    assert pt.bank == "AXIS"
    assert pt.direction == "debit"
    assert pt.account_type == "card"
    assert pt.last4 == "9966"
    assert pt.amount == 257.53
    assert "ZOMATO" in pt.merchant
    assert pt.balance_after == 20144.04


def test_axis_multiline_usd_card_spend():
    parser = AxisParser()
    body = (
        "Spent\n"
        "Card no. XX9966\n"
        "USD 47.35\n"
        "14-08-23 16:39:49\n"
        "AMZN MKTP U\n"
        "Avl Lmt INR 39448.15\n"
        "SMS BLOCK 9966 ... - Axis Bank"
    )
    assert parser.matches("AXISBK", body)
    pt = parser.parse("AXISBK", body, datetime(2023, 8, 14, 16, 39, 49))
    assert pt.bank == "AXIS"
    assert pt.direction == "debit"
    assert pt.account_type == "card"
    assert pt.last4 == "9966"
    assert pt.currency == "USD"
    assert pt.original_amount == 47.35
    assert pt.amount == round(47.35 * 86.0, 2)
    assert "AMZN" in pt.merchant
    assert pt.balance_after == 39448.15


def test_axis_multiline_inr_unchanged_currency_inr():
    parser = AxisParser()
    body = (
        "Spent\n"
        "Card no. XX9966\n"
        "INR 257.53\n"
        "30-03-24 13:19:41\n"
        "ZOMATO\n"
        "Avl Lmt INR 20144.04\n"
        "SMS BLOCK 9966 - Axis Bank"
    )
    pt = parser.parse("AXISBK", body, datetime(2024, 3, 30, 13, 19, 41))
    assert pt.currency == "INR"
    assert pt.original_amount is None
    assert pt.amount == 257.53


def test_axis_single_line_card_spend():
    parser = AxisParser()
    body = (
        "INR 320 spent on AXIS BANK Credit Card no. XX1234 at UBER on "
        "03-06-2026. Avl Lmt INR 49230"
    )
    assert parser.matches("AX-AXISBK", body)
    pt = parser.parse("AX-AXISBK", body, datetime(2026, 6, 3, 10, 0))
    assert pt.direction == "debit"
    assert pt.account_type == "card"
    assert pt.last4 == "1234"
    assert pt.amount == 320.0
    assert "UBER" in pt.merchant
    assert pt.balance_after == 49230.0


def test_axis_account_neft_credit():
    parser = AxisParser()
    body = (
        "INR 229911.00 credited to A/c no. XX4400 on 03-03-25 at 20:02:28 IST. "
        "Info - NEFT/CITIN25530239996/SILV. Chk Bal https://example - Axis Bank"
    )
    pt = parser.parse("AX-AXISBK", body, datetime(2025, 3, 3, 20, 2, 28))
    assert pt.direction == "credit"
    assert pt.account_type == "bank"
    assert pt.last4 == "4400"
    assert pt.amount == 229911.00
    assert "NEFT" in pt.merchant
    assert pt.balance_after is None


# === AXIS SMS — UPI account debit ===

def test_axis_sms_upi_p2m_merchant_debit():
    parser = AxisParser()
    body = (
        "INR 818.60 debited A/c no. XX4400 06-05-25, 18:47:40 "
        "UPI/P2M/758363491265/IRCTC Web UPI Not you? SMS BLOCKUPI Cust ID "
        "to 919951860002 Axis Bank"
    )
    assert parser.matches("AX-AXISBK", body)
    pt = parser.parse("AX-AXISBK", body, datetime(2025, 5, 6, 12, 0))
    assert pt.bank == "AXIS"
    assert pt.direction == "debit"
    assert pt.account_type == "bank"
    assert pt.last4 == "4400"
    assert pt.amount == 818.60
    assert pt.merchant == "IRCTC Web UPI"
    assert pt.txn_ref == "758363491265"
    assert pt.is_transfer is False
    assert pt.txn_time == datetime(2025, 5, 6, 18, 47, 40)


def test_axis_sms_upi_p2a_person_transfer_debit():
    parser = AxisParser()
    body = (
        "INR 82.00 debited A/c no. XX4400 06-05-25, 17:39:57 "
        "UPI/P2A/751870061265/Mr BRAJESH KUMAR Not you? SMS BLOCKUPI"
    )
    pt = parser.parse("AX-AXISBK", body, datetime(2025, 5, 6, 12, 0))
    assert pt.direction == "debit"
    assert pt.account_type == "bank"
    assert pt.last4 == "4400"
    assert pt.amount == 82.00
    assert pt.merchant == "Mr BRAJESH KUMAR"
    assert pt.txn_ref == "751870061265"
    assert pt.is_transfer is True
    assert pt.txn_time == datetime(2025, 5, 6, 17, 39, 57)


# === AXIS EMAIL — savings "Amount Debited" ===

def test_axis_email_amount_debited_p2a():
    parser = AxisParser()
    body = (
        "Dear Customer, Here's the summary of your transaction: "
        "Amount Debited: INR 1000.00 Account Number: XX4400 "
        "Date & Time: 13-12-25, 14:52:41 IST "
        "Transaction Info: UPI/P2A/534712345678/JOHN DOE Thank you."
    )
    pt = parser.parse("alerts@axisbank.com", body, datetime(2025, 12, 13, 12, 0))
    assert pt.direction == "debit"
    assert pt.account_type == "bank"
    assert pt.last4 == "4400"
    assert pt.amount == 1000.00
    assert pt.merchant == "JOHN DOE"
    assert pt.txn_ref == "534712345678"
    assert pt.is_transfer is True
    assert pt.txn_time == datetime(2025, 12, 13, 14, 52, 41)


def test_axis_email_amount_debited_ref_only_defaults_merchant():
    parser = AxisParser()
    body = (
        "Here's the summary of your transaction: "
        "Amount Debited: INR 250.00 Account Number: XX4400 "
        "Date & Time: 13-12-25, 14:52:41 IST "
        "Transaction Info: UPI/P2A/5347 "
    )
    pt = parser.parse("alerts@axisbank.com", body, datetime(2025, 12, 13, 12, 0))
    assert pt.amount == 250.00
    assert pt.merchant == "UPI transfer"
    assert pt.txn_ref == "5347"
    assert pt.is_transfer is True


# === AXIS EMAIL — credit-card spend "Thank you for using your credit card" ===

def test_axis_email_cc_thankyou_grofers():
    parser = AxisParser()
    body = (
        "Dear Customer, Thank you for using your credit card no. XX9966 for "
        "INR 376 at GROFERS IND on 01-09-2025 09:59:40 IST. The total credit "
        "limit is INR 50000."
    )
    pt = parser.parse("alerts@axisbank.com", body, datetime(2025, 9, 1, 12, 0))
    assert pt.direction == "debit"
    assert pt.account_type == "card"
    assert pt.last4 == "9966"
    assert pt.amount == 376.0
    assert pt.merchant == "GROFERS IND"
    assert pt.is_transfer is False
    assert pt.txn_time == datetime(2025, 9, 1, 9, 59, 40)


def test_axis_email_cc_thankyou_zomato():
    parser = AxisParser()
    body = (
        "Thank you for using your credit card no. XX6978 for INR 237.16 at "
        "ZOMATO on 31-08-2025 20:17:27 IST. The total credit limit ..."
    )
    pt = parser.parse("alerts@axisbank.com", body, datetime(2025, 8, 31, 12, 0))
    assert pt.last4 == "6978"
    assert pt.amount == 237.16
    assert pt.merchant == "ZOMATO"
    assert pt.txn_time == datetime(2025, 8, 31, 20, 17, 27)


# === AXIS EMAIL — labeled CC txn (keep working, extract datetime + currency) ===

def test_axis_email_labeled_usd_with_datetime():
    parser = AxisParser()
    body = (
        "Axis Bank Credit Card Transaction: Transaction Amount: USD 29.5 "
        "Merchant Name: ANTHROPIC Axis Bank Credit Card No. XX6978 "
        "Date & Time: 02-06-2026, 14:42:29 IST Available Limit*: INR 40000"
    )
    pt = parser.parse("alerts@axis.bank.in", body, datetime(2026, 6, 2, 12, 0))
    assert pt.account_type == "card"
    assert pt.last4 == "6978"
    assert pt.currency == "USD"
    assert pt.original_amount == 29.5
    assert pt.merchant == "ANTHROPIC"
    assert pt.is_transfer is False
    assert pt.txn_time == datetime(2026, 6, 2, 14, 42, 29)
    assert pt.balance_after == 40000.0


# === AXIS EMAIL — savings NEFT credit (email variant) ===

def test_axis_email_neft_credit():
    parser = AxisParser()
    body = (
        "Dear Customer, your A/c no. XX4400 has been credited with INR "
        "203530.00 on 03-11-2025 at 16:03:12 IST by NEFT/CITIN25649946 from "
        "ACME CORP. - Axis Bank"
    )
    pt = parser.parse("alerts@axisbank.com", body, datetime(2025, 11, 3, 12, 0))
    assert pt.direction == "credit"
    assert pt.account_type == "bank"
    assert pt.last4 == "4400"
    assert pt.amount == 203530.00
    assert pt.merchant == "NEFT"
    assert pt.txn_ref == "CITIN25649946"
    assert pt.is_transfer is True
    assert pt.txn_time == datetime(2025, 11, 3, 16, 3, 12)


def test_axis_email_debited_with_by_payee():
    # "debited with INR X ... by <payee>" — credit-card bill payment from savings.
    body = ("Dear Harsh Khandelwal, your A/c no. XX4400 has been debited with INR "
            "37027.41 on 03-06-2026 15:16:55 IST by CreditCard Payment XX 9966. "
            "To check your available balance, please click here.")
    from datetime import datetime
    p = AxisParser().parse("alerts@axis.bank.in", body, datetime(2026, 6, 3, 15, 25))
    assert p is not None
    assert p.bank == "AXIS" and p.last4 == "4400"
    assert p.amount == 37027.41
    assert p.direction == "debit"
    assert p.is_transfer is True
    assert "CreditCard Payment" in p.merchant
