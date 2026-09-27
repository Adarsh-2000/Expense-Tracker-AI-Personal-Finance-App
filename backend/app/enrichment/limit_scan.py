"""Extract credit-card available limit ("Avl Limit: INR X") from transaction SMS.

Parallel to balance_scan (which intentionally skips Avl Limit). latest_* gives the
freshest available credit; max_* approximates the sanctioned limit (available peaks
when outstanding is ~0)."""
from __future__ import annotations

import re

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.entities import RawMessage

_LIMIT_RE = re.compile(r"Avl\.?\s*Limit:?\s*INR\s*([\d,]+\.?\d*)", re.IGNORECASE)


def _to_float(raw: str) -> float:
    return float(raw.replace(",", ""))


async def _rows_for(session: AsyncSession, last4: str):
    # Use a broad LIKE first for DB-level filtering, then filter in Python with a
    # tighter regex so "0000" doesn't match amounts like "980000.00".
    pattern = re.compile(
        rf"X{{2,}}{re.escape(last4)}|no\.\s*\w*{re.escape(last4)}",
        re.IGNORECASE,
    )
    rows = (await session.execute(
        select(RawMessage.received_at, RawMessage.body)
        .where(RawMessage.body.like(f"%{last4}%"))
        .order_by(RawMessage.received_at.desc())
    )).all()
    return [(received_at, body) for received_at, body in rows if pattern.search(body or "")]


async def latest_avl_limit_for(session: AsyncSession, *, last4: str) -> tuple[float, str] | None:
    """(available_limit, as_of_iso) from the newest SMS that mentions this card and
    carries an Avl Limit, or None."""
    if not last4:
        return None
    for received_at, body in await _rows_for(session, last4):
        m = _LIMIT_RE.search(body or "")
        if m:
            as_of = received_at.date().isoformat() if hasattr(received_at, "date") else str(received_at)[:10]
            return _to_float(m.group(1)), as_of
    return None


async def max_avl_limit_for(session: AsyncSession, *, last4: str) -> float | None:
    """Largest Avl Limit ever seen for this card ≈ sanctioned limit, or None."""
    if not last4:
        return None
    best: float | None = None
    for _received_at, body in await _rows_for(session, last4):
        m = _LIMIT_RE.search(body or "")
        if m:
            v = _to_float(m.group(1))
            if best is None or v > best:
                best = v
    return best
