"""Unit tests for the LLM insight narrator's safety rails (no live LLM needed)."""
from app.config import settings
from app.enrichment import insight_narrator as N


def test_digits_strips_grouping_separators():
    assert N._digits("₹3,39,803 and 5 items") == {"339803", "5"}


def test_card_numbers_reads_title_and_detail():
    card = {"title": "Food ₹12,500", "detail": "20% above ₹10,000/mo"}
    assert N._card_numbers(card) == {"12500", "20", "10000"}


async def test_narration_disabled_is_noop(monkeypatch):
    monkeypatch.setattr(settings, "insights_narrate", False)
    cards = [{"title": "x ₹100", "detail": ""}]
    assert await N.narrate_cards(cards) is cards


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


def _patch_llm(monkeypatch, content):
    monkeypatch.setattr(settings, "insights_narrate", True)
    monkeypatch.setattr(N.httpx, "AsyncClient", lambda *a, **k: _FakeClient(content))


async def test_faithful_narration_is_added_and_original_kept(monkeypatch):
    _patch_llm(monkeypatch, "Heads up — you spent ₹12,500, about 20% over your ₹10,000 norm.")
    card = {"title": "Food ₹12,500", "detail": "20% above ₹10,000"}
    out = (await N.narrate_cards([card]))[0]
    assert out["narration"].startswith("Heads up")
    assert out["title"] == card["title"]      # exact figures untouched
    assert out["detail"] == card["detail"]


async def test_number_drift_keeps_exact_card(monkeypatch):
    _patch_llm(monkeypatch, "You spent ₹99,999 this month.")   # invented number
    card = {"title": "Food ₹12,500", "detail": ""}
    out = (await N.narrate_cards([card]))[0]
    assert "narration" not in out             # guard rejected the drift


async def test_dropped_number_keeps_exact_card(monkeypatch):
    _patch_llm(monkeypatch, "You spent a lot on food this month.")   # omits 12500
    card = {"title": "Food ₹12,500", "detail": ""}
    out = (await N.narrate_cards([card]))[0]
    assert "narration" not in out


async def test_llm_failure_keeps_exact_card(monkeypatch):
    monkeypatch.setattr(settings, "insights_narrate", True)

    class _BoomClient:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *exc):
            return False

        async def post(self, *args, **kwargs):
            raise RuntimeError("connection refused")

    monkeypatch.setattr(N.httpx, "AsyncClient", lambda *a, **k: _BoomClient())
    out = (await N.narrate_cards([{"title": "Food ₹12,500", "detail": ""}]))[0]
    assert "narration" not in out
