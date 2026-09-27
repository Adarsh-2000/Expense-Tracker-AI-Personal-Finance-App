from datetime import datetime

from app.schemas import SmsIn


def test_sms_in_parses_iso_timestamp():
    payload = SmsIn(sender="AX-AXISBK", body="x", received_at="2026-06-01T10:00:00", external_id="sms-1")
    assert payload.received_at == datetime(2026, 6, 1, 10, 0)
