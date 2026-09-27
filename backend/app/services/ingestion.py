from __future__ import annotations

from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.ingestion_service import IngestionService
from app.parsing.registry import default_registry


def build_ingestion_service(session: AsyncSession) -> IngestionService:
    return IngestionService(
        session=session, registry=default_registry(),
        window_hours=settings.dedup_window_hours,
        amount_window_hours=settings.dedup_amount_window_hours,
    )
