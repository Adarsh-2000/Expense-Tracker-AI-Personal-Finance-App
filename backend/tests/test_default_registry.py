from datetime import datetime

import pytest

from app.parsing.registry import default_registry


@pytest.mark.parametrize(
    "sender,expected",
    [
        ("alerts@axis.bank.in", "AxisParser"),
        ("alerts@axisbank.com", "AxisParser"),
        ("Axis Bank Alerts", "AxisParser"),
        ("AX-AXISBK", "AxisParser"),
        ("noreply@idfcfirstbank.com", "IdfcParser"),
        ("IDFCFB", "IdfcParser"),
        ("BOBSMS", "BobParser"),
        ("BOBTXN", "BobParser"),
    ],
)
def test_sender_routes_to_exactly_one_parser(sender, expected):
    reg = default_registry()
    matched = [type(p).__name__ for p in reg._parsers if p.matches(sender, "")]
    assert matched == [expected]


def test_default_registry_parses_all_user_banks():
    reg = default_registry()
    axis = reg.parse(
        "AX-AXISBK",
        "INR 320 spent on AXIS BANK Credit Card no. XX1234 at UBER on "
        "03-06-2026. Avl Lmt INR 49230",
        datetime(2026, 6, 3),
    )
    idfc = reg.parse(
        "IDFCFB",
        "Transaction Successful! INR 71.52 spent on your IDFC FIRST Bank "
        "Credit Card XX0684 at UBER INDIA SYSTE PVT LTD on 27-AUG-2024 "
        "Available limit: INR 56961.18",
        datetime(2024, 8, 27),
    )
    bob = reg.parse(
        "BOBTXN",
        "Rs.1568 Credited to A/c ...2404 thru UPI/290163334806 by "
        "abhi.prabal_ibl. Total Bal:Rs.655923.76CR. Avlbl Amt:Rs.655973.76"
        "(30-09-2024 17:53:27) - Bank of Baroda",
        datetime(2024, 9, 30),
    )
    assert axis is not None and axis.bank == "AXIS"
    assert idfc is not None and idfc.bank == "IDFC"
    assert bob is not None and bob.bank == "BOB"
    assert axis.amount == 320.0
    assert idfc.amount == 71.52
    assert bob.amount == 1568.0
