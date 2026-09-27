from datetime import date, datetime, timedelta

import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.db import Base, new_session_factory
from app.deps import get_session
from app.main import app
from app.repositories import CardBillRepository


@pytest_asyncio.fixture
async def client_and_session():
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


async def _seed_bills(Session):
    soon = (date.today() + timedelta(days=3)).isoformat()
    later = (date.today() + timedelta(days=20)).isoformat()
    async with Session() as s:
        repo = CardBillRepository(s)
        await repo.upsert(
            bank="AXIS", last4=None, due_date=later, total_due=27040.74,
            min_due=9512.0, statement_period="FEBRUARY 2026", received_at=datetime.now(),
        )
        await repo.upsert(
            bank="IDFC", last4="0684", due_date=soon, total_due=9706.49,
            min_due=195.0, statement_period=None, received_at=datetime.now(),
        )
        await s.commit()
    return soon, later


async def test_list_bills_sorted_with_days_until_due(client_and_session):
    client, Session = client_and_session
    soon, later = await _seed_bills(Session)

    resp = await client.get("/bills")
    assert resp.status_code == 200
    bills = resp.json()
    assert len(bills) == 2
    # Sorted by due_date ascending -> the sooner (IDFC) bill first.
    assert bills[0]["bank"] == "IDFC"
    assert bills[1]["bank"] == "AXIS"
    assert bills[0]["days_until_due"] == 3
    assert bills[1]["days_until_due"] == 20
    assert bills[0]["total_due"] == 9706.49
    assert bills[1]["last4"] is None


async def test_mark_paid_excludes_bill(client_and_session):
    client, Session = client_and_session
    await _seed_bills(Session)

    bills = (await client.get("/bills")).json()
    idfc_id = next(b["id"] for b in bills if b["bank"] == "IDFC")

    resp = await client.post(f"/bills/{idfc_id}/paid")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}

    remaining = (await client.get("/bills")).json()
    assert len(remaining) == 1
    assert remaining[0]["bank"] == "AXIS"


async def test_mark_paid_missing_returns_404(client_and_session):
    client, _ = client_and_session
    resp = await client.post("/bills/999/paid")
    assert resp.status_code == 404


async def test_ingest_statement_creates_bill(client_and_session):
    client, Session = client_and_session
    idfc_reminder = (
        "Dear Cardmember, Your payment for the FIRST Millennia credit card ending in "
        "XX0684 is coming up soon. • Minimum Amount Due: ₹195.00 "
        "• Total Amount Due: ₹9706.49 • Payment Due Date: 05 May, 2026"
    )
    # Ingest via the email path (uses ingest_message internally is not exposed for
    # email directly, so exercise the bill upsert + reparse-bills recovery path).
    async with Session() as s:
        from app.entities import RawMessage
        s.add(RawMessage(
            channel="email", sender="alerts@idfcfirstbank.com", body=idfc_reminder,
            received_at=datetime(2026, 4, 30, 9, 0), external_id="bill1",
            parsed_status="needs_review",
        ))
        await s.commit()

    resp = await client.post("/admin/reparse-bills")
    assert resp.status_code == 200
    assert resp.json() == {"bills_found": 1}

    bills = (await client.get("/bills")).json()
    assert len(bills) == 1
    assert bills[0]["bank"] == "IDFC"
    assert bills[0]["last4"] == "0684"
    assert bills[0]["due_date"] == "2026-05-05"
    assert bills[0]["total_due"] == 9706.49
