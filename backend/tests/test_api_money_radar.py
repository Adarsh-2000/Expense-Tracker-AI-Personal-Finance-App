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


async def _seed_mandate(client):
    await client.post("/ingest/sms", json={
        "sender": "AXISBK", "external_id": "sms-m1",
        "body": "UPI mandate set up for Spotify India for INR 119.00. Not you?",
        "received_at": "2026-04-15T10:00:00"})


async def test_scan_then_feed_and_summary(client):
    await _seed_mandate(client)
    scan = await client.post("/money-radar/scan")
    assert scan.status_code == 200

    feed = (await client.get("/money-radar")).json()
    assert feed["new_count"] >= 1
    assert any(f["type"] == "mandate" for f in feed["findings"])
    assert feed["findings"][0]["query"] is None or isinstance(feed["findings"][0]["query"], dict)

    summary = (await client.get("/money-radar/summary")).json()
    assert summary["new_count"] == feed["new_count"]
    assert len(summary["top"]) <= 3


async def test_seen_then_dismiss(client):
    await _seed_mandate(client)
    await client.post("/money-radar/scan")
    fid = (await client.get("/money-radar")).json()["findings"][0]["id"]

    await client.post("/money-radar/seen")
    assert (await client.get("/money-radar")).json()["new_count"] == 0

    await client.post(f"/money-radar/{fid}/dismiss")
    assert all(f["id"] != fid for f in (await client.get("/money-radar")).json()["findings"])


async def test_findings_appear_in_notifications(client):
    await _seed_mandate(client)
    await client.post("/money-radar/scan")
    notifs = (await client.get("/notifications")).json()["notifications"]
    radar = [n for n in notifs if n["type"] == "money_radar"]
    assert radar, "expected a money_radar notification"
    assert radar[0]["route"] == "money_radar"
    # Stable id == the finding's dedup_key (client dedups on it).
    assert radar[0]["id"].startswith("mandate:")
    assert any(n["type"] == "digest" and n["route"] == "money_radar" for n in notifs)


async def test_sms_ingest_triggers_scan(client):
    # Posting a mandate SMS should auto-produce a finding without an explicit scan.
    await client.post("/ingest/sms", json={
        "sender": "AXISBK", "external_id": "sms-auto1",
        "body": "UPI mandate set up for Netflix India for INR 649.00. Not you?",
        "received_at": "2026-04-15T10:00:00"})
    feed = (await client.get("/money-radar")).json()
    assert any(f["type"] == "mandate" for f in feed["findings"])


async def test_ingest_survives_scan_failure(client, monkeypatch):
    async def boom(self):
        raise RuntimeError("detector blew up")
    monkeypatch.setattr("app.enrichment.money_radar.MoneyRadarService.scan", boom)
    resp = await client.post("/ingest/sms", json={
        "sender": "AXISBK", "external_id": "sms-boom1",
        "body": "Spent Rs 200 at CAFE on Axis Bank Credit Card XX9966",
        "received_at": "2026-04-15T10:00:00"})
    assert resp.status_code < 500   # ingest must not fail because enrichment did
