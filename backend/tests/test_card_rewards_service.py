import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.db import Base, new_session_factory
from app.deps import get_session
from app.main import app


@pytest_asyncio.fixture
async def session_maker():
    engine, Session = new_session_factory("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield Session
    await engine.dispose()


async def test_profile_upsert_persists_name(session_maker):
    from app.repositories import ProfileRepository

    async with session_maker() as s:
        await ProfileRepository(s).upsert(pan="DXWPK1177G", dob="29-03-1998", name="Harsh Khandelwal")
        await s.commit()
    async with session_maker() as s:
        p = await ProfileRepository(s).get()
        assert p.name == "Harsh Khandelwal"
        assert p.dob == "29-03-1998"


async def test_card_reward_upsert_replaces_per_card(session_maker):
    from app.repositories import CardRewardRepository

    async with session_maker() as s:
        repo = CardRewardRepository(s)
        await repo.upsert_for_card(bank="IDFC", last4="0684", program="IDFC_FIRST",
                                   points_balance=12400, earned=300, redeemed=0,
                                   expiring_points=1250, expiring_date="2026-06-18",
                                   statement_period="MAY 2026", as_of="2026-06-02")
        await repo.upsert_for_card(bank="IDFC", last4="0684", program="IDFC_FIRST",
                                   points_balance=12700, earned=300, redeemed=0,
                                   expiring_points=0, expiring_date=None,
                                   statement_period="JUN 2026", as_of="2026-07-02")
        await s.commit()
        rows = await repo.list_all()
        assert len(rows) == 1
        assert rows[0].points_balance == 12700
        assert rows[0].as_of == "2026-07-02"


async def test_reward_event_dedup(session_maker):
    from app.repositories import RewardEventRepository

    async with session_maker() as s:
        repo = RewardEventRepository(s)
        ev = dict(bank="IDFC", last4="0684", points=1780, merchant="MAKEMYTRIP",
                  occurred_at="2026-05-20", voucher_code=None, voucher_expiry=None,
                  kind="redemption")
        assert await repo.add_if_new(**ev) is True
        assert await repo.add_if_new(**ev) is False
        await s.commit()
        assert len(await repo.list_recent(10)) == 1


async def test_sync_card_rewards_parses_messages_without_gmail(session_maker, monkeypatch):
    from app.entities import RawMessage
    from app.repositories import RewardEventRepository
    from app.services.card_rewards import sync_card_rewards

    async with session_maker() as s:
        s.add(RawMessage(channel="sms", sender="IDFCFB",
                         body=("Congrats! 1780 reward points redeemed from your IDFC FIRST "
                               "Bank Credit Card XX0684 at MAKEMYTRIP INDIA PVT L . "
                               "Available Reward Points: 0"),
                         received_at=__import__("datetime").datetime(2026, 5, 20),
                         external_id="m1", parsed_status="parsed"))
        await s.commit()
        result = await sync_card_rewards(s)
        await s.commit()
        assert result["status"] == "ok"
        events = await RewardEventRepository(s).list_recent(10)
        assert any(e.points == 1780 and e.last4 == "0684" for e in events)


async def test_sync_gmail_invokes_card_rewards(session_maker, monkeypatch):
    import app.routers.sync as sync_router
    from app.deps import get_session
    from app.main import app
    from httpx import ASGITransport, AsyncClient

    calls = {"n": 0}

    async def fake(session):
        calls["n"] += 1
        return {"status": "ok"}

    monkeypatch.setattr(sync_router, "sync_card_rewards", fake, raising=False)

    async def override():
        async with session_maker() as s:
            yield s

    app.dependency_overrides[get_session] = override
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post("/sync/gmail")
    app.dependency_overrides.clear()
    assert r.status_code == 200
    assert calls["n"] == 1


async def test_reward_expiry_notification(session_maker):
    from datetime import date, timedelta
    from app.repositories import CardRewardRepository
    from app.services.notifications import build_notifications

    soon = (date.today() + timedelta(days=10)).isoformat()
    async with session_maker() as s:
        await CardRewardRepository(s).upsert_for_card(
            bank="IDFC", last4="0684", program="IDFC_FIRST", points_balance=12400,
            earned=0, redeemed=0, expiring_points=1250, expiring_date=soon,
            statement_period="MAY 2026", as_of=date.today().isoformat())
        await s.commit()
        items = await build_notifications(s)
        reward = [i for i in items if i["type"] == "reward"]
        assert reward, "expected a reward-expiry notification"
        assert reward[0]["route"] == "rewards"
        assert "1,250" in reward[0]["body"] or "1250" in reward[0]["body"]
