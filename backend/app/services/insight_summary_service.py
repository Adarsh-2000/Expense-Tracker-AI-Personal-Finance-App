"""Per-month LLM insight summaries with deterministic, hash-based caching.

Lazy: generated on first request for a month and cached; regenerated only when that
month's transactions change (payload hash) or the model changes. No scheduler.
"""
from __future__ import annotations

import hashlib
import json
from datetime import datetime

from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.enrichment.insights_llm import build_payload, generate_insights, split_sections
from app.repositories import InsightSummaryRepository, TransactionRepository

_EMPTY = {"spending_patterns": "", "how_to_save": "", "money_radar": ""}


def _month_bounds(period: str) -> tuple[datetime, datetime]:
    y, m = int(period[:4]), int(period[5:7])
    start = datetime(y, m, 1)
    end = datetime(y + 1, 1, 1) if m == 12 else datetime(y, m + 1, 1)
    return start, end


def _payload_hash(payload: dict) -> str:
    return hashlib.sha256(
        json.dumps(payload, sort_keys=True, ensure_ascii=False).encode()
    ).hexdigest()


def _resp(period, *, enabled, available, sections, model, generated_at) -> dict:
    return {
        "period": period, "enabled": enabled, "available": available, "model": model,
        "generated_at": generated_at.isoformat() if generated_at else None,
        "spending_patterns": sections["spending_patterns"],
        "how_to_save": sections["how_to_save"],
        "money_radar": sections["money_radar"],
    }


async def get_summary(session: AsyncSession, period: str) -> dict:
    if not settings.insights_summary_enabled:
        return _resp(period, enabled=False, available=False, sections=dict(_EMPTY),
                     model=settings.llm_model, generated_at=None)

    start, end = _month_bounds(period)
    rows = await TransactionRepository(session).analytics_rows(start, end)
    payload = build_payload(rows, period)
    h = _payload_hash(payload)

    repo = InsightSummaryRepository(session)
    row = await repo.get(period)
    if row is not None and row.payload_hash == h and row.model == settings.llm_model:
        return _resp(period, enabled=True, available=True, model=row.model,
                     generated_at=row.generated_at,
                     sections={"spending_patterns": row.spending_patterns,
                               "how_to_save": row.how_to_save,
                               "money_radar": row.money_radar})

    res = await generate_insights(rows, period)
    if not res["ok"]:
        if row is not None:
            return _resp(period, enabled=True, available=True, model=row.model,
                         generated_at=row.generated_at,
                         sections={"spending_patterns": row.spending_patterns,
                                   "how_to_save": row.how_to_save,
                                   "money_radar": row.money_radar})
        return _resp(period, enabled=True, available=False, sections=dict(_EMPTY),
                     model=settings.llm_model, generated_at=None)

    sections = split_sections(res["text"])
    saved = await repo.upsert(period, h, settings.llm_model,
                              spending_patterns=sections["spending_patterns"],
                              how_to_save=sections["how_to_save"],
                              money_radar=sections["money_radar"])
    await session.commit()
    return _resp(period, enabled=True, available=True, sections=sections,
                 model=saved.model, generated_at=saved.generated_at)
