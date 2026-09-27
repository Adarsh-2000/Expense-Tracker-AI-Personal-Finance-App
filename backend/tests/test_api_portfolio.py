import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.services import gmail
from app.db import Base, new_session_factory
from app.deps import get_session
from app.main import app


@pytest.fixture(autouse=True)
def _no_us_network(monkeypatch):
    # Never hit Gmail for the US analytics email during tests (token.json may
    # exist in the dev cwd). Tests that exercise US sync override this.
    monkeypatch.setattr(gmail, "fetch_latest_us_analytics_data", lambda: None)


@pytest_asyncio.fixture
async def client_factory():
    engine, Session = new_session_factory("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async def override():
        async with Session() as s:
            yield s

    app.dependency_overrides[get_session] = override
    transport = ASGITransport(app=app)
    client = AsyncClient(transport=transport, base_url="http://test")
    yield client
    await client.aclose()
    app.dependency_overrides.clear()
    await engine.dispose()


async def test_sync_no_pan_then_portfolio_empty(client_factory):
    c = client_factory
    resp = await c.post("/sync/portfolio")
    assert resp.json()["status"] == "no_pan"

    port = await c.get("/portfolio")
    body = port.json()
    assert body["as_of"] is None
    assert body["total_value"] == 0
    assert body["count"] == 0
    assert body["holdings"] == []
    assert body["us_stocks"] is None
    assert body["grand_total_value"] == 0


async def test_sync_us_aggregate_and_portfolio(client_factory, monkeypatch):
    c = client_factory
    html = ('US Stock A/C ending with XXXX1347. Invested Value: $1.15 K '
            'Current Value: $1.65 K Absolute Returns: 43.72%')
    monkeypatch.setattr(gmail, "fetch_latest_us_analytics_data", lambda: (html, "2026-01-29"))
    monkeypatch.setattr(gmail, "fetch_latest_holding_pdf_bytes", lambda: None)

    resp = await c.post("/sync/portfolio")
    body = resp.json()
    assert body["us_stocks"]["current_usd"] == 1650.0

    port = (await c.get("/portfolio")).json()
    us = port["us_stocks"]
    assert us["current_usd"] == 1650.0
    assert us["invested_usd"] == 1150.0
    assert us["returns_pct"] == 43.72
    assert us["account_last4"] == "1347"
    assert us["current_inr"] == round(1650.0 * us["fx_rate"], 2)
    # No Indian holdings, so grand total == US INR value.
    assert port["grand_total_value"] == us["current_inr"]


async def test_sync_portfolio_stores_holdings(client_factory, monkeypatch):
    c = client_factory
    await c.put("/profile", json={"pan": "DXWPK1177G"})

    # Stub out Gmail download + parsing so no network/PDF is needed.
    monkeypatch.setattr(gmail, "fetch_latest_holding_pdf_bytes", lambda: b"%PDF-fake")

    def fake_parse(pdf_bytes, password):
        assert password == "DXWPK1177G"
        return {"as_of": "2026-04-30", "holdings": [
            {"isin": "INE814H01029", "name": "ADANI POWER", "quantity": 600.0,
             "rate": 222.05, "value": 133230.0, "type": "stock"},
            {"isin": "INF769K01DM9", "name": "MIRAE ETF", "quantity": 6132.442,
             "rate": 54.841, "value": 336309.252, "type": "mutual_fund"},
        ]}

    monkeypatch.setattr("app.parsing.holdings.parse_holding_statement", fake_parse)

    resp = await c.post("/sync/portfolio")
    body = resp.json()
    assert body["holdings"] == 2
    assert body["as_of"] == "2026-04-30"
    assert round(body["total_value"], 2) == 469539.25

    port = (await c.get("/portfolio")).json()
    assert port["count"] == 2
    assert port["as_of"] == "2026-04-30"
    # Sorted by value desc => MIRAE first.
    assert port["holdings"][0]["isin"] == "INF769K01DM9"
    assert round(port["total_value"], 2) == 469539.25


async def test_sync_no_statement(client_factory, monkeypatch):
    c = client_factory
    await c.put("/profile", json={"pan": "DXWPK1177G"})
    monkeypatch.setattr(gmail, "fetch_latest_holding_pdf_bytes", lambda: None)
    resp = await c.post("/sync/portfolio")
    assert resp.json()["status"] == "no_statement"
