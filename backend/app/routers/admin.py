from __future__ import annotations

from datetime import timedelta

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.deps import get_session
from app.services.ingestion import build_ingestion_service

router = APIRouter()


@router.post("/admin/reparse-bills")
async def reparse_bills(session: AsyncSession = Depends(get_session)):
    """Scan needs_review email RawMessages, parse them as credit-card bills,
    upsert any matches, and flip their status to 'bill'. Recovers statement
    emails already sitting in the DB before the bills feature existed.
    """
    from app.parsing.bills import parse_card_bill
    from app.repositories import CardBillRepository, RawMessageRepository

    messages = await RawMessageRepository(session).list_by_status("needs_review")
    bills_repo = CardBillRepository(session)
    found = 0
    for msg in messages:
        # Statements/reminders arrive by email AND SMS (IDFC eStatement SMS), so
        # scan both channels.
        try:
            bill = parse_card_bill(msg.sender, msg.body, msg.received_at)
        except Exception:
            bill = None
        if bill is None:
            continue
        await bills_repo.upsert(
            bank=bill["bank"], last4=bill["last4"], due_date=bill["due_date"],
            total_due=bill["total_due"], min_due=bill["min_due"],
            statement_period=bill["statement_period"], received_at=msg.received_at,
        )
        msg.parsed_status = "bill"
        found += 1
    await session.commit()
    return {"bills_found": found}


@router.get("/review")
async def review_queue(session: AsyncSession = Depends(get_session)):
    from app.repositories import RawMessageRepository
    msgs = await RawMessageRepository(session).list_by_status("needs_review")
    return [{"id": m.id, "channel": m.channel, "sender": m.sender, "body": m.body, "received_at": m.received_at.isoformat()} for m in msgs]


@router.get("/recurring")
async def recurring(session: AsyncSession = Depends(get_session)):
    from app.enrichment.recurring import detect_recurring
    from app.repositories import TransactionRepository
    txns = await TransactionRepository(session).list_all()
    charges = [(t.merchant, t.amount, t.occurred_at) for t in txns if t.direction == "debit"]
    series = detect_recurring(charges)
    return [
        {"merchant": s.merchant, "expected_amount": s.expected_amount, "cadence_days": s.cadence_days,
         "last_seen_at": s.last_seen_at.isoformat(), "next_expected_at": s.next_expected_at.isoformat()}
        for s in series
    ]


@router.get("/categories")
async def list_categories(session: AsyncSession = Depends(get_session)):
    from app.repositories import CategoryRepository
    cats = await CategoryRepository(session).list_all()
    return [{"id": c.id, "name": c.name} for c in cats]


@router.post("/admin/recategorize")
async def recategorize(session: AsyncSession = Depends(get_session)):
    from app.enrichment.cascade_categorizer import CascadeCategorizer
    from app.repositories import CategoryRepository, TransactionRepository
    cat = CascadeCategorizer()
    repo = TransactionRepository(session)
    cats = CategoryRepository(session)
    txns = await repo.list_all()
    updated = 0
    namecache: dict[str, int] = {}
    for t in txns:
        # The DB stores only the merchant string, not is_transfer, so derive a
        # transfer heuristic from the raw merchant: a VPA handle ("@"), an
        # explicit "upi transfer", or a NEFT/IMPS/RTGS reference. Real merchant
        # names (e.g. "RK GROCERY") fall through to keyword match / uncategorized.
        m = (t.merchant or "").lower()
        is_transfer = (
            "@" in m
            or m == "upi transfer"
            or "neft" in m
            or "imps" in m
            or "rtgs" in m
        )
        name = cat.categorize(t.merchant, is_transfer)
        if name not in namecache:
            c = await cats.get_or_create(name)
            await session.flush()
            namecache[name] = c.id
        if t.category_id != namecache[name]:
            t.category_id = namecache[name]
            updated += 1
    await session.commit()
    return {"recategorized": updated, "total": len(txns)}


