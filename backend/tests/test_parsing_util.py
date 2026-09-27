import re

from app.parsing._util import extract_amount_currency


def test_extract_amount_currency_inr():
    body = "Transaction Successful! INR 663.07 spent at Zomato"
    anchor = re.compile(r"%s\s+spent", re.IGNORECASE)
    amount, currency = extract_amount_currency(body, anchor)
    assert amount == 663.07
    assert currency == "INR"


def test_extract_amount_currency_usd():
    body = "Transaction Successful! USD 23.60 spent at CLAUDE AI SUBSCRIPTION"
    anchor = re.compile(r"%s\s+spent", re.IGNORECASE)
    amount, currency = extract_amount_currency(body, anchor)
    assert amount == 23.60
    assert currency == "USD"


def test_extract_amount_currency_rs_normalizes_to_inr():
    body = "Rs.149.00 debited from your account"
    anchor = re.compile(r"%s\s+debited", re.IGNORECASE)
    amount, currency = extract_amount_currency(body, anchor)
    assert amount == 149.00
    assert currency == "INR"


def test_extract_amount_currency_dollar_sign_normalizes_to_usd():
    body = "Spent $50.00 on your card"
    anchor = re.compile(r"Spent\s+%s", re.IGNORECASE)
    amount, currency = extract_amount_currency(body, anchor)
    assert amount == 50.0
    assert currency == "USD"


def test_extract_amount_currency_no_match_returns_none():
    body = "no money here"
    anchor = re.compile(r"%s\s+spent", re.IGNORECASE)
    assert extract_amount_currency(body, anchor) == (None, None)
