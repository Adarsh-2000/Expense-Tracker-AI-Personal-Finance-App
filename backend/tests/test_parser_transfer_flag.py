from datetime import datetime

from app.parsing.banks.axis import AxisParser
from app.parsing.banks.bob import BobParser


def test_bob_transfer_debit_sets_is_transfer():
    parser = BobParser()
    body = (
        "Rs.20 transferred from A/c ...2404 to:UPI/309437357450. "
        "Total Bal:Rs.260756.42CR. Avlbl Amt:Rs.246506.42"
        "(04-04-2023 22:34:41) - Bank of Baroda"
    )
    pt = parser.parse("BOBTXN", body, datetime(2023, 4, 4, 22, 34, 41))
    assert pt.is_transfer is True


def test_bob_credit_sets_is_transfer():
    parser = BobParser()
    body = (
        "Rs.1568 Credited to A/c ...2404 thru UPI/290163334806 by "
        "abhi.prabal_ibl. Total Bal:Rs.655923.76CR. Avlbl Amt:Rs.655973.76"
        "(30-09-2024 17:53:27) - Bank of Baroda"
    )
    pt = parser.parse("BOBTXN", body, datetime(2024, 9, 30, 17, 53, 27))
    assert pt.is_transfer is True


def test_bob_upi_debit_sets_is_transfer():
    parser = BobParser()
    body = (
        "Rs 149.00 debited from A/C XXXXXX2404 and credited to "
        "HOTSTARONLINE@ybl UPI Ref:311720467801. Not you? Call 18005700 -BOB"
    )
    pt = parser.parse("BOBSMS", body, datetime(2024, 1, 1, 0, 0))
    assert pt.is_transfer is True


def test_axis_card_spend_not_transfer():
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
    pt = parser.parse("AXISBK", body, datetime(2024, 3, 30, 13, 19, 41))
    assert pt.is_transfer is False


def test_axis_account_neft_credit_sets_is_transfer():
    parser = AxisParser()
    body = (
        "INR 229911.00 credited to A/c no. XX4400 on 03-03-25 at 20:02:28 IST. "
        "Info - NEFT/CITIN25530239996/SILV. Chk Bal https://example - Axis Bank"
    )
    pt = parser.parse("AX-AXISBK", body, datetime(2025, 3, 3, 20, 2, 28))
    assert pt.is_transfer is True
