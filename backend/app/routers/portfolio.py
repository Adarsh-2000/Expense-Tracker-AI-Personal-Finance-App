from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession

from app import security
from app.deps import get_session
from app.schemas import ProfileIn
from app.services.networth import sync_investments, us_stocks_block

router = APIRouter()


@router.put("/profile")
async def put_profile(payload: ProfileIn, session: AsyncSession = Depends(get_session)):
    from app.repositories import ProfileRepository
    pan = payload.pan
    if pan is not None:
        pan = pan.strip().upper()
        if not security.PAN_RE.match(pan):
            raise HTTPException(status_code=400, detail="invalid PAN")
    await ProfileRepository(session).upsert(pan=pan, dob=payload.dob, name=payload.name)
    await session.commit()
    return {"ok": True}


@router.get("/profile")
async def get_profile(session: AsyncSession = Depends(get_session)):
    from app.repositories import ProfileRepository
    profile = await ProfileRepository(session).get()
    pan = profile.pan if profile else None
    pan_masked = ("XXXXXX" + pan[-4:]) if pan else None
    return {
        "has_pan": bool(pan),
        "pan_masked": pan_masked,
        "dob": profile.dob if profile else None,
    }


@router.get("/portfolio")
async def get_portfolio(session: AsyncSession = Depends(get_session)):
    from app.repositories import HoldingRepository, UsPortfolioRepository
    rows = await HoldingRepository(session).list_all()
    rows = sorted(rows, key=lambda h: h.value, reverse=True)
    as_of = rows[0].as_of if rows else None
    total = sum(h.value for h in rows)

    us = await UsPortfolioRepository(session).get()
    us_block = us_stocks_block(us)
    # Grand total includes the INR-converted US aggregate so the hero reflects
    # the whole portfolio (Indian holdings + US stocks).
    grand_total = total + (us_block["current_inr"] if us_block else 0.0)

    return {
        "as_of": as_of,
        "total_value": round(total, 2),
        "grand_total_value": round(grand_total, 2),
        "count": len(rows),
        "holdings": [
            {"isin": h.isin, "name": h.name, "quantity": h.quantity,
             "rate": h.rate, "value": h.value, "type": h.type}
            for h in rows
        ],
        "us_stocks": us_block,
    }


@router.post("/sync/portfolio")
async def sync_portfolio(session: AsyncSession = Depends(get_session)):
    from app.services.networth import sync_investments
    result = await sync_investments(session)
    # Full Indian holdings statement parsed + stored.
    if "holdings" in result:
        return {
            "holdings": result.get("holdings"),
            "total_value": result.get("total_value"),
            "as_of": result.get("as_of"),
            "us_stocks": result.get("us_stocks"),
        }
    status = result.get("status")
    if status == "no_pan":
        return {"status": "no_pan", "us_stocks": result.get("us_stocks"),
                "detail": "Set your PAN in profile first"}
    if status == "ok":
        # No PAN, but the US aggregate refreshed successfully.
        return {"status": "ok", "us_stocks": result.get("us_stocks"), "detail": None}
    # no_statement / parse_failed: US may have refreshed, no Indian holdings.
    return {"status": status, "us_stocks": result.get("us_stocks")}
