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


async def test_report_months_lists_months_with_data_sorted_desc(client):
    await client.post("/transactions/manual", json={
        "amount": 450,
        "merchant": "SWIGGY",
        "occurred_at": "2026-06-10T09:00:00",
    })
    await client.post("/transactions/manual", json={
        "amount": 1000,
        "merchant": "AMAZON",
        "occurred_at": "2026-05-10T09:00:00",
    })

    resp = await client.get("/reports/months")
    assert resp.status_code == 200
    months = resp.json()["months"]
    assert "2026-06" in months
    assert "2026-05" in months
    # Sorted descending: latest month first.
    assert months.index("2026-06") < months.index("2026-05")
    assert months == sorted(months, reverse=True)
