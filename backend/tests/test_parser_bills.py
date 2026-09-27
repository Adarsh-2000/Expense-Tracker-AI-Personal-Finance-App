from datetime import datetime

from app.parsing.bills import parse_card_bill


IDFC_REMINDER = (
    "Dear Cardmember, Your payment for the FIRST Millennia credit card ending in "
    "XX0684 is coming up soon. Here are the details: "
    "• Minimum Amount Due: ₹195.00 "
    "• Total Amount Due: ₹9706.49 "
    "• Payment Due Date: 05 May, 2026 "
    "Please pay on time to avoid late fees."
)

AXIS_STATEMENT = (
    "Axis Bank Dear Customer, Please find enclosed your credit card statement for "
    "FEBRUARY 2026. "
    "Total Amount Due INR Minimum Amount Due (INR) Payment Due Date (DD-MM-YYYY) "
    "27040.74 Dr 9512 Dr 21/02/2026 "
    "Thank you for banking with us."
)

IDFC_STATEMENT_VARIANT = (
    "Dear Cardmember, your credit card ending with XXXX0684 statement is ready. "
    "Total Amount Due: ₹9706.49 "
    "Minimum Amount Due: ₹195.00 "
    "Payment Due Date: 05 May, 2026"
)

BOB_ACCOUNT_STATEMENT = (
    "Bank of Baroda e-Statement. Dear Customer, Your Account Statement for "
    "November is attached. Please find your account transactions enclosed."
)

MARKETING_EMAIL = (
    "Dear Cardmember, Get 10% cashback on your next purchase with your IDFC FIRST "
    "Bank Credit Card! Shop now and save big. Limited period offer."
)


def test_idfc_reminder_parses():
    bill = parse_card_bill(
        "alerts@idfcfirstbank.com", IDFC_REMINDER, datetime(2026, 4, 30, 9, 0)
    )
    assert bill is not None
    assert bill["bank"] == "IDFC"
    assert bill["last4"] == "0684"
    assert bill["total_due"] == 9706.49
    assert bill["min_due"] == 195.00
    assert bill["due_date"] == "2026-05-05"


def test_axis_statement_parses():
    bill = parse_card_bill(
        "cc.statements@axisbank.com", AXIS_STATEMENT, datetime(2026, 2, 1, 9, 0)
    )
    assert bill is not None
    assert bill["bank"] == "AXIS"
    assert bill["total_due"] == 27040.74
    assert bill["min_due"] == 9512.0
    assert bill["due_date"] == "2026-02-21"
    assert bill["statement_period"] == "FEBRUARY 2026"


def test_idfc_statement_variant_parses():
    bill = parse_card_bill(
        "statements@idfcfirstbank.com", IDFC_STATEMENT_VARIANT, datetime(2026, 4, 30, 9, 0)
    )
    assert bill is not None
    assert bill["bank"] == "IDFC"
    assert bill["last4"] == "0684"
    assert bill["total_due"] == 9706.49
    assert bill["min_due"] == 195.00
    assert bill["due_date"] == "2026-05-05"


def test_bob_account_statement_ignored():
    bill = parse_card_bill(
        "estatement@bankofbaroda.co.in", BOB_ACCOUNT_STATEMENT, datetime(2025, 12, 1, 9, 0)
    )
    assert bill is None


def test_marketing_email_ignored():
    bill = parse_card_bill(
        "offers@idfcfirstbank.com", MARKETING_EMAIL, datetime(2026, 4, 30, 9, 0)
    )
    assert bill is None


# IDFC eStatement SMS: different labels ("Total Due"/"Payment Due by"),
# dash dates, and "Credit Card XX####" last4. Was landing in needs_review.
IDFC_ESTATEMENT_SMS = (
    "The eStatement for your FIRST Millennia Credit Card XX0684 is here "
    "https://idfcfr.in/IDFCFB/q5q8ri To access eStatement use your date of "
    "birth in DDMM format Payment Due by 04-06-2026 Min Due: INR 911.00 "
    "Total Due: INR 45,535.07 Click to pay: https://idfcfr.in/IDFCFB/mq9vL5 IDFC FIRST Bank"
)


def test_idfc_estatement_sms_bill():
    bill = parse_card_bill("IDFCFB", IDFC_ESTATEMENT_SMS, datetime(2026, 5, 22, 8, 1))
    assert bill is not None
    assert bill["bank"] == "IDFC"
    assert bill["last4"] == "0684"
    assert bill["total_due"] == 45535.07
    assert bill["min_due"] == 911.00
    assert bill["due_date"] == "2026-06-04"
