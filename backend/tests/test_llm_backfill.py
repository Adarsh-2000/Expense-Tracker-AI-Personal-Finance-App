"""Tests for the opt-in LLM backfill classifier (no live LLM needed)."""
from app.enrichment import llm_backfill as B


def test_parse_accepts_canonical_only():
    assert B._parse("food_dining") == "food_dining"
    assert B._parse("food_dining\n") == "food_dining"
    assert B._parse("unknown") is None
    assert B._parse("banana") is None
    assert B._parse("") is None
    assert B._parse(None) is None


async def test_classify_texts_empty():
    assert await B.classify_texts([]) == []


class _FakeResp:
    def __init__(self, content):
        self._content = content

    def raise_for_status(self):
        pass

    def json(self):
        return {"choices": [{"message": {"content": self._content}}]}


class _FakeClient:
    def __init__(self, content):
        self._content = content

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def post(self, *args, **kwargs):
        return _FakeResp(self._content)


async def test_classify_texts_maps_valid_category(monkeypatch):
    monkeypatch.setattr(B.httpx, "AsyncClient", lambda *a, **k: _FakeClient("groceries"))
    assert await B.classify_texts(["BLINKIT order"]) == ["groceries"]


async def test_classify_texts_none_on_llm_error(monkeypatch):
    class _Boom:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *exc):
            return False

        async def post(self, *args, **kwargs):
            raise RuntimeError("down")

    monkeypatch.setattr(B.httpx, "AsyncClient", lambda *a, **k: _Boom())
    assert await B.classify_texts(["whatever"]) == [None]