@router.post("/admin/dedup")
async def dedup_existing(session: AsyncSession = Depends(get_session)):
    """Merge cross-channel duplicate transactions the old (backward-only, UTC-skewed)
    ingest logic missed.

    Groups transactions by (account_id, round(amount,2), direction). Within each
    group, sorted by occurred_at, any two transactions within
    dedup_amount_window_hours of each other whose source channels are DISJOINT
    (e.g. one has email sources, the other sms) are the same purchase: reattach the
    later one's raw_messages to the earlier one and delete the later one. Genuine
    same-channel repeats are never merged.
    """
    from sqlalchemy import delete as sa_delete
    from sqlalchemy import select as sa_select

    from app.entities import RawMessage, Transaction

    window = timedelta(hours=settings.dedup_amount_window_hours)

    txns = list((await session.execute(sa_select(Transaction))).scalars())

    # Channels per transaction, in one pass over linked raw_messages.
    channels: dict[int, set[str]] = {t.id: set() for t in txns}
    rows = (await session.execute(
        sa_select(RawMessage.transaction_id, RawMessage.channel).where(
            RawMessage.transaction_id.is_not(None)
        )
    )).all()
    for txn_id, channel in rows:
        channels.setdefault(txn_id, set()).add(channel)

    groups: dict[tuple, list[Transaction]] = {}
    for t in txns:
        groups.setdefault((t.account_id, round(t.amount, 2), t.direction), []).append(t)

    merged = 0
    for group in groups.values():
        group.sort(key=lambda t: t.occurred_at)
        deleted: set[int] = set()
        for i in range(len(group)):
            a = group[i]
            if a.id in deleted:
                continue
            for j in range(i + 1, len(group)):
                b = group[j]
                if b.id in deleted:
                    continue
                if abs((b.occurred_at - a.occurred_at).total_seconds()) > window.total_seconds():
                    continue
                a_ch, b_ch = channels.get(a.id, set()), channels.get(b.id, set())
                # Only merge when the two transactions come from different,
                # non-overlapping channels (a real cross-channel dup).
                if not a_ch or not b_ch or (a_ch & b_ch):
                    continue
                # Reattach b's raw_messages to a (the earlier txn), then delete b.
                for rm in (await session.execute(
                    sa_select(RawMessage).where(RawMessage.transaction_id == b.id)
                )).scalars():
                    rm.transaction_id = a.id
                channels[a.id] = a_ch | b_ch
                await session.execute(
                    sa_delete(Transaction).where(Transaction.id == b.id)
                )
                deleted.add(b.id)
                merged += 1

    await session.commit()
    remaining = len(list((await session.execute(sa_select(Transaction))).scalars()))
    return {"merged": merged, "remaining": remaining}


@router.post("/admin/reparse")
async def reparse_review(session: AsyncSession = Depends(get_session)):
    """Re-run the parser registry over every needs_review RawMessage.

    Messages that now parse are pushed through the same dedup/categorize path
    used at ingest (linking the transaction + flipping status to "parsed").
    """
    from app.repositories import RawMessageRepository

    svc = build_ingestion_service(session)
    messages = await RawMessageRepository(session).list_by_status("needs_review")
    promoted = 0
    for msg in messages:
        try:
            parsed = svc.registry.parse(msg.sender, msg.body, msg.received_at)
        except Exception:
            parsed = None
        if parsed is None:
            continue
        await svc.dedup.ingest(parsed, msg)
        promoted += 1
    await session.commit()
    remaining = await RawMessageRepository(session).list_by_status("needs_review")
    return {"reparsed": promoted, "remaining_review": len(remaining)}


