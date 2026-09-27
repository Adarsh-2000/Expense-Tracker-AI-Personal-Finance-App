from __future__ import annotations

import logging
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.deps import get_session
from app.repositories import TransactionRepository
from app.schemas import ManualTxnIn, SmsIn, SplitIn, TransactionOut, TxnPatch
from app.services.ingestion import build_ingestion_service

router = APIRouter()


@router.post("/ingest/sms", status_code=status.HTTP_201_CREATED)
async def ingest_sms(payload: SmsIn, response: Response, session: AsyncSession = Depends(get_session)):
    svc = build_ingestion_service(session)
    txn = await svc.ingest_message(
        channel="sms", sender=payload.sender, body=payload.body,
        received_at=payload.received_at, external_id=payload.external_id,
    )
    await session.commit()
    from app.enrichment.money_radar import MoneyRadarService
    try:
        await MoneyRadarService(session).scan()
        await session.commit()
    except Exception:
        await session.rollback()
        logging.getLogger("money_radar").exception("post-ingest scan failed")
    if txn is None:
        response.status_code = status.HTTP_202_ACCEPTED
        return {"status": "review_or_duplicate"}
    return TransactionOut.model_validate(txn)


@router.post("/transactions/manual", status_code=status.HTTP_201_CREATED)
async def create_manual(payload: ManualTxnIn, session: AsyncSession = Depends(get_session)):
    from app.enrichment.cascade_categorizer import CascadeCategorizer
    from app.parsing.types import normalize_merchant
    from app.repositories import AccountRepository, CategoryRepository, TransactionRepository

    accounts = AccountRepository(session)
    if payload.account_id is None:
        acc = await accounts.get_or_create(bank="CASH", type="cash", last4="0000")
        await session.flush()
        account_id = acc.id
    else:
        account_id = payload.account_id

    category_id = payload.category_id
    if category_id is None:
        name = CascadeCategorizer().categorize(payload.merchant)
        cat = await CategoryRepository(session).get_or_create(name)
        await session.flush()
        category_id = cat.id

    fingerprint = f"manual|{payload.amount}|{payload.direction}|{normalize_merchant(payload.merchant)}"
    txn = await TransactionRepository(session).create(
        amount=payload.amount, direction=payload.direction, merchant=payload.merchant,
        occurred_at=payload.occurred_at, account_id=account_id, category_id=category_id,
        fingerprint=fingerprint, entry_type="manual", note=payload.note,
    )
    await session.commit()
    return TransactionOut.model_validate(txn)


@router.get("/transactions/search", response_model=list[TransactionOut])
async def search_transactions(
    q: str | None = None, merchant_any: str | None = None,
    account_id: int | None = None, category_id: int | None = None,
    start: datetime | None = None, end: datetime | None = None,
    min_amount: float | None = None, max_amount: float | None = None,
    direction: str | None = None,
    session: AsyncSession = Depends(get_session),
):
    from app.repositories import TransactionRepository
    rows = await TransactionRepository(session).search(
        q=q, merchant_any=merchant_any, account_id=account_id, category_id=category_id,
        start=start, end=end, min_amount=min_amount, max_amount=max_amount,
        direction=direction,
    )
    return [TransactionOut.model_validate(r) for r in rows]


@router.get("/transactions", response_model=list[TransactionOut])
async def list_transactions(session: AsyncSession = Depends(get_session)):
    rows = await TransactionRepository(session).list_all()
    return [TransactionOut.model_validate(r) for r in rows]


@router.patch("/transactions/{txn_id}")
async def patch_transaction(txn_id: int, patch: TxnPatch, session: AsyncSession = Depends(get_session)):
    from app.repositories import TransactionRepository
    repo = TransactionRepository(session)
    txn = await repo.get(txn_id)
    if txn is None:
        raise HTTPException(status_code=404, detail="transaction not found")
    for field, value in patch.model_dump(exclude_unset=True).items():
        setattr(txn, field, value)
    await session.commit()
    return TransactionOut.model_validate(txn)


@router.put("/transactions/{txn_id}/splits")
async def put_splits(txn_id: int, payload: SplitIn, session: AsyncSession = Depends(get_session)):
    from app.repositories import TransactionRepository, TransactionSplitRepository
    repo = TransactionRepository(session)
    splits_repo = TransactionSplitRepository(session)
    txn = await repo.get(txn_id)
    if txn is None:
        raise HTTPException(status_code=404, detail="transaction not found")
    total = round(sum(s.amount for s in payload.splits), 2)
    if total != round(txn.amount, 2):
        raise HTTPException(status_code=400, detail="splits must sum to transaction amount")
    await splits_repo.delete_for(txn_id)
    for s in payload.splits:
        await splits_repo.add(transaction_id=txn_id, category_id=s.category_id, amount=s.amount)
    await session.commit()
    return {"status": "ok", "count": len(payload.splits)}


@router.get("/export/transactions.csv")
async def export_transactions_csv(session: AsyncSession = Depends(get_session)):
    import csv
    import io

    from fastapi.responses import StreamingResponse

    from app.repositories import AccountRepository, CategoryRepository, TransactionRepository

    txns = await TransactionRepository(session).list_all()
    accounts = {a.id: f"{a.bank} {a.last4}" for a in await AccountRepository(session).list_all()}
    categories = {c.id: c.name for c in await CategoryRepository(session).list_all()}

    buf = io.StringIO()
    writer = csv.writer(buf)
    writer.writerow(["id", "occurred_at", "account", "merchant", "category", "direction", "amount", "note"])
    for t in txns:
        writer.writerow([
            t.id, t.occurred_at.isoformat(), accounts.get(t.account_id, ""),
            t.merchant, categories.get(t.category_id, ""), t.direction, t.amount, t.note or "",
        ])
    buf.seek(0)
    return StreamingResponse(
        iter([buf.getvalue()]), media_type="text/csv",
        headers={"Content-Disposition": "attachment; filename=transactions.csv"},
    )
