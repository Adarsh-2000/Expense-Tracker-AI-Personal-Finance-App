from datetime import datetime

import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.db import Base, new_session_factory
from app.deps import get_session
from app.entities import RawMessage
from app.main import app


@pytest_asyncio.fixture
async def session_factory():
    engine, Session = new_session_factory("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield Session
    await engine.dispose()


@pytest_asyncio.fixture
async def client(session_factory):
    async def override():
        async with session_factory() as s:
            yield s

    app.dependency_overrides[get_session] = override
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c
    app.dependency_overrides.clear()


async def test_reparse_promotes_stored_review_messages(client, session_factory):
    # Insert a RawMessage stuck in needs_review whose body the NEW parser handles.
    parseable_body = (
        "Dear Cardmember, Happy Shopping! INR 281.00 spent on your IDFC FIRST BANK "
        "Credit Card ending XX2198 at Blinkit on 15 MAR 2026. Available Limit: "
        "INR 30014.09 . For any dispute please contact us."
    )
    async with session_factory() as s:
        s.add(RawMessage(
            channel="email", sender="idfcfirstbank.com", body=parseable_body,
            received_at=datetime(2026, 3, 15, 12, 0), external_id="rp1",
            parsed_status="needs_review",
        ))
        # Also one that genuinely cannot parse — should remain in review.
        s.add(RawMessage(
            channel="sms", sender="NOPE", body="junk", received_at=datetime(2026, 3, 1, 9, 0),
            external_id="rp2", parsed_status="needs_review",
        ))
        await s.commit()

    r = await client.post("/admin/reparse")
    assert r.status_code == 200
    data = r.json()
    assert data["reparsed"] >= 1
    assert data["remaining_review"] == 1

    # The promoted message now surfaces as a transaction.
    r = await client.get("/transactions")
    assert r.status_code == 200
    txns = r.json()
    assert any(t["amount"] == 281.00 for t in txns)
