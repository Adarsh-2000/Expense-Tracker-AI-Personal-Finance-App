"""Per-credit-card utilization = used / sanctioned-limit.

Two ways to derive `used`, so utilization works for EVERY card:
  1. Cards whose SMS carries "Avl Limit" (e.g. Axis): used = total - available,
     where available is the latest Avl Limit and total is the credit_limit override
     else the max-observed Avl Limit heuristic.
  2. Cards without Avl Limit (e.g. IDFC) but WITH a credit_limit override: used =
     current outstanding (unbilled + billed) from the card's bills/transactions.
Cards with neither a limit nor any usage signal → 'unknown'."""
from __future__ import annotations

from sqlalchemy.ext.asyncio import AsyncSession


async def _card_outstanding(session: AsyncSession, account) -> float | None:
    """Current amount owed on this card = live unbilled spend + latest unpaid bill,
    via compute_card_state (the same source the Bills screen uses). None if it
    cannot be computed."""
    from datetime import datetime

    from sqlalchemy import select

    from app.entities import Transaction
    from app.enrichment.cards import compute_card_state
    from app.repositories import AccountRepository, CardBillRepository

    now = datetime.now()
    txn_rows = (await session.execute(
        select(Transaction.amount, Transaction.occurred_at).where(
            Transaction.account_id == account.id, Transaction.direction == "debit")
    )).all()
    card_txns = [{"amount": float(amt), "occurred_at": ts} for amt, ts in txn_rows]

    all_accts = await AccountRepository(session).list_all()
    sole = len([a for a in all_accts if a.type == "card" and a.bank == account.bank]) == 1
    all_bills = await CardBillRepository(session).list_all()
    matched = [
        {"total_due": b.total_due, "due_date": b.due_date,
         "received_at": b.received_at, "paid": b.paid}
        for b in all_bills
        if b.bank == account.bank and (
            b.last4 == account.last4 or (not (b.last4 or "").strip() and sole))
    ]
    state = compute_card_state(
        bank=account.bank, last4=account.last4, card_txns=card_txns,
        card_bills=matched, today=now)
    outstanding = state.get("unbilled", 0.0)
    if state.get("current_bill"):
        outstanding += state["current_bill"]["total_due"]
    return round(outstanding, 2)


def _unknown(account, total, available, as_of) -> dict:
    return {
        "account_id": account.id, "bank": account.bank, "last4": account.last4,
        "total_limit": total or 0.0, "available": available or 0.0,
        "used": 0.0, "utilization_pct": 0.0, "as_of": as_of, "source": "unknown",
    }


async def card_utilization(session: AsyncSession) -> list[dict]:
    from app.enrichment.limit_scan import latest_avl_limit_for, max_avl_limit_for
    from app.repositories import AccountRepository

    accounts = [a for a in await AccountRepository(session).list_all() if a.type == "card"]
    out: list[dict] = []
    for a in accounts:
        last4 = a.last4 or ""
        latest = await latest_avl_limit_for(session, last4=last4)
        available = latest[0] if latest else None
        as_of = latest[1] if latest else None

        if a.credit_limit is not None:
            total = a.credit_limit
            source = "manual"
        else:
            total = await max_avl_limit_for(session, last4=last4)
            source = "observed" if total is not None else "unknown"

        if total is None or total <= 0:
            out.append(_unknown(a, total, available, as_of))
            continue

        if available is not None:
            # Avl-Limit path (Axis): used = limit - available.
            used = round(total - available, 2)
        else:
            # No Avl Limit but a limit is set (e.g. IDFC + manual override): derive
            # used from the card's outstanding (unbilled + billed).
            outstanding = await _card_outstanding(session, a)
            if outstanding is None:
                out.append(_unknown(a, total, available, as_of))
                continue
            used = outstanding
            available = round(total - used, 2)

        used = max(used, 0.0)
        pct = round(used / total * 100.0, 1)
        out.append({
            "account_id": a.id, "bank": a.bank, "last4": a.last4,
            "total_limit": round(total, 2), "available": round(available, 2),
            "used": round(used, 2), "utilization_pct": pct, "as_of": as_of,
            "source": source,
        })
    out.sort(key=lambda c: -c["utilization_pct"])
    return out
