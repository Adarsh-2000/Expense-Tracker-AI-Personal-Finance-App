"""Tests for the rules->model cascade categorizer (16-cat taxonomy)."""
from app.enrichment.cascade_categorizer import CascadeCategorizer
from app.enrichment.taxonomy import CANONICAL, UNCATEGORIZED

c = CascadeCategorizer()


def test_rules_layer_emits_canonical_16():
    assert c.categorize("ZOMATO LIMITED") == "food_dining"
    assert c.categorize("ANTHROPIC") == "subscriptions"
    assert c.categorize("UBER INDIA") == "transport_fuel"
    assert c.categorize("Sharma General Store") == "groceries"   # store-fix


def test_vpa_and_transfer_flag_map_to_p2p():
    assert c.categorize("someone@okhdfcbank") == "transfers_p2p"      # bare VPA
    assert c.categorize("WANMEI KONYAK M", is_transfer=True) == "transfers_p2p"


def test_classify_exposes_source_and_confidence():
    res = c.classify("ZOMATO")
    assert res["source"] == "rules" and res["category"] == "food_dining"
    assert res["confidence"] == 0.97


def test_model_layer_runs_for_unknown_merchant():
    # no rule matches -> model layer decides; result is canonical or 'uncategorized'
    res = c.classify("Zndj Qwerty Pvt Ltd")
    assert res["category"] in set(CANONICAL) | {UNCATEGORIZED}
    assert res["source"] in {"model", "model_low_confidence", "none"}
