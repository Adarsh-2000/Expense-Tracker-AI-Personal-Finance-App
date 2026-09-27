from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.deps import get_session
from app.services.notifications import build_notifications

router = APIRouter()


@router.get("/notifications")
async def notifications(session: AsyncSession = Depends(get_session)):
    """Current notification feed (bills, goals, budgets, spending, weekly digest).
    Each item has a stable `id`; the client posts each once and dedupes on
    re-fetch. Stateless on the server."""
    items = await build_notifications(session)
    return {"notifications": items, "count": len(items)}
