"""Each item-level notification carries an `anchor` (the target item's id) so the
Android app can deep-link and scroll to the specific item. Screen-level
notifications (digest/goal/spending) intentionally have no anchor."""
from datetime import date
from types import SimpleNamespace

from app.services.notifications import (
    _bill_notifications,
    _budget_notifications,
    _money_radar_notifications,
    _reward_notifications,
)


def test_bill_anchor_is_last4():
    bill = SimpleNamespace(bank="HDFC", last4="1234", due_date="2026-06-07",
                           total_due=5000.0)
    [item] = _bill_notifications([bill], date(2026, 6, 5))
    assert item["type"] == "bill"
    assert item["anchor"] == "1234"


def test_bill_anchor_none_when_no_last4():
    bill = SimpleNamespace(bank="HDFC", last4=None, due_date="2026-06-07",
                           total_due=5000.0)
    [item] = _bill_notifications([bill], date(2026, 6, 5))
    assert item["anchor"] is None


def test_budget_anchor_is_category():
    status = SimpleNamespace(category="dining", assigned=1000.0, spent=1500.0,
                             projected=1600.0, status="exceeded",
                             pace_status="ok")
    [item] = _budget_notifications([status], "2026-06")
    assert item["type"] == "budget"
    assert item["anchor"] == "dining"


def test_money_radar_anchor_is_dedup_key():
    finding = SimpleNamespace(status="new", severity="security",
                              dedup_key="mandate:spotify:119",
                              title="New mandate", detail="Spotify mandate")
    [item] = _money_radar_notifications([finding])
    assert item["type"] == "money_radar"
    assert item["anchor"] == "mandate:spotify:119"


def test_reward_anchor_is_last4():
    card = SimpleNamespace(bank="AXIS", last4="9876", expiring_points=5000,
                           expiring_date="2026-06-10")
    [item] = _reward_notifications([card], [], date(2026, 6, 5))
    assert item["type"] == "reward"
    assert item["anchor"] == "9876"
