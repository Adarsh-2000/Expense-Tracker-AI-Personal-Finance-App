from __future__ import annotations

import json

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession

from app.deps import get_session
from app.enrichment.money_radar import MoneyRadarService
from app.repositories import FindingRepository
from app.schemas import MoneyRadarResponse, RadarSummary

router = APIRouter()


def _to_out(f) -> dict:
    return {
        "id": f.id, "type": f.type, "severity": f.severity, "title": f.title,
        "detail": f.detail, "amount": f.amount, "currency": f.currency,
        "occurred_at": f.occurred_at, "status": f.status,
        "dedup_key": f.dedup_key,
        "query": json.loads(f.query_json) if f.query_json else None,
    }


@router.get("/money-radar", response_model=MoneyRadarResponse)
async def money_radar(session: AsyncSession = Depends(get_session)):
    repo = FindingRepository(session)
    findings = await repo.list_active()
    new_count = sum(1 for f in findings if f.status == "new")
    return {"findings": [_to_out(f) for f in findings], "new_count": new_count}


@router.get("/money-radar/summary", response_model=RadarSummary)
async def money_radar_summary(session: AsyncSession = Depends(get_session)):
    repo = FindingRepository(session)
    findings = await repo.list_active()
    new_count = sum(1 for f in findings if f.status == "new")
    return {"new_count": new_count, "top": [_to_out(f) for f in findings[:3]]}


@router.post("/money-radar/scan")
async def money_radar_scan(session: AsyncSession = Depends(get_session)):
    n = await MoneyRadarService(session).scan()
    await session.commit()
    return {"scanned": n}


@router.post("/money-radar/seen")
async def money_radar_seen(session: AsyncSession = Depends(get_session)):
    await FindingRepository(session).mark_all_seen()
    await session.commit()
    return {"ok": True}


@router.post("/money-radar/{finding_id}/dismiss")
async def money_radar_dismiss(finding_id: int, session: AsyncSession = Depends(get_session)):
    ok = await FindingRepository(session).dismiss(finding_id)
    if not ok:
        raise HTTPException(status_code=404, detail="finding not found")
    await session.commit()
    return {"ok": ok}
