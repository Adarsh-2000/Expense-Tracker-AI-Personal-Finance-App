from datetime import datetime

from sqlalchemy import select

from app.entities import RawMessage, Transaction
from app.ingestion_service import IngestionService
from app.parsing.registry import default_registry


def _service(session):
    return IngestionService(session=session, registry=default_registry(), window_hours=36)


async def test_ingest_unknown_bank_goes_to_review(session):
    svc = _service(session)
    txn = await svc.ingest_message(
        channel="sms", sender="UNKNOWN", body="random text",
        received_at=datetime(2026, 6, 1), external_id="x1",
    )
    await session.flush()
    assert txn is None
    msg = (await session.execute(select(RawMessage).where(RawMessage.external_id == "x1"))).scalar_one()
    assert msg.parsed_status == "needs_review"


async def test_ingest_known_bank_creates_transaction(session):
    svc = _service(session)
    txn = await svc.ingest_message(
        channel="sms", sender="AX-AXISBK",
        body="INR 450 spent on AXIS BANK Credit Card no. XX1234 at SWIGGY on 01-06-2026.",
        received_at=datetime(2026, 6, 1, 10, 0), external_id="h1",
    )
    await session.flush()
    assert txn is not None and txn.amount == 450.0


async def test_ingest_is_idempotent_on_external_id(session):
    svc = _service(session)
    body = "INR 450 spent on AXIS BANK Credit Card no. XX1234 at SWIGGY on 01-06-2026."
    await svc.ingest_message(channel="sms", sender="AX-AXISBK", body=body, received_at=datetime(2026, 6, 1, 10, 0), external_id="dup")
    await svc.ingest_message(channel="sms", sender="AX-AXISBK", body=body, received_at=datetime(2026, 6, 1, 10, 0), external_id="dup")
    await session.flush()
    count = len((await session.execute(select(Transaction))).scalars().all())
    assert count == 1


async def test_parser_crash_routes_to_review(session):
    class BoomRegistry:
        def parse(self, sender, body, received_at):
            raise ValueError("boom")
    svc = IngestionService(session=session, registry=BoomRegistry(), window_hours=36)
    txn = await svc.ingest_message(
        channel="sms", sender="AX-AXISBK", body="anything",
        received_at=datetime(2026, 6, 1), external_id="boom1",
    )
    await session.flush()
    assert txn is None
    msg = (await session.execute(select(RawMessage).where(RawMessage.external_id == "boom1"))).scalar_one()
    assert msg.parsed_status == "needs_review"
