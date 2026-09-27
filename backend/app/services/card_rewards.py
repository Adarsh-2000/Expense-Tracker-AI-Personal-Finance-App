"""Sync credit-card rewards: decrypt + parse statement PDFs for points balances,
and scan recent messages for redemption/voucher events. Best-effort and idempotent;
mirrors services.networth.sync_investments."""
from __future__ import annotations

import logging

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

logger = logging.getLogger("rewards")

_STATEMENT_SOURCES = [
    ("AXIS_EDGE", "AXIS", "AXIS_CARD_STATEMENT_QUERY"),
    ("IDFC_FIRST", "IDFC", "IDFC_CARD_STATEMENT_QUERY"),
]


async def _sync_statements(session: AsyncSession) -> int:
    """Fetch + decrypt + parse the latest statement per issuer. Returns #cards updated.
    No-op when Gmail isn't authorized."""
    import os

    if not os.path.exists("token.json"):
        return 0
    from app.connectors import gmail_poller
    from app.parsing.card_rewards import derive_statement_passwords, extract_points_summary
    from app.parsing.holdings import _decrypted_text
    from app.repositories import CardRewardRepository, ProfileRepository
    from app.services.gmail import fetch_latest_statement_pdf_bytes

    profile = await ProfileRepository(session).get()
    name = profile.name if profile else None
    dob = profile.dob if profile else None

    repo = CardRewardRepository(session)
    updated = 0
    for program, bank, query_name in _STATEMENT_SOURCES:
        query = getattr(gmail_poller, query_name)
        pdf = fetch_latest_statement_pdf_bytes(query)
        if not pdf:
            continue
        text = None
        for pwd in derive_statement_passwords(name=name, dob=dob, last4=None):
            text = _decrypted_text(pdf, pwd)
            if text:
                break
        if not text:
            continue
        summary = extract_points_summary(text, program)
        if not summary:
            continue
        import re
        m = re.search(r"X{2,}(\d{4})|ending\s+\w*?(\d{4})", text)
        last4 = (m.group(1) or m.group(2)) if m else "0000"
        from datetime import date
        await repo.upsert_for_card(
            bank=bank, last4=last4, program=program,
            points_balance=summary["points_balance"], earned=summary["earned"],
            redeemed=summary["redeemed"], expiring_points=summary["expiring_points"],
            expiring_date=summary["expiring_date"],
            statement_period=summary["statement_period"], as_of=date.today().isoformat())
        updated += 1
    return updated


async def _sync_events(session: AsyncSession) -> int:
    """Scan recent raw_messages for redemption + voucher events. Returns #new events."""
    from app.entities import RawMessage
    from app.parsing.card_rewards import parse_redemption_message, parse_voucher_email
    from app.repositories import RewardEventRepository

    repo = RewardEventRepository(session)
    rows = list((await session.execute(
        select(RawMessage).order_by(RawMessage.received_at.desc()).limit(2000)
    )).scalars())
    added = 0
    for r in rows:
        occurred = (r.received_at.date().isoformat() if r.received_at else "")
        ev = parse_redemption_message(r.channel, r.sender or "", r.body or "", occurred_at=occurred)
        if ev is None and r.channel == "email":
            ev = parse_voucher_email(r.body or "", occurred_at=occurred)
        if ev and await repo.add_if_new(**ev):
            added += 1
    return added


async def sync_card_rewards(session: AsyncSession) -> dict:
    """Top-level rewards sync: statements + events. Commits its own work."""
    cards = await _sync_statements(session)
    events = await _sync_events(session)
    await session.commit()
    return {"status": "ok", "cards_updated": cards, "events_added": events}
