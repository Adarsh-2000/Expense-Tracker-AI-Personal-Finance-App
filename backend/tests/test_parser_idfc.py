from datetime import datetime

from app.parsing.banks.idfc import IdfcParser


def test_idfc_card_spend_ending_xx():
    parser = IdfcParser()
    body = (
        "Transaction Successful! INR 663.07 spent on your IDFC FIRST Bank "
        "Credit Card ending XX2198 at Zomato Ltd on 29-SEP-2024 at 08:18 PM "
        "Avbl Limit: INR 53542.67 Activate UPI: https://example"
    )
    assert parser.matches("IDFCFB", body)
    pt = parser.parse("IDFCFB", body, datetime(2024, 9, 29, 20, 18))
    assert pt.bank == "IDFC"
    assert pt.direction == "debit"
    assert pt.account_type == "card"
    assert pt.last4 == "2198"
    assert pt.amount == 663.07
    assert "Zomato Ltd" in pt.merchant
    assert pt.balance_after == 53542.67
    assert pt.txn_time == datetime(2024, 9, 29, 20, 18)


def test_idfc_card_spend_card_xx():
    parser = IdfcParser()
    body = (
        "Transaction Successful! INR 71.52 spent on your IDFC FIRST Bank "
        "Credit Card XX0684 at UBER INDIA SYSTE PVT LTD on 27-AUG-2024 "
        "Available limit: INR 56961.18 Activate UPI: https://example"
    )
    pt = parser.parse("IDFCFB", body, datetime(2024, 8, 27, 10, 0))
    assert pt.direction == "debit"
    assert pt.account_type == "card"
    assert pt.last4 == "0684"
    assert pt.amount == 71.52
    assert "UBER INDIA SYSTE PVT LTD" in pt.merchant
    assert pt.balance_after == 56961.18


def test_idfc_email_happy_shopping_blinkit():
    parser = IdfcParser()
    body = (
        "Dear Cardmember, Happy Shopping! INR 281.00 spent on your IDFC FIRST BANK "
        "Credit Card ending XX2198 at Blinkit on 15 MAR 2026. Available Limit: "
        "INR 30014.09 . For any dispute please contact us."
    )
    assert parser.matches("idfcfirstbank.com", body)
    pt = parser.parse("idfcfirstbank.com", body, datetime(2026, 3, 15, 12, 0))
    assert pt.bank == "IDFC"
    assert pt.direction == "debit"
    assert pt.account_type == "card"
    assert pt.last4 == "2198"
    assert pt.amount == 281.00
    assert pt.merchant == "Blinkit"
    assert pt.balance_after == 30014.09
    assert pt.txn_time == datetime(2026, 3, 15)


def test_idfc_email_happy_shopping_sultan():
    parser = IdfcParser()
    body = (
        "Dear Cardmember, Happy Shopping! INR 70.00 spent on your IDFC FIRST BANK "
        "Credit Card ending XX2198 at SULTAN on 06 MAR 2026. Available Limit: "
        "INR 33763.47 ..."
    )
    pt = parser.parse("idfcfirstbank.com", body, datetime(2026, 3, 6, 12, 0))
    assert pt.direction == "debit"
    assert pt.last4 == "2198"
    assert pt.amount == 70.00
    assert pt.merchant == "SULTAN"
    assert pt.balance_after == 33763.47


def test_idfc_email_usd_claude_subscription():
    parser = IdfcParser()
    body = (
        "Dear Cardmember, Transaction Successful! USD 23.60 spent on your IDFC "
        "FIRST BANK Credit Card ending XX0684 at CLAUDE AI SUBSCRIPTION on "
        "31 MAY 2026. Available Limit: INR 4965.37 ..."
    )
    pt = parser.parse("idfcfirstbank.com", body, datetime(2026, 5, 31, 12, 0))
    assert pt.direction == "debit"
    assert pt.account_type == "card"
    assert pt.last4 == "0684"
    assert pt.currency == "USD"
    assert pt.original_amount == 23.60
    assert pt.amount == round(23.60 * 86.0, 2)
    assert "CLAUDE" in pt.merchant
    # Available Limit is in INR; balance still captured in INR.
    assert pt.balance_after == 4965.37


def test_idfc_email_usd_claude_subscription_larger():
    parser = IdfcParser()
    body = (
        "Transaction Successful! USD 103.14 spent on your IDFC FIRST BANK "
        "Credit Card ending XX0684 at CLAUDE AI SUBSCRIPTION on 29 APR 2026. "
        "Available Limit: INR 35454.65 ..."
    )
    pt = parser.parse("idfcfirstbank.com", body, datetime(2026, 4, 29, 12, 0))
    assert pt.currency == "USD"
    assert pt.original_amount == 103.14
    assert pt.amount == round(103.14 * 86.0, 2)
    assert pt.last4 == "0684"
    assert "CLAUDE" in pt.merchant


def test_idfc_inr_spend_unchanged_currency_inr():
    parser = IdfcParser()
    body = (
        "Transaction Successful! INR 663.07 spent on your IDFC FIRST Bank "
        "Credit Card ending XX2198 at Zomato Ltd on 29-SEP-2024 at 08:18 PM "
        "Avbl Limit: INR 53542.67"
    )
    pt = parser.parse("IDFCFB", body, datetime(2024, 9, 29, 20, 18))
    assert pt.currency == "INR"
    assert pt.original_amount is None
    assert pt.amount == 663.07


def test_idfc_email_converted_into_emi_rejected():
    parser = IdfcParser()
    body = (
        "Dear Customer, Congratulations! Transaction of Rs.6911.00 on your IDFC "
        "FIRST Bank Credit Card ending with XX0684 has been successfully converted "
        "into EMI. Thank you for banking with us."
    )
    assert parser.matches("IDFCFB", body)
    assert parser.parse("IDFCFB", body, datetime(2026, 3, 6, 12, 0)) is None


def test_idfc_email_closure_notification_rejected():
    parser = IdfcParser()
    body = (
        "Credit Card Closure Notification. You have crossed 80% limit on your "
        "IDFC First Bank Credit Card. Please pay your dues."
    )
    assert parser.parse("IDFCFB", body, datetime(2026, 3, 6, 12, 0)) is None
