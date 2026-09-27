import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.db import Base, new_session_factory
from app.deps import get_session
from app.main import app


@pytest_asyncio.fixture
async def client():
    engine, Session = new_session_factory("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async def override():
        async with Session() as s:
            yield s

    app.dependency_overrides[get_session] = override
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c
    app.dependency_overrides.clear()
    await engine.dispose()


async def test_summary_enabled_by_default(client):
    # The AI Insights screen is ON by default (insights_summary_enabled=True). Without a
    # reachable LLM in the test env it reports available=false, but never enabled=false.
    resp = await client.get("/insights/summary?month=2026-05")
    assert resp.status_code == 200
    body = resp.json()
    assert body["enabled"] is True
    assert body["period"] == "2026-05"


async def test_summary_rejects_bad_month(client):
    resp = await client.get("/insights/summary?month=2026-5")
    assert resp.status_code == 422


async def test_summary_defaults_to_current_month(client):
    resp = await client.get("/insights/summary")
    assert resp.status_code == 200
    assert len(resp.json()["period"]) == 7
