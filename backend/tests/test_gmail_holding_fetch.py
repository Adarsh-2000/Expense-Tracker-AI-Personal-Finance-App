import base64

from app.connectors.gmail_poller import _find_pdf_part, fetch_latest_holding_pdf


class _FakeExec:
    def __init__(self, result):
        self._result = result

    def execute(self):
        return self._result


class _FakeAttachments:
    def __init__(self, data):
        self._data = data

    def get(self, *, userId, messageId, id):
        return _FakeExec({"data": self._data})


class _FakeMessages:
    def __init__(self, listing, message, att_data):
        self._listing = listing
        self._message = message
        self._att_data = att_data

    def list(self, **kwargs):
        return _FakeExec(self._listing)

    def get(self, **kwargs):
        return _FakeExec(self._message)

    def attachments(self):
        return _FakeAttachments(self._att_data)


class _FakeUsers:
    def __init__(self, messages):
        self._messages = messages

    def messages(self):
        return self._messages


class _FakeService:
    def __init__(self, listing, message, att_data):
        self._users = _FakeUsers(_FakeMessages(listing, message, att_data))

    def users(self):
        return self._users


def test_find_pdf_part_walks_tree():
    payload = {"parts": [
        {"filename": "body.html", "body": {"data": "x"}},
        {"filename": "statement.PDF", "body": {"attachmentId": "att-1"}},
    ]}
    part = _find_pdf_part(payload)
    assert part["body"]["attachmentId"] == "att-1"


def test_fetch_latest_holding_pdf_decodes_attachment():
    raw = b"%PDF-1.7 fake bytes"
    encoded = base64.urlsafe_b64encode(raw).decode()
    service = _FakeService(
        listing={"messages": [{"id": "m1"}]},
        message={"payload": {"parts": [
            {"filename": "holding.pdf", "body": {"attachmentId": "att-1"}},
        ]}},
        att_data=encoded,
    )
    assert fetch_latest_holding_pdf(service) == raw


def test_fetch_latest_holding_pdf_none_when_no_messages():
    service = _FakeService(listing={}, message={}, att_data="")
    assert fetch_latest_holding_pdf(service) is None
