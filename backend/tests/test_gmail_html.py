import base64

from app.connectors.gmail import gmail_message_to_fields


def test_html_body_is_flattened_to_text():
    html_body = (
        "<html><head><style>.x{color:red}</style></head>"
        "<body><p>INR&nbsp;450 spent on AXIS BANK Credit Card no. XX1234 "
        "at SWIGGY on 01-06-2026.</p>"
        "<script>track();</script></body></html>"
    )
    raw_body = base64.urlsafe_b64encode(html_body.encode()).decode()
    msg = {
        "id": "gmail-html-1",
        "internalDate": "1748764800000",
        "payload": {
            "headers": [{"name": "From", "value": "alerts@axisbank.com"}],
            "body": {"data": raw_body},
        },
    }
    fields = gmail_message_to_fields(msg)
    assert "INR 450 spent on AXIS BANK Credit Card no. XX1234 at SWIGGY" in fields["body"]
    # script/style content stripped, no tags remain
    assert "<" not in fields["body"]
    assert "track()" not in fields["body"]
    assert ".x{color" not in fields["body"]
