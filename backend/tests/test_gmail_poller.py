from app.connectors.gmail_poller import poll_messages


class FakeGmail:
    """Two-page fake: the first list() returns a nextPageToken, the second none."""

    def __init__(self, pages, bodies):
        # pages: list of id-lists, one per page (in order)
        self._pages = pages
        self._bodies = bodies
        self.list_calls = []

    def users(self):
        return self

    def messages(self):
        return self

    def list(self, userId, q, maxResults, pageToken=None):
        self.list_calls.append(pageToken)
        idx = 0 if pageToken is None else int(pageToken)
        ids = self._pages[idx]
        result = {"messages": [{"id": i} for i in ids]}
        if idx + 1 < len(self._pages):
            result["nextPageToken"] = str(idx + 1)
        return _Exec(result)

    def get(self, userId, id, format):
        return _Exec(self._bodies[id])


class _Exec:
    def __init__(self, value):
        self._value = value

    def execute(self):
        return self._value


def _body(msg_id):
    return {
        "id": msg_id,
        "internalDate": "1748764800000",
        "payload": {
            "headers": [{"name": "From", "value": "alerts@axisbank.com"}],
            "body": {"data": "U3BlbnQgUnMuMTA="},
        },
    }


def test_poll_returns_field_dicts():
    bodies = {"1": _body("1")}
    gmail = FakeGmail([["1"]], bodies)
    results = poll_messages(gmail, query="from:axisbank.com", max_results=10)
    assert len(results) == 1
    assert results[0]["external_id"] == "gmail-1"


def test_poll_paginates_across_pages():
    # Two pages: ids 1,2 on page one; ids 3,4 on page two. All four should be fetched.
    bodies = {i: _body(i) for i in ("1", "2", "3", "4")}
    gmail = FakeGmail([["1", "2"], ["3", "4"]], bodies)
    results = poll_messages(gmail, query="from:axisbank.com", max_results=2000, page_size=2)
    assert len(results) == 4
    assert {r["external_id"] for r in results} == {"gmail-1", "gmail-2", "gmail-3", "gmail-4"}
    # Exercised two list() calls: first with no token, second with the page token.
    assert gmail.list_calls == [None, "1"]


def test_poll_respects_max_results_cap():
    bodies = {i: _body(i) for i in ("1", "2", "3", "4")}
    gmail = FakeGmail([["1", "2"], ["3", "4"]], bodies)
    results = poll_messages(gmail, query="from:axisbank.com", max_results=3, page_size=2)
    assert len(results) == 3
