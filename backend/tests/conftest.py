import pytest
import pytest_asyncio

from app.config import settings
from app.db import Base, new_session_factory


@pytest.fixture(autouse=True)
def _disable_app_lock(monkeypatch):
    """Keep the test suite independent of the developer's local `.env`.

    The dev `.env` sets `ET_GOOGLE_WEB_CLIENT_ID` (+ allowed_email), which turns on
    the app-lock middleware so unauthenticated requests get 401 "locked". The API
    tests hit endpoints without a session token, so neutralize the auth-gating
    settings here. Tests that specifically exercise sign-in override these in their
    own bodies (their monkeypatch runs after this autouse fixture, so it wins).
    """
    monkeypatch.setattr(settings, "google_web_client_id", "")
    monkeypatch.setattr(settings, "device_key", "")
    monkeypatch.setattr(settings, "app_pin_hash", None)
    monkeypatch.setattr(settings, "allowed_email", "")


@pytest_asyncio.fixture
async def session():
    engine, Session = new_session_factory("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    async with Session() as s:
        yield s
    await engine.dispose()
