from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.deps import get_session
from app.schemas import AssignIn, IncomeIn

router = APIRouter()


@router.put("/budget/income")
async def set_income(payload: IncomeIn, session: AsyncSession = Depends(get_session)):
    from app.repositories import MonthlyIncomeRepository
    await MonthlyIncomeRepository(session).set(month=payload.month, amount=payload.amount)
    await session.commit()
    return {"status": "ok"}


@router.put("/budget/assign")
async def set_assignment(payload: AssignIn, session: AsyncSession = Depends(get_session)):
    from app.repositories import BudgetAssignmentRepository
    await BudgetAssignmentRepository(session).set(
        category_id=payload.category_id, month=payload.month, assigned_amount=payload.assigned_amount
    )
    await session.commit()
    return {"status": "ok"}


@router.get("/budget/{month}")
async def get_budget(month: str, session: AsyncSession = Depends(get_session)):
    from dataclasses import asdict

    from app.enrichment.zero_based import ZeroBasedBudgetService
    from app.repositories import (
        BudgetAssignmentRepository, MonthlyIncomeRepository,
        TransactionRepository, TransactionSplitRepository,
    )
    from app.repositories import CategoryRepository
    svc = ZeroBasedBudgetService(
        income=MonthlyIncomeRepository(session),
        assignments=BudgetAssignmentRepository(session),
        transactions=TransactionRepository(session),
        splits=TransactionSplitRepository(session),
    )
    names = {c.id: c.name for c in await CategoryRepository(session).list_all()}
    return {
        "month": month,
        "ready_to_assign": await svc.ready_to_assign(month),
        "categories": [asdict(s) for s in await svc.category_status(month, names=names)],
    }
