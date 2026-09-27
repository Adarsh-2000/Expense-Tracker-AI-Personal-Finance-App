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
        "external_id": "x1",
    })
    await client.post("/ingest/sms", json={
        "sender": "VM-IDFCFB",
        "body": "Transaction Successful! INR 1250.00 spent on your IDFC FIRST Bank Credit Card XX9876 at AMAZON on 02-AUG-2024 Available limit: INR 40000.00",
        "received_at": "2026-06-02T10:00:00",
        "external_id": "x2",
    })


async def test_export_csv_returns_200_with_csv_content_type(client):
    await _seed(client)
    resp = await client.get("/export/transactions.csv")
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("text/csv")


async def test_export_csv_body_has_header_plus_two_data_rows(client):
    await _seed(client)
    resp = await client.get("/export/transactions.csv")
    lines = [line for line in resp.text.splitlines() if line.strip()]
    assert len(lines) == 3  # 1 header + 2 data rows


async def test_export_csv_header_contains_expected_columns(client):
    await _seed(client)
    resp = await client.get("/export/transactions.csv")
    header = resp.text.splitlines()[0]
    assert "id" in header
    assert "merchant" in header
    assert "amount" in header
    assert "category" in header
