import pytest

from app.enrichment.categorizer import RuleCategorizer
from app.enrichment.default_rules import DEFAULT_RULES


def test_categorizer_matches_keyword():
    cat = RuleCategorizer({"food": ["swiggy", "zomato"], "shopping": ["amazon"]})
    assert cat.categorize("SWIGGY*Order") == "food"
    assert cat.categorize("AMAZON PAY") == "shopping"


def test_categorizer_returns_uncategorized_when_no_match():
    cat = RuleCategorizer({"food": ["swiggy"]})
    assert cat.categorize("RANDOM MERCHANT") == "uncategorized"


def test_categorizer_keyword_wins_over_transfer():
    cat = RuleCategorizer({"food": ["swiggy"]})
    # Keyword match wins even when is_transfer would otherwise apply.
    assert cat.categorize("SWIGGY", is_transfer=False) == "food"
    assert cat.categorize("SWIGGY", is_transfer=True) == "food"


def test_categorizer_transfer_fallback_when_no_keyword():
    cat = RuleCategorizer({"food": ["swiggy"]})
    assert cat.categorize("Q603498770 ybl", is_transfer=True) == "transfers"


def test_categorizer_uncategorized_when_not_transfer_and_no_keyword():
    cat = RuleCategorizer({"food": ["swiggy"]})
    assert cat.categorize("random merchant", is_transfer=False) == "uncategorized"


def test_categorizer_merchant_vpa_keyword_beats_transfer():
    cat = RuleCategorizer({"entertainment": ["hotstar"]})
    # hotstaronline@ybl is a merchant VPA -> keyword "hotstar" must win.
    assert cat.categorize("hotstaronline ybl", is_transfer=True) == "entertainment"


# === DEFAULT_RULES coverage for real merchant names ===

@pytest.mark.parametrize(
    "merchant,expected",
    [
        ("GROFERS IND", "groceries"),
        ("grofers", "groceries"),
        ("BLINKIT", "groceries"),
        ("BLINK COMMERCE PRIVA", "groceries"),
        ("IRCTC Web UPI", "travel"),
        ("ZOMATO", "food"),
        ("SWIGGY*Order", "food"),
        ("ANTHROPIC", "subscriptions"),
        ("CLAUDE AI SUBSCRIPTION", "subscriptions"),
        ("OPENAI *CHATGPT", "subscriptions"),
        ("CURSOR AI POWERED IDE", "subscriptions"),
        ("GITHUB INC", "subscriptions"),
        ("NETFLIX.COM", "subscriptions"),
        ("AMZN MKTP U", "shopping"),
        ("AMAZON PAY", "shopping"),
    ],
)
def test_default_rules_real_merchants(merchant, expected):
    cat = RuleCategorizer(DEFAULT_RULES)
    assert cat.categorize(merchant) == expected


def test_default_rules_paytm_not_a_spend_category():
    # PAYTM is a wallet/transfer rail, not a spend merchant: stays out of the
    # spend buckets (uncategorized as a plain debit, transfers when flagged).
    cat = RuleCategorizer(DEFAULT_RULES)
    assert cat.categorize("PAYTM", is_transfer=False) == "uncategorized"
    assert cat.categorize("PAYTM", is_transfer=True) == "transfers"
