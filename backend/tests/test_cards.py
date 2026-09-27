from datetime import datetime

from app.enrichment.cards import compute_card_state


def _t(amount, day):
    return {"amount": amount, "occurred_at": datetime(2026, 6, day, 12, 0)}


def test_all_spend_unbilled_when_no_statement():
    s = compute_card_state(
        bank="AXIS", last4="9966",
        card_txns=[_t(1000, 1), _t(500, 2)],
        card_bills=[],
        today=datetime(2026, 6, 3),
    )
    assert s["unbilled"] == 1500.0
    assert s["unbilled_count"] == 2
    assert s["current_bill"] is None


def test_no_statement_windows_to_one_cycle():
    # No bill: only spend within the last `cycle_days` counts as unbilled.
    old = {"amount": 9999.0, "occurred_at": datetime(2026, 1, 1, 12, 0)}  # >38d ago
    recent = {"amount": 500.0, "occurred_at": datetime(2026, 6, 1, 12, 0)}
    s = compute_card_state(
        bank="IDFC", last4="2198", card_txns=[old, recent],
        card_bills=[], today=datetime(2026, 6, 3), cycle_days=38,
    )
    assert s["unbilled"] == 500.0  # old all-time spend excluded
    assert s["unbilled_count"] == 1


def test_unbilled_excludes_pre_statement_spend_and_shows_bill():
    # Statement generated 2026-06-02; spends on the 1st are billed, on the 3rd unbilled.
    bill = {"total_due": 102395.88, "due_date": "2026-06-21",
            "received_at": datetime(2026, 6, 2, 16, 0), "paid": False}
    s = compute_card_state(
        bank="AXIS", last4="9966",
        card_txns=[_t(50000, 1), _t(39099, 3)],
        card_bills=[bill],
        today=datetime(2026, 6, 3),
    )
    assert s["unbilled"] == 39099.0          # only post-statement spend
    assert s["current_bill"]["total_due"] == 102395.88
    assert s["current_bill"]["days_until_due"] == 18
    assert s["last_statement_date"] == "2026-06-02"


def test_paid_bill_has_no_outstanding():
    bill = {"total_due": 5000.0, "due_date": "2026-06-21",
            "received_at": datetime(2026, 6, 2), "paid": True}
    s = compute_card_state(
        bank="IDFC", last4="0684", card_txns=[_t(700, 3)],
        card_bills=[bill], today=datetime(2026, 6, 3),
    )
    assert s["current_bill"] is None
    assert s["unbilled"] == 700.0


def test_stale_overdue_statement_is_not_due():
    # A statement overdue by more than a full billing cycle, never paid, with no
    # newer statement, is treated as settled/superseded — not "due now" (regression:
    # a 67-day-overdue AXIS ••6978 statement was showing a phantom ₹3,389 due).
    bill = {"total_due": 3388.91, "due_date": "2026-04-01",
            "received_at": datetime(2026, 3, 20, 10, 0), "paid": False}
    s = compute_card_state(
        bank="AXIS", last4="6978",
        card_txns=[
            {"amount": 2000.0, "occurred_at": datetime(2026, 6, 5, 12, 0)},   # current cycle
            {"amount": 9999.0, "occurred_at": datetime(2026, 3, 1, 12, 0)},   # ancient, excluded
        ],
        card_bills=[bill], today=datetime(2026, 6, 7),
    )
    assert s["current_bill"] is None          # stale overdue → not surfaced as due
    assert s["unbilled"] == 2000.0            # windowed to the last cycle, not since the ancient statement
    assert s["unbilled_count"] == 1


def test_recently_overdue_bill_is_still_due():
    # A statement overdue by only ~13 days IS still genuinely due (don't over-hide).
    bill = {"total_due": 5000.0, "due_date": "2026-05-25",
            "received_at": datetime(2026, 5, 10, 10, 0), "paid": False}
    s = compute_card_state(
        bank="AXIS", last4="6978", card_txns=[], card_bills=[bill],
        today=datetime(2026, 6, 7),
    )
    assert s["current_bill"] is not None
    assert s["current_bill"]["total_due"] == 5000.0
    assert s["current_bill"]["days_until_due"] == -13


def test_latest_of_multiple_bills_is_the_statement_cutoff():
    bills = [
        {"total_due": 1000, "due_date": "2026-05-21", "received_at": datetime(2026, 5, 2), "paid": True},
        {"total_due": 2000, "due_date": "2026-06-21", "received_at": datetime(2026, 6, 2), "paid": False},
    ]
    s = compute_card_state(
        bank="AXIS", last4="9966",
        card_txns=[_t(300, 1), _t(400, 3)],  # 1st is pre-latest-statement, 3rd after
        card_bills=bills, today=datetime(2026, 6, 3),
    )
    assert s["unbilled"] == 400.0
    assert s["current_bill"]["total_due"] == 2000
