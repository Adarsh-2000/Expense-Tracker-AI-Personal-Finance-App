from __future__ import annotations

from datetime import datetime


def month_range(month: str):
    y, m = int(month[:4]), int(month[5:7])
    start = datetime(y, m, 1)
    end = datetime(y + 1, 1, 1) if m == 12 else datetime(y, m + 1, 1)
    return start, end


def months_span(start: str, end: str) -> float:
    a = datetime.fromisoformat(start); b = datetime.fromisoformat(end)
    return max((b - a).days / 30.4, 0.1)
