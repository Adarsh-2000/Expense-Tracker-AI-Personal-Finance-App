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


async def test_get_categories_contains_food_after_swiggy_sms(client):
    """Ingest one Axis SWIGGY SMS (creates 'food_dining' category in the 16-cat
    taxonomy) then GET /categories should contain an object with int id + that name."""
    # Ingest an Axis SWIGGY SMS which should create the food_dining category
    resp = await client.post("/ingest/sms", json={
        "sender": "AX-AXISBK",
        "body": "INR 450 spent on AXIS BANK Credit Card no. XX1234 at SWIGGY on 01-06-2026.",
        "received_at": "2026-06-01T10:00:00",
        "external_id": "cat-test-1",
    })
    assert resp.status_code == 201

    # GET /categories should now contain food_dining
    cat_resp = await client.get("/categories")
    assert cat_resp.status_code == 200
    categories = cat_resp.json()
    assert isinstance(categories, list)
    food_cats = [c for c in categories if c["name"] == "food_dining"]
    assert len(food_cats) >= 1, f"Expected 'food_dining' category, got: {categories}"
    food = food_cats[0]
    assert isinstance(food["id"], int)
    assert food["name"] == "food_dining"
