from app.parsing.events.mandates import parse_mandate


def test_mandate_setup_simple():
    r = parse_mandate("AXISBK", "UPI mandate set up for Spotify India for INR 119.00. Not you? Call us")
    assert r == {"merchant": "Spotify India", "amount": 119.0, "action": "created"}


def test_mandate_created_with_dates():
    r = parse_mandate("AXISBK",
        "Your UPI ASPRESENTED mandate has been successfully created towards Google Play "
        "from 15-04-25 to 31-12-35 for INR 119.00 - Axis Bank")
    assert r["merchant"] == "Google Play"
    assert r["amount"] == 119.0
    assert r["action"] == "created"


def test_mandate_bob_on_merchant_for_amount():
    r = parse_mandate("BOBTXN",
        "You have successfully created a mandate on NETFLIX COM for a frequency of "
        "ASPRESENTED starting from 2025-04-15 12:00:00 AM for amount 59.00 -BOB")
    assert r["merchant"] == "NETFLIX COM"
    assert r["amount"] == 59.0


def test_mandate_revoked():
    r = parse_mandate("AXISBK", "Your UPI mandate has been successfully revoked towards Jiocinema for INR 29.00 - Axis Bank")
    assert r["action"] == "revoked"
    assert r["merchant"] == "Jiocinema"


def test_mandate_rs_with_paise():
    r = parse_mandate("BOBTXN", "Your UPI-Mandate is successfully created towards Garuda Construction for Rs. 14915.00. Funds are blocked")
    assert r["merchant"] == "Garuda Construction"
    assert r["amount"] == 14915.0


def test_non_mandate_returns_none():
    assert parse_mandate("AXISBK", "Rs 181.00 debited from A/C XX2404 to amazonpay") is None


from app.parsing.events.declines import parse_decline


def test_decline_cvv_with_amount():
    r = parse_decline("IDFCFB", "Txn of INR 6802.00 on IDFC FIRST Bank Credit Card XX0684 at PAYTM APP declined due to Incorrect CVV. Retry")
    assert r["bank"] == "IDFC"
    assert r["last4"] == "0684"
    assert r["amount"] == 6802.0
    assert "incorrect cvv" in r["reason"].lower()


def test_decline_shared_limit():
    r = parse_decline("AXISBK", "Txn on Axis Bank Credit Card XX9966 is declined as you have exhausted shared limit across all your cards. Pay")
    assert r["bank"] == "AXIS"
    assert r["last4"] == "9966"
    assert "shared limit" in r["reason"].lower()


def test_decline_blocked_debit_card():
    r = parse_decline("AXISBK", "Your transaction was declined as your debit card ending with 4145 is blocked. Pl contact your base branch")
    assert r["last4"] == "4145"
    assert "blocked" in r["reason"].lower()


def test_non_decline_returns_none():
    assert parse_decline("AXISBK", "Statement for your Axis Bank Credit Card XX9966 of INR 4265 has been generated") is None


def test_decline_bob_sender_detected():
    r = parse_decline("BOBTXN", "Txn of INR 1500.00 on your Bank of Baroda Debit Card XX2404 declined due to insufficient balance.")
    assert r["bank"] == "BOB"


from app.parsing.events.refunds import parse_refund


def test_refund_reversal_card():
    r = parse_refund("IDFCFB", "Txn of INR 71.52 reversed on your IDFC FIRST Bank Credit Card XX0684 at UBER INDIA SYSTE PVT LTD on 27-AUG-2024")
    assert r["kind"] == "reversal"
    assert r["amount"] == 71.52
    assert r["last4"] == "0684"


def test_refund_explicit():
    r = parse_refund("IDFCFB", "Dear Cardmember, Transaction of INR 336.75 made at on 10-05-2026 has been refunded to your IDFC FIRST Bank Credit Card ending XX0684")
    assert r["kind"] == "refund"
    assert r["amount"] == 336.75


def test_refund_credit_balance():
    r = parse_refund("AXISBK", "You have a credit balance of INR 20430.6/- on your Axis Bank Credit Card no. XX6978. Call to place your refund")
    assert r["kind"] == "credit_balance"
    assert r["amount"] == 20430.6
    assert r["last4"] == "6978"


def test_non_refund_returns_none():
    assert parse_refund("AXISBK", "Rs 500 debited from your account") is None


from app.parsing.events.emi import parse_emi


def test_emi_conversion_confirmed():
    r = parse_emi("IDFCFB", "Your purchase of INR 24000.00 on IDFC FIRST Bank Credit Card XX0684 has been converted to EMI of 6 months")
    assert r["amount"] == 24000.0
    assert r["tenure_months"] == 6


def test_emi_promo_ignored():
    # Promotional "convert now" offers are NOT conversions — must return None.
    assert parse_emi("IDFCFB", "Break down your IDFC FIRST Bank Credit Card transactions into easy EMIs. Convert now & get cashback") is None


def test_refund_no_amount_does_not_crash():
    # Real-world: a fraud-awareness email mentions "refund" and contains "Rs," with no real amount.
    body = ("Dear Customer, Fraudsters may ask you to share OTP to process a refund. "
            "Never share Rs, card or OTP details. - IDFC FIRST Bank")
    assert parse_refund("IDFCFB", body) is None   # must not raise ValueError


def test_decline_no_amount_does_not_crash():
    body = "Your transaction was declined. For help on a refund of Rs, contact the bank."
    # Should not raise; amount simply absent.
    r = parse_decline("AXISBK", body)
    assert r is None or r["amount"] is None
