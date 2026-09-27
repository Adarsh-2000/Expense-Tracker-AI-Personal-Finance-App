from __future__ import annotations

from datetime import date, datetime

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.deps import get_session

router = APIRouter()

_STALE_DAYS = 35


def _is_stale(as_of: str) -> bool:
    try:
        return (date.today() - datetime.fromisoformat(as_of).date()).days > _STALE_DAYS
    except Exception:
        return False


@router.get("/rewards")
async def get_rewards(session: AsyncSession = Depends(get_session)):
    from app.data.reward_catalogs import catalog_for, points_to_inr
    from app.repositories import AccountRepository, CardRewardRepository, RewardEventRepository

    cards = await CardRewardRepository(session).list_all()
    ev_repo = RewardEventRepository(session)

    # Map (bank, last4) -> network override so each reward card can surface it.
    accounts = await AccountRepository(session).list_all()
    network_by_card = {
        (a.bank, a.last4): a.network for a in accounts if a.type == "card"
    }

    out_cards = []
    total_points = 0
    total_value = 0.0
    for card in cards:
        cat = catalog_for(card.program) or {}
        value = points_to_inr(card.points_balance, card.program)
        total_points += card.points_balance
        total_value += value
        events = await ev_repo.list_for_card(card.bank, card.last4, limit=5)
        out_cards.append({
            "bank": card.bank, "last4": card.last4, "program": card.program,
            "network": network_by_card.get((card.bank, card.last4)),
            "display_name": cat.get("display_name", card.program),
            "points_balance": card.points_balance, "value_inr": value,
            "as_of": card.as_of, "statement_period": card.statement_period,
            "expiring_points": card.expiring_points, "expiring_date": card.expiring_date,
            "stale": _is_stale(card.as_of),
            "portal_url": cat.get("portal_url"),
            "offer_categories": cat.get("offer_categories", []),
            "redemption_options": cat.get("partners", []),
            "recent_events": [{
                "kind": e.kind, "points": e.points, "merchant": e.merchant,
                "occurred_at": e.occurred_at, "voucher_code": e.voucher_code,
                "voucher_expiry": e.voucher_expiry,
            } for e in events],
        })

    out_cards.sort(key=lambda c: (c["expiring_date"] or "9999-12-31", -c["value_inr"]))
    return {
        "total_value_inr": round(total_value, 2),
        "total_points": total_points,
        "cards": out_cards,
    }
