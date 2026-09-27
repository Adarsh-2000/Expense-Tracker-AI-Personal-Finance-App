from __future__ import annotations

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.deps import get_session
from app.repositories import DeviceTokenRepository
from app.services import push as push_service

router = APIRouter()


class PushRegisterIn(BaseModel):
    token: str
    platform: str = "android"


@router.post("/push/register")
async def register_device(payload: PushRegisterIn, session: AsyncSession = Depends(get_session)):
    """The Android app posts its FCM token here on launch / token rotation. The app
    may register the same token twice in quick succession (onNewToken + on-launch);
    if two concurrent inserts race the unique constraint, the token is already
    stored, so treat the conflict as success."""
    try:
        await DeviceTokenRepository(session).register(token=payload.token, platform=payload.platform)
        await session.commit()
    except IntegrityError:
        await session.rollback()  # token already registered by the racing request
    return {"ok": True, "fcm_enabled": push_service.is_enabled()}


@router.post("/push/test")
async def test_push(session: AsyncSession = Depends(get_session)):
    """Fire a test push to every registered device — proves the pipe end-to-end."""
    tokens = await DeviceTokenRepository(session).list_tokens()
    result = push_service.send_push(
        tokens,
        title="Ledger Noir",
        body="🔔 Push notifications are working.",
        data={"type": "test", "route": "dashboard"},
    )
    return {"fcm_enabled": push_service.is_enabled(), "devices": len(tokens),
            "sent": result.sent, "failed": result.failed}


@router.post("/push/dispatch")
async def dispatch_pushes(session: AsyncSession = Depends(get_session)):
    """Push any new notifications now (also invoked automatically after sync)."""
    summary = await push_service.dispatch_new_pushes(session)
    await session.commit()
    return summary
