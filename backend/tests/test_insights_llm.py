"""Tests for the pre-compute payload + grounding guard."""
from datetime import datetime

from app.enrichment import insights_llm as L


def _rows():
    return [
        {"merchant": "ZOMATO", "amount": 300.0, "occurred_at": datetime(2026, 5, 4), "category": "food_dining"},   # Mon
        {"merchant": "ZOMATO", "amount": 200.0, "occurred_at": datetime(2026, 5, 3), "category": "food_dining"},   # Sun
        {"merchant": "Blinkit", "amount": 500.0, "occurred_at": datetime(2026, 5, 2), "category": "groceries"},    # Sat
        {"merchant": "CRED Club", "amount": 9000.0, "occurred_at": datetime(2026, 5, 3), "category": "bills_utilities"},
    ]


def test_money_movement_split_out():
    p = L.build_payload(_rows(), "2026-05")
    # CRED Club is money-movement, excluded from consumption (300+200+500=1000)
    assert p["consumption_total_display"] == "Rs1,000"
    assert "9,000" in p["money_movement_total_display_NOT_SPENDING"]
    assert p["consumption_txn_count_display"] == "3 spending transactions"


def test_comparison_verdict_is_precomputed_correctly():
    p = L.build_payload(_rows(), "2026-05")
    # weekday total (ZOMATO 200 on Wed 5/6) vs weekend (300+500 on Sat/Sun) -> weekend higher
    assert "Weekend spending" in p["spend_comparison_display"]
    assert "higher than weekday" in p["spend_comparison_display"]


def test_guard_strips_ungrounded_number():
    p = L.build_payload(_rows(), "2026-05")
    text = ("You spent Rs1,000 this month.\n"
            "You could save Rs4,711 by cutting back.\n"   # 4711 not in payload -> strip
            "Blinkit is a frequent merchant.")
    clean, stripped = L.ground_guard(text, p)
    assert "Rs1,000" in clean
    assert "Blinkit is a frequent merchant." in clean
    assert any("4,711" in s for s in stripped)
    assert "4,711" not in clean


def test_guard_keeps_reformatted_grounded_number():
    p = {"a_display": "Rs35,518", "c_display": "5 transactions"}
    # reformatted (commas/words differ) but digits match -> kept
    clean, stripped = L.ground_guard("ANTHROPIC was 35518 across 5 transactions.", p)
    assert stripped == []
    assert "35518" in clean


def test_split_sections_hash_headers():
    text = ("### Spending Patterns\nYou spent a lot.\n"
            "### How To Save More\nTrim subscriptions.\n"
            "### Money Radar\nWatch CRED.")
    s = L.split_sections(text)
    assert s["spending_patterns"] == "You spent a lot."
    assert s["how_to_save"] == "Trim subscriptions."
    assert s["money_radar"] == "Watch CRED."


def test_split_sections_bold_headers():
    text = ("**Spending Patterns:**  \nLine A.\n\n"
            "**How To Save More:**  \nLine B.\n\n"
            "**Money Radar:**  \nLine C.")
    s = L.split_sections(text)
    assert s["spending_patterns"] == "Line A."
    assert s["how_to_save"] == "Line B."
    assert s["money_radar"] == "Line C."


def test_split_sections_missing_header_is_empty():
    text = "### Spending Patterns\nOnly this one."
    s = L.split_sections(text)
    assert s["spending_patterns"] == "Only this one."
    assert s["how_to_save"] == ""
    assert s["money_radar"] == ""


def test_split_sections_no_headers_all_empty():
    assert L.split_sections("just prose, no headers") == {
        "spending_patterns": "", "how_to_save": "", "money_radar": ""}
