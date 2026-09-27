from datetime import datetime

from app.enrichment.insights_engine import (
    behavioral_nudges, detect_subscriptions, savings_rate_series, small_leaks,
)


def _r(merchant, amount, day, category="subscriptions"):
    return {"merchant": merchant, "amount": amount,
            "occurred_at": datetime(2026, 3 + day // 28, 1 + day % 28, 10),
            "category": category}


def test_detect_subscriptions_finds_stable_recurring():
    rows = [_r("Netflix", 649, 0), _r("Netflix", 649, 30), _r("Netflix", 649, 60),
            _r("Croma", 95000, 5)]  # one-off, not recurring
    subs = detect_subscriptions(rows, months_span=3)
    names = [s["merchant"] for s in subs]
    assert "Netflix" in names
    assert "Croma" not in names
    netflix = next(s for s in subs if s["merchant"] == "Netflix")
    assert netflix["count"] == 3
    assert netflix["monthly_amount"] == 649


def test_subscriptions_excludes_variable_amounts():
    rows = [_r("Swiggy", 100, 0), _r("Swiggy", 900, 30), _r("Swiggy", 50, 60)]
    assert all(s["merchant"] != "Swiggy" for s in detect_subscriptions(rows, 3))


def test_small_leaks_annualizes_frequent_small_spends():
    rows = [_r("Blinkit", 200, d, "groceries") for d in range(0, 60, 10)]  # 6 charges
    leaks = small_leaks(rows, months_span=3, min_count=5)
    assert len(leaks) == 1
    bl = leaks[0]
    assert bl["merchant"] == "Blinkit"
    assert bl["count"] == 6
    # total 1200 over 3 months -> 4800/yr
    assert bl["annual_pace"] == 4800.0


def test_small_leaks_ignores_big_ticket():
    rows = [_r("Croma", 95000, d) for d in range(0, 60, 10)]
    assert small_leaks(rows, 3, max_avg=1000.0) == []


def test_savings_rate_series():
    s = savings_rate_series([
        {"month": "2026-04", "income": 100000, "spend": 66000},
        {"month": "2026-05", "income": 0, "spend": 5000},
    ])
    assert s[0]["rate"] == 34.0
    assert s[0]["saved"] == 34000
    assert s[1]["rate"] is None


def test_behavioral_nudges_big_ticket_and_top_category():
    from datetime import datetime
    rows = [
        {"merchant": "Croma", "amount": 95000, "occurred_at": datetime(2026, 6, 2, 12), "category": "shopping"},
        {"merchant": "Zomato", "amount": 800, "occurred_at": datetime(2026, 6, 3, 20), "category": "food"},
        {"merchant": "ICCL", "amount": 50000, "occurred_at": datetime(2026, 6, 1, 9), "category": "investments"},  # excluded
    ]
    nudges = behavioral_nudges(rows, current_month="2026-06")
    types = {n["type"] for n in nudges}
    assert "big_ticket" in types          # Croma 95k
    assert "top_category" in types        # shopping
    # Investments excluded from consumption nudges.
    assert all("ICCL" not in n["title"] for n in nudges)


def test_behavioral_nudges_trend_down_is_positive():
    from datetime import datetime
    rows = (
        [{"merchant": "X", "amount": 40000, "occurred_at": datetime(2026, 4, 10), "category": "food"}]
        + [{"merchant": "Y", "amount": 40000, "occurred_at": datetime(2026, 5, 10), "category": "food"}]
        + [{"merchant": "Z", "amount": 5000, "occurred_at": datetime(2026, 6, 10), "category": "food"}]
    )
    nudges = behavioral_nudges(rows, current_month="2026-06")
    assert any(n["type"] == "trend_down" and n["severity"] == "positive" for n in nudges)


def test_subscriptions_active_only_drops_stale():
    # Two subs charged monthly Jan–Mar 2026. As of 1 Jun 2026, only one was charged
    # again recently (May); the other stopped in March → only the active one shows.
    def at(y, mo, d, merch, amt):
        return {"merchant": merch, "amount": amt,
                "occurred_at": datetime(y, mo, d, 10), "category": "subscriptions"}
    rows = [
        at(2026, 1, 5, "ACTIVE SUB", 199), at(2026, 3, 5, "ACTIVE SUB", 199),
        at(2026, 5, 20, "ACTIVE SUB", 199),
        at(2026, 1, 5, "STALE SUB", 299), at(2026, 2, 5, "STALE SUB", 299),
        at(2026, 3, 5, "STALE SUB", 299),  # last charge Mar — >45d before Jun 1
    ]
    subs = detect_subscriptions(rows, months_span=6, as_of=datetime(2026, 6, 1))
    names = [s["merchant"] for s in subs]
    assert "ACTIVE SUB" in names
    assert "STALE SUB" not in names


def test_subscriptions_no_as_of_keeps_all():
    # Backward compatible: without as_of, no recency filtering.
    def at(mo, merch):
        return {"merchant": merch, "amount": 100,
                "occurred_at": datetime(2026, mo, 5, 10), "category": "subscriptions"}
    rows = [at(1, "OLD"), at(2, "OLD"), at(3, "OLD")]
    assert any(s["merchant"] == "OLD" for s in detect_subscriptions(rows, 6))
