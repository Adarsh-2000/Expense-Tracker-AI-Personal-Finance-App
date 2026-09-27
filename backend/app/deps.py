from __future__ import annotations

from collections.abc import AsyncIterator
from functools import lru_cache

from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.db import new_session_factory


@lru_cache
def _factory():
    return new_session_factory(settings.database_url)


async def get_session() -> AsyncIterator[AsyncSession]:
    _engine, Session = _factory()
    async with Session() as session:
        yield session
