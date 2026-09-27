import app.services.insight_summary_service as S

_TEXT = ("### Spending Patterns\nA.\n### How To Save More\nB.\n### Money Radar\nC.")


def _patch_common(monkeypatch, payload):
    monkeypatch.setattr(S.settings, "insights_summary_enabled", True)
    monkeypatch.setattr(S.settings, "llm_model", "m1")
    monkeypatch.setattr(S, "build_payload", lambda rows, period: payload)


async def test_disabled_is_noop(session, monkeypatch):
    monkeypatch.setattr(S.settings, "insights_summary_enabled", False)
    r = await S.get_summary(session, "2026-05")
    assert r["enabled"] is False and r["available"] is False
    assert r["spending_patterns"] == ""


async def test_generates_then_caches(session, monkeypatch):
    _patch_common(monkeypatch, {"x": 1})
    calls = {"n": 0}

    async def fake_gen(rows, period):
        calls["n"] += 1
        return {"text": _TEXT, "ok": True}

    monkeypatch.setattr(S, "generate_insights", fake_gen)

    r1 = await S.get_summary(session, "2026-05")
    assert calls["n"] == 1 and r1["available"] is True
    assert r1["spending_patterns"] == "A." and r1["money_radar"] == "C."

    r2 = await S.get_summary(session, "2026-05")
    assert calls["n"] == 1 and r2["spending_patterns"] == "A."


async def test_data_change_regenerates(session, monkeypatch):
    payload = {"x": 1}
    _patch_common(monkeypatch, payload)
    calls = {"n": 0}

    async def fake_gen(rows, period):
        calls["n"] += 1
        return {"text": _TEXT, "ok": True}

    monkeypatch.setattr(S, "generate_insights", fake_gen)
    await S.get_summary(session, "2026-05")
    assert calls["n"] == 1
    monkeypatch.setattr(S, "build_payload", lambda rows, period: {"x": 2})
    await S.get_summary(session, "2026-05")
    assert calls["n"] == 2


async def test_llm_down_no_cache_is_unavailable(session, monkeypatch):
    _patch_common(monkeypatch, {"x": 1})

    async def fake_gen(rows, period):
        return {"text": "", "ok": False}

    monkeypatch.setattr(S, "generate_insights", fake_gen)
    r = await S.get_summary(session, "2026-05")
    assert r["enabled"] is True and r["available"] is False
