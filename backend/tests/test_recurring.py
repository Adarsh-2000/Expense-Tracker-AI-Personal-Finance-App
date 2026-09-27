from datetime import datetime

from app.enrichment.recurring import detect_recurring


def test_detects_monthly_subscription():
    charges = [
        ("NETFLIX", 199.0, datetime(2026, 4, 1)),
        ("NETFLIX", 199.0, datetime(2026, 5, 1)),
        ("NETFLIX", 199.0, datetime(2026, 6, 1)),
    ]
    series = detect_recurring(charges)
    assert len(series) == 1
    assert series[0].merchant == "NETFLIX"
    assert 28 <= series[0].cadence_days <= 31


def test_ignores_one_off_charges():
    charges = [("RANDOM", 50.0, datetime(2026, 6, 1))]
    assert detect_recurring(charges) == []
