from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.deps import get_session
from app.schemas import GoalIn
from app.services.networth import networth_components

router = APIRouter()


@router.get("/goals")
async def list_goals(session: AsyncSession = Depends(get_session)):
    """Goals with progress vs current net worth and the 10y projection ETA."""
    from app.repositories import GoalRepository
    nw = (await networth_components(session))
    current = sum(nw.values())
    goals = await GoalRepository(session).list_all()
    out = []
    for g in goals:
        pct = round(min(current / g.target_amount * 100.0, 100.0), 1) if g.target_amount > 0 else 0.0
        out.append({
            "id": g.id, "name": g.name, "target_amount": g.target_amount,
            "target_date": g.target_date, "current": round(current, 2),
            "progress_pct": pct, "remaining": round(max(g.target_amount - current, 0.0), 2),
        })
    return {"goals": out, "net_worth": round(current, 2)}


@router.post("/goals", status_code=status.HTTP_201_CREATED)
async def create_goal(payload: GoalIn, session: AsyncSession = Depends(get_session)):
    from datetime import date
    from app.repositories import GoalRepository
    g = await GoalRepository(session).add(
        name=payload.name, target_amount=payload.target_amount,
        target_date=payload.target_date, created_at=date.today().isoformat())
    await session.flush(); gid = g.id
    await session.commit()
    return {"status": "ok", "id": gid}


@router.delete("/goals/{goal_id}")
async def delete_goal(goal_id: int, session: AsyncSession = Depends(get_session)):
    from app.repositories import GoalRepository
    ok = await GoalRepository(session).delete(goal_id)
    if not ok:
        raise HTTPException(status_code=404, detail="goal not found")
    await session.commit()
    return {"status": "ok"}
