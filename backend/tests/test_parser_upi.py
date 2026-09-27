from datetime import datetime

from app.parsing._util import clean_merchant, parse_dt, parse_upi_info


# --- parse_upi_info --------------------------------------------------------

def test_upi_p2m_merchant_payment_not_transfer():
    merchant, ref, is_transfer = parse_upi_info(
        "UPI/P2M/758363491265/IRCTC Web UPI Not you? SMS BLOCKUPI"
    )
    assert merchant == "IRCTC Web UPI"
    assert ref == "758363491265"
    assert is_transfer is False


def test_upi_p2a_person_transfer():
    merchant, ref, is_transfer = parse_upi_info(
        "UPI/P2A/751870061265/Mr BRAJESH KUMAR Not you? Call"
    )
    assert merchant == "Mr BRAJESH KUMAR"
    assert ref == "751870061265"
    assert is_transfer is True


def test_upi_ref_only_defaults_merchant():
    merchant, ref, is_transfer = parse_upi_info("Transaction Info: UPI/P2A/5347")
    assert merchant == "UPI transfer"
    assert ref == "5347"
    assert is_transfer is True


def test_upi_neft_info():
    merchant, ref, is_transfer = parse_upi_info("by NEFT/CITIN25649946/SILV")
    assert merchant == "NEFT"
    assert ref == "CITIN25649946"
    assert is_transfer is True


def test_upi_info_none_when_absent():
    assert parse_upi_info("no upi token here") is None


def test_upi_p2a_strips_security_boilerplate():
    # Axis savings-debit emails append "If this transaction was not initiated
    # by you...". The non-greedy capture used to stop only at "not", swallowing
    # "If this transaction was" into the merchant. Must stop at "If" now.
    merchant, ref, is_transfer = parse_upi_info(
        "Transaction Info: UPI/P2A/534748908575/WANMEI KONYAK M "
        "If this transaction was not initiated by you, call 18001234"
    )
    assert merchant == "WANMEI KONYAK M"
    assert ref == "534748908575"
    assert is_transfer is True


def test_clean_merchant_truncates_boilerplate():
    assert clean_merchant("CRED Club If this transaction was") == "CRED Club"
    assert clean_merchant("ICCL ZERODHA Not you? Call 1800") == "ICCL ZERODHA"
    assert clean_merchant("BLINKIT") == "BLINKIT"
    assert clean_merchant(None) is None


# --- parse_dt --------------------------------------------------------------

def test_parse_dt_dd_mm_yy_comma():
    assert parse_dt("06-05-25, 18:47:40") == datetime(2025, 5, 6, 18, 47, 40)


def test_parse_dt_dd_mm_yyyy_ist():
    assert parse_dt("on 01-09-2025 09:59:40 IST.") == datetime(2025, 9, 1, 9, 59, 40)


def test_parse_dt_dd_mm_yyyy_comma():
    assert parse_dt("02-06-2026, 14:42:29 IST") == datetime(2026, 6, 2, 14, 42, 29)


def test_parse_dt_yyyy_mm_dd_ampm():
    assert parse_dt("on Date 2025-04-05 03:16:56 PM by") == datetime(
        2025, 4, 5, 15, 16, 56
    )


def test_parse_dt_idfc_monthname_with_time():
    assert parse_dt("on 29-SEP-2024 at 08:18 PM Avbl") == datetime(
        2024, 9, 29, 20, 18
    )


def test_parse_dt_idfc_monthname_spaced_date_only():
    assert parse_dt("at Blinkit on 15 MAR 2026. Available") == datetime(2026, 3, 15)


def test_parse_dt_idfc_monthname_dashed_date_only():
    assert parse_dt("on 27-AUG-2024 Available") == datetime(2024, 8, 27)


def test_parse_dt_none():
    assert parse_dt("no date here") is None
