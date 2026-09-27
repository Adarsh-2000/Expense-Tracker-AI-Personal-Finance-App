from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.deps import get_session
from app.schemas import SetCardDetailsIn
from app.services.networth import networth_components

router = APIRouter()


@router.get("/cards/utilization")
async def cards_utilization(session: AsyncSession = Depends(get_session)):
    from app.services.utilization import card_utilization
    return {"cards": await card_utilization(session)}


@router.put("/cards/{account_id}/limit")
async def set_card_limit(account_id: int, payload: SetCardDetailsIn,
                         session: AsyncSession = Depends(get_session)):
    # Backward compatible: a body with only credit_limit still works; a body with
    # only network sets the network without touching the limit (and vice versa).
    from app.repositories import AccountRepository
    from fastapi import HTTPException
    ok = await AccountRepository(session).set_card_details(
        account_id, credit_limit=payload.credit_limit, network=payload.network)
    if not ok:
        raise HTTPException(status_code=404, detail="account not found")
    await session.commit()
    return {"ok": True}


@router.get("/accounts")
async def list_accounts(session: AsyncSession = Depends(get_session)):
    from app.repositories import AccountRepository
    accs = await AccountRepository(session).list_all()
    return [{"id": a.id, "bank": a.bank, "type": a.type, "last4": a.last4, "current_balance": a.current_balance} for a in accs]


@router.get("/networth")
async def net_worth(session: AsyncSession = Depends(get_session)):
    c = await networth_components(session)
    total = round(sum(c.values()), 2)
    return {"net_worth": total, "components": c}


@router.get("/networth/projection")
async def net_worth_projection(
    years: int = 10,
    equity_rate: float = 12.0,
    us_rate: float = 12.0,
    fd_rate: float = 7.0,
    savings_rate: float = 3.5,
    sip: float = 75000.0,
    sip_after: float = 100000.0,
    step_date: str | None = None,
    session: AsyncSession = Depends(get_session),
):
    """10-year (configurable) net-worth projection. Equity + US stocks compound at
    the equity rate and receive the monthly SIP (stepped up after step_date);
    FD and savings compound at their own rates. Nominal (future) rupees."""
    from datetime import date
    from app.enrichment.projection import project_net_worth

    c = await networth_components(session)
    today = date.today()
    # SIP feeds the growth bucket; combine Indian + US equity into one equity pool
    # (both grow at equity_rate; us_rate kept as an accepted param for clarity).
    equity_pool = c["indian_equity"] + c["us_stocks"]

    # Step-up month: months from today until step_date (default: next Jan 1).
    if step_date:
        sd = date.fromisoformat(step_date)
    else:
        sd = date(today.year + 1, 1, 1)
    step_after_month = max(0, (sd.year - today.year) * 12 + (sd.month - today.month))

    proj = project_net_worth(
        equity=equity_pool, fd=c["fixed_deposits"], savings=c["savings"],
        equity_rate=equity_rate, fd_rate=fd_rate, savings_rate=savings_rate,
        sip=sip, sip_after=sip_after, step_after_month=step_after_month,
        years=years, start_year=today.year, start_month=today.month,
    )
    proj["components_now"] = c
    proj["step_date"] = sd.isoformat()
    return proj


@router.get("/accounts/{account_id}/balance-history")
async def balance_history(account_id: int, session: AsyncSession = Depends(get_session)):
    from app.repositories import BalanceSnapshotRepository
    hist = await BalanceSnapshotRepository(session).history(account_id)
    return [{"balance": h.balance, "captured_at": h.captured_at.isoformat()} for h in hist]
