from __future__ import annotations

from datetime import datetime, timedelta


def compute_card_state(
    *,
    bank: str,
    last4: str,
    card_txns: list[dict],
    card_bills: list[dict],
    today: datetime,
    cycle_days: int = 38,
    stale_after_days: int | None = None,
) -> dict:
    """Compute a credit card's CRED-style unbilled + current-bill state.

    Args:
      card_txns: this card's debit transactions, each {amount, occurred_at(datetime)}.
      card_bills: bills matched to this card (by bank+last4), each
        {total_due, due_date(iso), received_at(datetime), paid(bool)}.
      today: reference datetime for days-until-due.
      stale_after_days: a statement overdue by more than this many days is treated
        as settled/superseded (defaults to one billing cycle, `cycle_days`).

    Logic (mirrors how a card cycle works):
      - The latest bill (by received_at) is the most recent statement.
      - CURRENT BILL ("due now") = the latest statement only if it is unpaid AND
        not overdue by more than a full billing cycle. We never auto-detect card
        payments (they don't arrive as card credits), so an unpaid statement that
        is months overdue is almost certainly already settled or rolled into a
        newer cycle we don't have — surfacing it as "due now" is wrong. Paid
        statements are likewise not outstanding.
      - UNBILLED = spend in the current open cycle. If the latest statement is
        recent (within `cycle_days`), unbilled = spend after it. If the latest
        statement is stale/ancient (or none exists), window to the last
        `cycle_days` so unbilled reflects the current cycle, not months of spend.
    """
    if stale_after_days is None:
        stale_after_days = cycle_days

    bills_sorted = sorted(card_bills, key=lambda b: b["received_at"], reverse=True)
    latest = bills_sorted[0] if bills_sorted else None
    statement_date = latest["received_at"] if latest else None

    # Is the latest statement recent enough to be the current cycle's boundary?
    latest_fresh = (
        statement_date is not None
        and (today - statement_date).days <= cycle_days
    )

    # Unbilled cutoff: spend after a fresh statement, else window to the last cycle
    # (so a stale/ancient statement doesn't make months of spend look "unbilled").
    cutoff = statement_date if latest_fresh else (today - timedelta(days=cycle_days))

    unbilled = 0.0
    unbilled_count = 0
    for t in card_txns:
        if t["occurred_at"] > cutoff:
            unbilled += t["amount"]
            unbilled_count += 1

    current_bill = None
    if latest is not None and not latest.get("paid", False):
        due = latest.get("due_date")
        days_until = None
        if due:
            try:
                days_until = (datetime.fromisoformat(due).date() - today.date()).days
            except ValueError:
                days_until = None
        # Only outstanding if not overdue beyond a full cycle. Unknown due date →
        # keep it only when the statement itself is still fresh.
        overdue = (-days_until) if days_until is not None else None
        settled = (overdue is not None and overdue > stale_after_days) or (
            overdue is None and not latest_fresh
        )
        if not settled:
            current_bill = {
                "total_due": round(latest["total_due"], 2),
                "due_date": due,
                "days_until_due": days_until,
            }

    return {
        "bank": bank,
        "last4": last4,
        "unbilled": round(unbilled, 2),
        "unbilled_count": unbilled_count,
        "last_statement_date": (statement_date.date().isoformat() if statement_date else None),
        "current_bill": current_bill,
    }
