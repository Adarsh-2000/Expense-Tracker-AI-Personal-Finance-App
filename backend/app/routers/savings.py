from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.deps import get_session
from app.schemas import FixedDepositIn, ManualBalanceIn
from app.services.networth import resolved_balance

router = APIRouter()


@router.get("/savings")
async def savings(session: AsyncSession = Depends(get_session)):
    """Savings accounts (with freshest resolved balance + source/as-of) and FDs,
    plus a combined liquid total. Powers the Savings tab."""
    from app.repositories import AccountRepository, FixedDepositRepository

    # Bank accounts with a real last4. A stray last4-less BOB account exists from
    # SMS that didn't expose the number — exclude it so no phantom card shows.
    accs = [a for a in await AccountRepository(session).list_all()
            if a.type == "bank" and (a.last4 or "").strip()]
    accounts = []
    savings_total = 0.0
    for a in accs:
        rb = await resolved_balance(session, a)
        if rb["balance"] is not None:
            savings_total += rb["balance"]
        accounts.append({
            "id": a.id, "bank": a.bank, "last4": a.last4,
            "balance": rb["balance"], "as_of": rb["as_of"], "source": rb["source"],
        })
    accounts.sort(key=lambda x: (x["balance"] or 0.0), reverse=True)

    fds = await FixedDepositRepository(session).list_all()
    fd_list = [{
        "id": f.id, "bank": f.bank, "principal": f.principal,
        "interest_rate": f.interest_rate, "start_date": f.start_date,
        "maturity_date": f.maturity_date, "maturity_amount": f.maturity_amount,
        "note": f.note,
    } for f in fds]
    fd_total = sum(f.principal for f in fds)

    return {
        "accounts": accounts,
        "savings_total": round(savings_total, 2),
        "fixed_deposits": fd_list,
        "fd_total": round(fd_total, 2),
        "liquid_total": round(savings_total + fd_total, 2),
    }


@router.put("/accounts/{account_id}/balance")
async def set_account_balance(account_id: int, payload: ManualBalanceIn,
                              session: AsyncSession = Depends(get_session)):
    from datetime import date
    from app.repositories import AccountRepository
    as_of = payload.as_of or date.today().isoformat()
    acc = await AccountRepository(session).set_manual_balance(
        account_id, balance=payload.balance, as_of=as_of)
    if acc is None:
        raise HTTPException(status_code=404, detail="account not found")
    await session.commit()
    return {"status": "ok", "balance": payload.balance, "as_of": as_of}


@router.get("/fixed-deposits")
async def list_fixed_deposits(session: AsyncSession = Depends(get_session)):
    from app.repositories import FixedDepositRepository
    fds = await FixedDepositRepository(session).list_all()
    return [{
        "id": f.id, "bank": f.bank, "principal": f.principal,
        "interest_rate": f.interest_rate, "start_date": f.start_date,
        "maturity_date": f.maturity_date, "maturity_amount": f.maturity_amount,
        "note": f.note,
    } for f in fds]


@router.post("/fixed-deposits", status_code=status.HTTP_201_CREATED)
async def create_fixed_deposit(payload: FixedDepositIn,
                               session: AsyncSession = Depends(get_session)):
    from app.repositories import FixedDepositRepository
    fd = await FixedDepositRepository(session).add(
        bank=payload.bank, principal=payload.principal,
        interest_rate=payload.interest_rate, start_date=payload.start_date,
        maturity_date=payload.maturity_date, maturity_amount=payload.maturity_amount,
        note=payload.note)
    await session.flush()
    fd_id = fd.id
    await session.commit()
    return {"status": "ok", "id": fd_id}


@router.delete("/fixed-deposits/{fd_id}")
async def delete_fixed_deposit(fd_id: int, session: AsyncSession = Depends(get_session)):
    from app.repositories import FixedDepositRepository
    ok = await FixedDepositRepository(session).delete(fd_id)
    if not ok:
        raise HTTPException(status_code=404, detail="FD not found")
    await session.commit()
    return {"status": "ok"}
