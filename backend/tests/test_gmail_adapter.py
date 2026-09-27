import base64
from datetime import datetime

from app.connectors.gmail import gmail_message_to_fields


def test_received_at_is_ist_wall_clock():
    # internalDate 1748764800000 ms == 2025-06-01 08:00:00 UTC == 13:30:00 IST.
    # Email occurred_at must be stored in Asia/Kolkata local wall-clock (naive) so
    # it matches the SMS local-time convention and cross-channel dedup lines up.
    msg = {
        "id": "gmail-123",
        "internalDate": "1748764800000",
        "payload": {"headers": [], "body": {"data": base64.urlsafe_b64encode(b"x").decode()}},
    }
    fields = gmail_message_to_fields(msg)
    assert fields["received_at"] == datetime(2025, 6, 1, 13, 30, 0)


def test_extracts_sender_body_and_id():
    raw_body = base64.urlsafe_b64encode(b"Spent Rs.10 At SHOP").decode()
    msg = {
        "id": "gmail-123",
        "internalDate": "1748764800000",
        "payload": {
            "headers": [{"name": "From", "value": "alerts@axisbank.com"}],
            "body": {"data": raw_body},
        },
    }
    fields = gmail_message_to_fields(msg)
    assert fields["external_id"] == "gmail-123"
    assert fields["sender"] == "alerts@axisbank.com"
    assert "Spent Rs.10" in fields["body"]
