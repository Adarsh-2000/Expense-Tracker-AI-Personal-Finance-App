from __future__ import annotations

import logging

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.deps import get_session
from app.services import gmail
from app.services.card_rewards import sync_card_rewards
from app.services.ingestion import build_ingestion_service
from app.services.networth import sync_investments

router = APIRouter()


@router.get("/stats")
async def stats(session: AsyncSession = Depends(get_session)):
    """Source ingestion stats for the Home page: SMS vs email parsed/total + totals."""
    from sqlalchemy import func, select

    from app.entities import RawMessage, Transaction

    async def counts(channel: str) -> dict:
        total = (await session.execute(
            select(func.count()).select_from(RawMessage).where(RawMessage.channel == channel)
        )).scalar_one()
        parsed = (await session.execute(
            select(func.count()).select_from(RawMessage).where(
                RawMessage.channel == channel, RawMessage.parsed_status == "parsed"
            )
        )).scalar_one()
        return {"total": int(total), "parsed": int(parsed)}

    txn_count = (await session.execute(select(func.count()).select_from(Transaction))).scalar_one()
    return {
        "sms": await counts("sms"),
        "email": await counts("email"),
        "transactions": int(txn_count),
    }


@router.post("/sync/gmail")
async def sync_gmail(session: AsyncSession = Depends(get_session)):
    svc = build_ingestion_service(session)
    fields = gmail.fetch_gmail_fields()
    if not fields:
        import os

        if not os.path.exists("token.json"):
            return {
                "ingested": 0,
                "fetched": 0,
                "detail": "Gmail not authorized; run scripts/gmail_auth.py",
            }
    ingested = 0
    for f in fields:
        # Drop incremental-sync bookkeeping keys before handing to ingest_message,
        # which only accepts the canonical message fields.
        payload = {k: v for k, v in f.items() if k != "internal_date_ms"}
        txn = await svc.ingest_message(channel="email", **payload)
        if txn is not None:
            ingested += 1
    await session.commit()
    from app.enrichment.money_radar import MoneyRadarService
    try:
        await MoneyRadarService(session).scan()
        await session.commit()
    except Exception:
        await session.rollback()
        logging.getLogger("money_radar").exception("post-ingest scan failed")
    # Refresh investment portfolio (Indian holdings + US stocks) so net worth
    # reflects new statements. Best-effort: never break transaction ingestion.
    try:
        await sync_investments(session)
    except Exception:
        await session.rollback()
        logging.getLogger("portfolio").exception("post-sync investment sync failed")
    # Refresh credit-card rewards (statement points + redemption/voucher events).
    # Best-effort: never break transaction ingestion.
    try:
        await sync_card_rewards(session)
    except Exception:
        await session.rollback()
        logging.getLogger("rewards").exception("post-sync card-rewards sync failed")
    # Push any newly-detected events (bills, findings, digests) to registered devices.
    try:
        from app.services.push import dispatch_new_pushes
        await dispatch_new_pushes(session)
        await session.commit()
    except Exception:
        await session.rollback()
        logging.getLogger("push").exception("post-sync push dispatch failed")
    return {"ingested": ingested, "fetched": len(fields)}
