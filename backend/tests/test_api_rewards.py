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
        yield c, Session
    app.dependency_overrides.clear()
    await engine.dispose()


async def test_rewards_endpoint_returns_card_with_value(client):
    c, Session = client
    from app.repositories import CardRewardRepository
    async with Session() as s:
        await CardRewardRepository(s).upsert_for_card(
            bank="IDFC", last4="0684", program="IDFC_FIRST", points_balance=12400,
            earned=300, redeemed=0, expiring_points=1250, expiring_date="2026-06-18",
            statement_period="MAY 2026", as_of="2026-06-02")
        await s.commit()

    r = await c.get("/rewards")
    assert r.status_code == 200
    body = r.json()
    assert body["total_points"] == 12400
    assert body["total_value_inr"] == 3100.0
    card = body["cards"][0]
    assert card["display_name"] == "FIRST Rewards"
    assert card["value_inr"] == 3100.0
    assert card["expiring_points"] == 1250
    assert any(o["label"] for o in card["redemption_options"])


async def test_rewards_empty(client):
    c, _ = client
    r = await c.get("/rewards")
    assert r.status_code == 200
    assert r.json() == {"total_value_inr": 0.0, "total_points": 0, "cards": []}


async def test_offer_categories_helper():
    from app.data.reward_catalogs import offer_categories_for
    idfc = offer_categories_for("IDFC_FIRST")
    assert idfc and all(o["title"] and o["kind"] for o in idfc)
    assert offer_categories_for("UNKNOWN") == []


async def test_rewards_card_includes_offers_and_portal(client):
    c, Session = client
    from app.repositories import CardRewardRepository
    async with Session() as s:
        await CardRewardRepository(s).upsert_for_card(
            bank="IDFC", last4="0684", program="IDFC_FIRST", points_balance=12400,
            earned=300, redeemed=0, expiring_points=0, expiring_date=None,
            statement_period="MAY 2026", as_of="2026-06-02")
        await s.commit()
    r = await c.get("/rewards")
    card = r.json()["cards"][0]
    assert card["portal_url"]
    assert card["offer_categories"]
    first = card["offer_categories"][0]
    assert first["title"] and first["description"] and first["kind"]
