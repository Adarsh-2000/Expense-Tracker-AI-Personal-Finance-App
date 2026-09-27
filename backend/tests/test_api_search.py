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


async def _seed(client):
    await client.post("/ingest/sms", json={
        "sender": "AX-AXISBK",
        "body": "INR 450 spent on AXIS BANK Credit Card no. XX1234 at SWIGGY on 01-06-2026.",
        "received_at": "2026-06-01T10:00:00",
        "external_id": "s1",
    })
    await client.post("/ingest/sms", json={
        "sender": "VM-IDFCFB",
        "body": "Transaction Successful! INR 1250.00 spent on your IDFC FIRST Bank Credit Card XX9876 at AMAZON on 02-AUG-2024 Available limit: INR 40000.00",
        "received_at": "2026-06-02T10:00:00",
        "external_id": "s2",
    })


async def test_search_by_merchant_keyword_returns_matching_transaction(client):
    await _seed(client)
    resp = await client.get("/transactions/search?q=swiggy")
    assert resp.status_code == 200
    results = resp.json()
    assert len(results) == 1
    assert results[0]["amount"] == 450.0


async def test_search_by_min_amount_filters_correctly(client):
    await _seed(client)
    resp = await client.get("/transactions/search?min_amount=1000")
    assert resp.status_code == 200
    results = resp.json()
    assert len(results) == 1
    assert results[0]["amount"] == 1250.0


async def test_search_by_max_amount_filters_correctly(client):
    await _seed(client)
    resp = await client.get("/transactions/search?max_amount=500")
    assert resp.status_code == 200
    results = resp.json()
    assert len(results) == 1
    assert results[0]["amount"] == 450.0


async def test_merchant_any_matches_any_significant_token(client):
    # Debits tagged "Hotstar" should be found when drilling a "Disney Hotstar" finding.
    await client.post("/transactions/manual", json={
        "amount": 149, "merchant": "HOTSTAR", "occurred_at": "2026-05-05T09:00:00"})
    await client.post("/transactions/manual", json={
        "amount": 500, "merchant": "SOMETHING ELSE", "occurred_at": "2026-05-06T09:00:00"})
    resp = await client.get("/transactions/search?merchant_any=Disney%20Hotstar")
    rows = resp.json()
    assert any(t["merchant"] == "HOTSTAR" for t in rows)
    assert all(t["merchant"] != "SOMETHING ELSE" for t in rows)


async def test_merchant_any_ignores_generic_stopwords(client):
    # "WEB UPI" has no significant tokens → falls back to exact substring, does NOT
    # match every UPI transaction.
    await client.post("/transactions/manual", json={
        "amount": 10, "merchant": "ZOMATO UPI", "occurred_at": "2026-05-05T09:00:00"})
    resp = await client.get("/transactions/search?merchant_any=WEB%20UPI")
    assert all(t["merchant"] != "ZOMATO UPI" for t in resp.json())
