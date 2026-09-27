from datetime import datetime

from app.parsing.banks.bob import BobParser


def test_bob_transfer_debit():
    parser = BobParser()
    body = (
        "Rs.20 transferred from A/c ...2404 to:UPI/309437357450. "
        "Total Bal:Rs.260756.42CR. Avlbl Amt:Rs.246506.42"
        "(04-04-2023 22:34:41) - Bank of Baroda"
    )
    assert parser.matches("BOBTXN", body)
    pt = parser.parse("BOBTXN", body, datetime(2023, 4, 4, 22, 34, 41))
    assert pt.bank == "BOB"
    assert pt.direction == "debit"
    assert pt.account_type == "bank"
    assert pt.last4 == "2404"
    assert pt.amount == 20.0
    assert "UPI" in pt.merchant
    assert pt.balance_after == 246506.42


def test_bob_credit():
    parser = BobParser()
    body = (
        "Rs.1568 Credited to A/c ...2404 thru UPI/290163334806 by "
        "abhi.prabal_ibl. Total Bal:Rs.655923.76CR. Avlbl Amt:Rs.655973.76"
        "(30-09-2024 17:53:27) - Bank of Baroda"
    )
    pt = parser.parse("BOBTXN", body, datetime(2024, 9, 30, 17, 53, 27))
    assert pt.direction == "credit"
    assert pt.account_type == "bank"
    assert pt.last4 == "2404"
    assert pt.amount == 1568.0
    assert "abhi.prabal_ibl" in pt.merchant
    assert pt.balance_after == 655973.76


def test_bob_upi_debit_bobsms():
    parser = BobParser()
    body = (
        "Rs 149.00 debited from A/C XXXXXX2404 and credited to "
        "HOTSTARONLINE@ybl UPI Ref:311720467801. Not you? Call 18005700 -BOB"
    )
    assert parser.matches("BOBSMS", body)
    pt = parser.parse("BOBSMS", body, datetime(2024, 1, 1, 0, 0))
    assert pt.direction == "debit"
    assert pt.account_type == "bank"
    assert pt.last4 == "2404"
    assert pt.amount == 149.00
    assert "HOTSTARONLINE@ybl" in pt.merchant
    assert pt.balance_after is None


def test_bob_upi_credit():
    parser = BobParser()
    body = (
        "Dear BOB UPI User, your account is credited INR 290.00 on Date "
        "2025-04-05 03:16:56 PM by UPI Ref No 183971000955 - BOB"
    )
    assert parser.matches("BOBSMS", body)
    pt = parser.parse("BOBSMS", body, datetime(2025, 4, 5, 12, 0))
    assert pt.bank == "BOB"
    assert pt.direction == "credit"
    assert pt.account_type == "bank"
    assert pt.last4 is None
    assert pt.amount == 290.00
    assert pt.merchant == "UPI credit"
    assert pt.txn_ref == "183971000955"
    assert pt.is_transfer is True
    assert pt.txn_time == datetime(2025, 4, 5, 15, 16, 56)


def test_bob_upi_debit_vpa_format():
    parser = BobParser()
    body = (
        "Your VPA sayhitoharshkhandelwal@ybl linked to your a/c no. "
        "XXXXXX2404 is debited for Rs. 50.0 and credited to VPA "
        "rapido.rzp@icici UPI Ref no. 218804905875. - Bank of Baroda"
    )
    pt = parser.parse("BOBSMS", body, datetime(2024, 1, 1, 0, 0))
    assert pt.direction == "debit"
    assert pt.account_type == "bank"
    assert pt.last4 == "2404"
    assert pt.amount == 50.0
    assert "rapido.rzp@icici" in pt.merchant
    assert pt.balance_after is None