@router.post("/admin/reparse-all")
async def reparse_all(session: AsyncSession = Depends(get_session)):
    """Re-run the CURRENT parser over the source message of every PARSED transaction
    and refresh its merchant / category / currency / txn_ref in place.

    Fixes 'stale' transactions created by older parser versions (e.g. merchant stuck
    as 'Merchant Name:'). Updates fields only — does not re-dedupe or move rows.
    """
    from sqlalchemy import select

    from app.entities import RawMessage, Transaction
    from app.enrichment.cascade_categorizer import CascadeCategorizer
    from app.repositories import CategoryRepository

    svc = build_ingestion_service(session)
    categorizer = CascadeCategorizer()
    cats = CategoryRepository(session)
    cat_id: dict[str, int] = {}

    # One representative source message per transaction (prefer an email/sms source).
    rows = (await session.execute(select(Transaction))).scalars().all()
    updated = 0
    for txn in rows:
        src = (await session.execute(
            select(RawMessage).where(RawMessage.transaction_id == txn.id).limit(1)
        )).scalar_one_or_none()
        if src is None:
            continue  # manual entry, nothing to reparse
        try:
            pt = svc.registry.parse(src.sender, src.body, src.received_at)
        except Exception:
            pt = None
        if pt is None:
            continue
        from app.parsing._util import clean_merchant
        pt.merchant = clean_merchant(pt.merchant)
        changed = False
        if pt.merchant and pt.merchant != txn.merchant:
            txn.merchant = pt.merchant
            changed = True
        if pt.currency and pt.currency != txn.currency:
            txn.currency = pt.currency
            txn.original_amount = pt.original_amount
            changed = True
        if getattr(pt, "txn_ref", None) and pt.txn_ref != txn.txn_ref:
            txn.txn_ref = pt.txn_ref
            changed = True
        # Recategorize from the (possibly new) merchant.
        name = categorizer.categorize(pt.merchant, pt.is_transfer)
        if name not in cat_id:
            c = await cats.get_or_create(name)
            await session.flush()
            cat_id[name] = c.id
        if txn.category_id != cat_id[name]:
            txn.category_id = cat_id[name]
            changed = True
        if changed:
            updated += 1
    await session.commit()
    return {"updated": updated, "scanned": len(rows)}


@router.post("/admin/llm-backfill")
async def llm_backfill(limit: int = 200, session: AsyncSession = Depends(get_session)):
    """Opt-in: classify still-uncategorized transactions with the local LLM, feeding
    each one its raw SMS/email body (far stronger than the bare merchant). Kept off the
    ingestion hot path; safe to re-run. No-op for rows the LLM can't resolve, and a
    no-op overall if no LLM is served at settings.llm_url.
    """
    from sqlalchemy import or_, select

    from app.entities import Category, RawMessage, Transaction
    from app.enrichment.llm_backfill import classify_texts
    from app.enrichment.taxonomy import UNCATEGORIZED
    from app.repositories import CategoryRepository

    uncat_id = (await session.execute(
        select(Category.id).where(Category.name == UNCATEGORIZED))).scalar_one_or_none()
    cond = Transaction.category_id.is_(None)
    if uncat_id is not None:
        cond = or_(cond, Transaction.category_id == uncat_id)
    txns = (await session.execute(
        select(Transaction).where(cond).limit(limit))).scalars().all()
    if not txns:
        return {"scanned": 0, "classified": 0, "updated": 0}

    # one raw body per transaction (prefer the linked source message); fall back to merchant
    ids = [t.id for t in txns]
    bodies: dict[int, str] = {}
    for rm in (await session.execute(
            select(RawMessage).where(RawMessage.transaction_id.in_(ids)))).scalars().all():
        bodies.setdefault(rm.transaction_id, rm.body)
    texts = [bodies.get(t.id) or t.merchant for t in txns]

    preds = await classify_texts(texts)

    cats = CategoryRepository(session)
    cat_id: dict[str, int] = {}
    updated = 0
    for txn, pred in zip(txns, preds):
        if not pred:
            continue
        if pred not in cat_id:
            c = await cats.get_or_create(pred)
            await session.flush()
            cat_id[pred] = c.id
        txn.category_id = cat_id[pred]
        updated += 1
    await session.commit()
    return {"scanned": len(txns), "classified": sum(1 for p in preds if p), "updated": updated}
