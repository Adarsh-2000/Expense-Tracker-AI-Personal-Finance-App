from __future__ import annotations

import re

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.entities import RawMessage

# Available-balance patterns per bank. Deliberately NOT matching credit-card
# "Avl Limit"/"Avl Lmt" lines — only savings available balance.
#   Axis SMS: "... Avl bal: INR 90307.32 ..."
#   BOB  SMS: "Avlbl Amt:Rs.491738.5"  /  "AvlBal:Rs496386.50"
_BAL_PATTERNS = {
    "AXIS": re.compile(r"Avl\.?\s*bal(?:ance)?\s*:?\s*INR\s*([\d,]+\.?\d*)", re.IGNORECASE),
    "BOB": re.compile(r"Avl(?:bl|Bal)\.?\s*(?:Amt)?\s*:?\s*Rs\.?\s*([\d,]+\.?\d*)", re.IGNORECASE),
}


def _to_float(raw: str) -> float:
    return float(raw.replace(",", ""))


async def latest_balance_for(session: AsyncSession, *, bank: str, last4: str) -> tuple[float, str] | None:
    """Return (balance, as_of_iso) of the most recent raw message that mentions
    this account's last4 and carries an available-balance figure, or None.

    Scans raw messages directly (newest first) so the figure is the freshest the
    bank ever sent, independent of whether the transaction itself parsed.
    """
    pat = _BAL_PATTERNS.get(bank.upper())
    if pat is None or not last4:
        return None
    rows = (await session.execute(
        select(RawMessage.received_at, RawMessage.body)
        .where(RawMessage.body.like(f"%{last4}%"))
        .order_by(RawMessage.received_at.desc())
    )).all()
    for received_at, body in rows:
        m = pat.search(body or "")
        if m:
            as_of = received_at.date().isoformat() if hasattr(received_at, "date") else str(received_at)[:10]
            return _to_float(m.group(1)), as_of
    return None
