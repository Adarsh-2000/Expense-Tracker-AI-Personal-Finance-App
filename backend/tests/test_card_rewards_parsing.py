from app.parsing.card_rewards import derive_statement_passwords


def test_axis_password_name_plus_ddmm():
    pwds = derive_statement_passwords(name="Harsh Khandelwal", dob="29-03-1998", last4="9966")
    assert "HARS2903" in pwds


def test_candidates_include_dob_and_card_variants():
    pwds = derive_statement_passwords(name="Harsh Khandelwal", dob="29-03-1998", last4="9966")
    assert "29031998" in pwds          # DDMMYYYY
    assert "HARS9966" in pwds          # name4 + last4
    assert all(p == p.upper() for p in pwds if p[:4].isalpha())


def test_missing_name_still_yields_dob_candidates():
    pwds = derive_statement_passwords(name=None, dob="29-03-1998", last4=None)
    assert "29031998" in pwds
    assert pwds


from app.data.reward_catalogs import catalog_for, points_to_inr


def test_catalog_known_programs():
    idfc = catalog_for("IDFC_FIRST")
    axis = catalog_for("AXIS_EDGE")
    assert idfc and axis
    assert idfc["display_name"]
    assert idfc["conversion_rate_inr_per_point"] > 0
    assert any(p["label"] for p in idfc["partners"])


def test_points_to_inr_rounds():
    assert points_to_inr(12400, "IDFC_FIRST") == 3100.0
    assert points_to_inr(100, "UNKNOWN") == 0.0


from app.parsing.card_rewards import extract_points_summary

AXIS_TEXT = """
EDGE REWARD POINTS SUMMARY
Opening Balance            5,000
Points Earned              4,100
Points Redeemed              200
Points Expired                 0
Closing Balance            8,900
Points expiring on 30-06-2026: 1,200
Statement Period: 01-05-2026 to 31-05-2026
"""

IDFC_TEXT = """
REWARD POINTS SUMMARY
Opening Points            12,100
Earned                       300
Redeemed                       0
Closing Points            12,400
1,250 points expiring on 18-06-2026
"""


def test_extract_axis_summary():
    s = extract_points_summary(AXIS_TEXT, "AXIS_EDGE")
    assert s["points_balance"] == 8900
    assert s["earned"] == 4100
    assert s["redeemed"] == 200
    assert s["expiring_points"] == 1200
    assert s["expiring_date"] == "2026-06-30"


def test_extract_idfc_summary():
    s = extract_points_summary(IDFC_TEXT, "IDFC_FIRST")
    assert s["points_balance"] == 12400
    assert s["earned"] == 300
    assert s["expiring_points"] == 1250
    assert s["expiring_date"] == "2026-06-18"


def test_extract_returns_none_without_balance():
    assert extract_points_summary("no rewards here", "AXIS_EDGE") is None


from app.parsing.card_rewards import parse_redemption_message, parse_voucher_email

REDEEM_SMS = ("Congrats! 1780 reward points redeemed from your IDFC FIRST Bank Credit "
              "Card XX0684 at MAKEMYTRIP INDIA PVT L . Available Reward Points: 0")

VOUCHER_EMAIL = ("Thank you for redeeming your eDGE Loyalty Reward Points for Amazon Prime "
                 "Instant Gift Code. Voucher Code PV8T-NVXS9S-YYR Voucher Denomination 1499 "
                 "Date of Expiry 1 Amazon Prime 07 Jun 2026")


def test_parse_redemption_extracts_points_and_card():
    ev = parse_redemption_message("sms", "IDFCFB", REDEEM_SMS, occurred_at="2026-05-20")
    assert ev["kind"] == "redemption"
    assert ev["points"] == 1780
    assert ev["last4"] == "0684"
    assert "MAKEMYTRIP" in ev["merchant"]
    assert ev["bank"] == "IDFC"


def test_parse_voucher_extracts_expiry():
    ev = parse_voucher_email(VOUCHER_EMAIL, occurred_at="2026-06-05")
    assert ev["kind"] == "voucher"
    assert ev["voucher_code"] == "PV8T-NVXS9S-YYR"
    assert ev["voucher_expiry"] == "2026-06-07"
    assert ev["bank"] == "AXIS"


def test_parse_non_reward_returns_none():
    assert parse_redemption_message("sms", "AXISBK", "Your OTP is 1234", occurred_at="2026-05-20") is None
