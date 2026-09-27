from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession

from app.deps import get_session

router = APIRouter()


@router.get("/bills")
async def list_bills(session: AsyncSession = Depends(get_session)):
    from datetime import date

    from app.repositories import CardBillRepository
    bills = await CardBillRepository(session).list_unpaid()
    today = date.today()
    out = []
    for b in bills:
        try:
            due = date.fromisoformat(b.due_date)
            days_until_due = (due - today).days
        except ValueError:
            days_until_due = None
        out.append({
            "id": b.id, "bank": b.bank, "last4": b.last4,
            "total_due": b.total_due, "min_due": b.min_due, "due_date": b.due_date,
            "statement_period": b.statement_period, "paid": b.paid,
            "days_until_due": days_until_due,
        })
    return out


@router.get("/cards")
async def cards(session: AsyncSession = Depends(get_session)):
    """Per credit-card CRED-style state: live UNBILLED spend (current open cycle)
    + the current OUTSTANDING bill (latest unpaid statement). Bills are matched to
    cards by (bank, last4); a bill with no last4 attaches to the bank's sole card.
    """
    from datetime import datetime

    from sqlalchemy import select

    from app.entities import Transaction
    from app.enrichment.cards import compute_card_state
    from app.repositories import AccountRepository, CardBillRepository

    now = datetime.now()
    accounts = await AccountRepository(session).list_all()
    cards_accts = [a for a in accounts if a.type == "card"]
    all_bills = await CardBillRepository(session).list_all()

    # Count cards per bank to allow attaching a last4-less bill to a lone card.
    cards_per_bank: dict[str, list] = {}
    for a in cards_accts:
        cards_per_bank.setdefault(a.bank, []).append(a)

    out = []
    total_unbilled = 0.0
    total_due = 0.0
    for a in cards_accts:
        # This card's debit transactions.
        txn_rows = (await session.execute(
            select(Transaction.amount, Transaction.occurred_at).where(
                Transaction.account_id == a.id, Transaction.direction == "debit")
        )).all()
        card_txns = [{"amount": float(amt), "occurred_at": ts} for amt, ts in txn_rows]

        # Bills for this card: exact last4 match, or bank-level when the bill has
        # no last4 AND this bank has exactly one card.
        sole = len(cards_per_bank.get(a.bank, [])) == 1
        matched = [
            {"total_due": b.total_due, "due_date": b.due_date,
             "received_at": b.received_at, "paid": b.paid}
            for b in all_bills
            if b.bank == a.bank and (
                b.last4 == a.last4 or (not (b.last4 or "").strip() and sole))
        ]
        state = compute_card_state(
            bank=a.bank, last4=a.last4, card_txns=card_txns,
            card_bills=matched, today=now)
        state["account_id"] = a.id  # for per-card transaction drill-down
        state["network"] = a.network  # per-card payment-network override (may be None)
        total_unbilled += state["unbilled"]
        if state["current_bill"]:
            total_due += state["current_bill"]["total_due"]
        out.append(state)

    out.sort(key=lambda c: (c["current_bill"]["total_due"] if c["current_bill"] else 0)
             + c["unbilled"], reverse=True)
    return {
        "cards": out,
        "total_unbilled": round(total_unbilled, 2),
        "total_due": round(total_due, 2),
    }


@router.post("/bills/{bill_id}/paid")
async def mark_bill_paid(bill_id: int, session: AsyncSession = Depends(get_session)):
    from app.repositories import CardBillRepository
    bill = await CardBillRepository(session).mark_paid(bill_id)
    if bill is None:
        raise HTTPException(status_code=404, detail="bill not found")
    await session.commit()
    return {"status": "ok"}
