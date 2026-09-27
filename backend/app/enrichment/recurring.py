from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from statistics import mean

from app.parsing.types import normalize_merchant


@dataclass
class DetectedSeries:
    merchant: str
    expected_amount: float
    cadence_days: int
    last_seen_at: datetime
    next_expected_at: datetime


def detect_recurring(charges: list[tuple[str, float, datetime]]) -> list[DetectedSeries]:
    groups: dict[str, list[tuple[float, datetime]]] = {}
    labels: dict[str, str] = {}
    for merchant, amount, when in charges:
        key = normalize_merchant(merchant)
        groups.setdefault(key, []).append((amount, when))
        labels[key] = merchant

    series: list[DetectedSeries] = []
    for key, items in groups.items():
        if len(items) < 3:
            continue
        items.sort(key=lambda x: x[1])
        gaps = [(items[i][1] - items[i - 1][1]).days for i in range(1, len(items))]
        cadence = round(mean(gaps))
        if not (25 <= cadence <= 35):
            continue
        last = items[-1][1]
        series.append(
            DetectedSeries(
                merchant=labels[key],
                expected_amount=round(mean(a for a, _ in items), 2),
                cadence_days=cadence,
                last_seen_at=last,
                next_expected_at=last + timedelta(days=cadence),
            )
        )
    return series
