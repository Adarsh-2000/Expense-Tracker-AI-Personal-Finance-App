# LLM Insight Summaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `GET /insights/summary?month=YYYY-MM` to the expense-tracker backend, returning three LLM-generated qualitative insight sections (Spending Patterns / How To Save More / Money Radar) per calendar month, generated from deterministic stats and cached so they run at most once per data change, behind an off-by-default flag.

**Architecture:** A caching service hashes the deterministic `build_payload` output for a month; on a hash/model match it returns the stored sections, otherwise it calls the existing (guarded) `generate_insights`, splits the markdown into three fields, and upserts them. New `insight_summaries` table; reuses the already-benchmarked `insights_llm.py`. Off by default via `settings.insights_narrate`; never 5xx on LLM failure.

**Tech Stack:** Python 3.11+, FastAPI, async SQLAlchemy 2.0 (`Mapped`/`mapped_column`), pytest + pytest-asyncio (`asyncio_mode=auto`), local OpenAI-compatible LLM via `settings.llm_url`.

---

## Reference facts (verified against the codebase)

- Run tests with the backend venv: `cd /Users/harshkhandelwal/Documents/expense-tracker/backend && .venv/bin/python -m pytest <path> -q`.
- `tests/conftest.py` provides an autouse `_disable_app_lock` fixture (so API tests skip the PIN lock) and an async `session` fixture (in-memory sqlite, `Base.metadata.create_all`). New tables are created automatically.
- `TransactionRepository(session).analytics_rows(start, end, direction="debit")` returns `list[dict]` with keys `merchant, amount(float), occurred_at(datetime), category` — exactly what `insights_llm.build_payload(rows, period)` consumes.
- `app/enrichment/insights_llm.py` already exposes `build_payload(rows, period) -> dict`, `generate_insights(rows, period) -> {"text","payload","raw","stripped","ok"}` (ok False on LLM failure, text already guarded), and `ground_guard`.
- `app/config.py` already has `insights_narrate: bool = False`, `llm_url`, `llm_model`.
- Repository pattern: `class XRepository: def __init__(self, session: AsyncSession): self.session = session`. Entities: `Mapped[T] = mapped_column(...)`, `from app.db import Base`. `String`, `DateTime`, `Integer` are imported in `entities.py`; `datetime` is imported there too.
- Commit message convention: end with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## Task 0: Feature branch

**Files:** none (git only)

- [ ] **Step 1: Create the branch**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git checkout -b feature/llm-insight-summaries
git branch --show-current   # expect: feature/llm-insight-summaries
```

(Uncommitted classifier-integration changes in the working tree move with you — that's fine; this plan only adds new files plus three small edits.)

---

## Task 1: `split_sections` in `insights_llm.py`

Pure function that splits the LLM's 3-section markdown into a dict. Handles both `### Header` and `**Header:**` forms; a missing header yields an empty string.

**Files:**
- Modify: `backend/app/enrichment/insights_llm.py`
- Test: `backend/tests/test_insights_llm.py` (append)

- [ ] **Step 1: Write the failing tests** (append to `tests/test_insights_llm.py`)

```python
def test_split_sections_hash_headers():
    text = ("### Spending Patterns\nYou spent a lot.\n"
            "### How To Save More\nTrim subscriptions.\n"
            "### Money Radar\nWatch CRED.")
    s = L.split_sections(text)
    assert s["spending_patterns"] == "You spent a lot."
    assert s["how_to_save"] == "Trim subscriptions."
    assert s["money_radar"] == "Watch CRED."


def test_split_sections_bold_headers():
    text = ("**Spending Patterns:**  \nLine A.\n\n"
            "**How To Save More:**  \nLine B.\n\n"
            "**Money Radar:**  \nLine C.")
    s = L.split_sections(text)
    assert s["spending_patterns"] == "Line A."
    assert s["how_to_save"] == "Line B."
    assert s["money_radar"] == "Line C."


def test_split_sections_missing_header_is_empty():
    text = "### Spending Patterns\nOnly this one."
    s = L.split_sections(text)
    assert s["spending_patterns"] == "Only this one."
    assert s["how_to_save"] == ""
    assert s["money_radar"] == ""


def test_split_sections_no_headers_all_empty():
    assert L.split_sections("just prose, no headers") == {
        "spending_patterns": "", "how_to_save": "", "money_radar": ""}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /Users/harshkhandelwal/Documents/expense-tracker/backend && .venv/bin/python -m pytest tests/test_insights_llm.py -q`
Expected: FAIL (`AttributeError: module ... has no attribute 'split_sections'`).

- [ ] **Step 3: Implement `split_sections`** (append to `app/enrichment/insights_llm.py`)

```python
_SECTION_HEADERS = re.compile(
    r"^[ \t]*(?:#{1,6}[ \t]*|\*\*[ \t]*)?"
    r"(spending patterns|how to save more|money radar)"
    r"[ \t]*:?[ \t]*\*{0,2}[ \t]*$",
    re.IGNORECASE | re.MULTILINE,
)
_LABEL_TO_KEY = {
    "spending patterns": "spending_patterns",
    "how to save more": "how_to_save",
    "money radar": "money_radar",
}


def split_sections(text: str) -> dict[str, str]:
    """Split the LLM's 3-section markdown into {spending_patterns, how_to_save,
    money_radar}. Accepts '### Header' or '**Header:**'. Missing header -> ''."""
    out = {"spending_patterns": "", "how_to_save": "", "money_radar": ""}
    matches = list(_SECTION_HEADERS.finditer(text or ""))
    for i, m in enumerate(matches):
        key = _LABEL_TO_KEY[m.group(1).lower()]
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        out[key] = text[m.end():end].strip()
    return out
```

(`re` is already imported at the top of `insights_llm.py`.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.venv/bin/python -m pytest tests/test_insights_llm.py -q`
Expected: PASS (the 4 new tests plus the existing ones).

- [ ] **Step 5: Commit**

```bash
git add app/enrichment/insights_llm.py tests/test_insights_llm.py
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "feat(insights): split_sections to parse 3-section LLM output

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: `InsightSummary` entity

**Files:**
- Modify: `backend/app/entities.py` (append a class)
- Test: `backend/tests/test_insight_summary_repo.py` (created in Task 3 covers it)

- [ ] **Step 1: Append the entity to `app/entities.py`**

```python
class InsightSummary(Base):
    __tablename__ = "insight_summaries"

    id: Mapped[int] = mapped_column(primary_key=True)
    period: Mapped[str] = mapped_column(String(7), unique=True)        # "YYYY-MM"
    payload_hash: Mapped[str] = mapped_column(String(64))
    model: Mapped[str] = mapped_column(String(128))
    spending_patterns: Mapped[str] = mapped_column(String, default="")
    how_to_save: Mapped[str] = mapped_column(String, default="")
    money_radar: Mapped[str] = mapped_column(String, default="")
    generated_at: Mapped[datetime] = mapped_column(DateTime)
```

(`Mapped`, `mapped_column`, `String`, `DateTime`, `datetime`, and `Base` are all already imported in `entities.py`.)

- [ ] **Step 2: Verify it imports and the table is registered**

Run:
```bash
.venv/bin/python -c "from app.entities import InsightSummary; from app.db import Base; assert 'insight_summaries' in Base.metadata.tables; print('ok')"
```
Expected: `ok`.

- [ ] **Step 3: Commit**

```bash
git add app/entities.py
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "feat(insights): InsightSummary entity (insight_summaries table)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: `InsightSummaryRepository`

**Files:**
- Modify: `backend/app/repositories.py` (add import + append class)
- Test: `backend/tests/test_insight_summary_repo.py` (create)

- [ ] **Step 1: Write the failing test** (`tests/test_insight_summary_repo.py`)

```python
from app.repositories import InsightSummaryRepository


async def test_upsert_then_get_single_row(session):
    repo = InsightSummaryRepository(session)
    row = await repo.upsert("2026-05", "hash1", "m1",
                            spending_patterns="A", how_to_save="B", money_radar="C")
    assert row.period == "2026-05" and row.payload_hash == "hash1"
    assert row.generated_at is not None

    got = await repo.get("2026-05")
    assert got.spending_patterns == "A" and got.money_radar == "C"


async def test_upsert_updates_in_place(session):
    repo = InsightSummaryRepository(session)
    await repo.upsert("2026-05", "hash1", "m1", spending_patterns="A",
                      how_to_save="B", money_radar="C")
    await repo.upsert("2026-05", "hash2", "m2", spending_patterns="X",
                      how_to_save="Y", money_radar="Z")
    got = await repo.get("2026-05")
    assert got.payload_hash == "hash2" and got.model == "m2" and got.spending_patterns == "X"


async def test_get_missing_returns_none(session):
    assert await InsightSummaryRepository(session).get("1999-01") is None
```

- [ ] **Step 2: Run to verify it fails**

Run: `.venv/bin/python -m pytest tests/test_insight_summary_repo.py -q`
Expected: FAIL (`ImportError: cannot import name 'InsightSummaryRepository'`).

- [ ] **Step 3: Implement the repository** (in `app/repositories.py`)

At the top of the file, ensure these imports exist (add any that are missing):
```python
from datetime import datetime
from app.entities import InsightSummary
```

Append the class at the end of `app/repositories.py`:
```python
class InsightSummaryRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def get(self, period: str) -> InsightSummary | None:
        stmt = select(InsightSummary).where(InsightSummary.period == period)
        return (await self.session.execute(stmt)).scalar_one_or_none()

    async def upsert(self, period: str, payload_hash: str, model: str, *,
                     spending_patterns: str, how_to_save: str,
                     money_radar: str) -> InsightSummary:
        row = await self.get(period)
        if row is None:
            row = InsightSummary(period=period)
            self.session.add(row)
        row.payload_hash = payload_hash
        row.model = model
        row.spending_patterns = spending_patterns
        row.how_to_save = how_to_save
        row.money_radar = money_radar
        row.generated_at = datetime.now()
        await self.session.flush()
        return row
```

(`select` and `AsyncSession` are already imported in `repositories.py`.)

- [ ] **Step 4: Run to verify it passes**

Run: `.venv/bin/python -m pytest tests/test_insight_summary_repo.py -q`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/repositories.py tests/test_insight_summary_repo.py
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "feat(insights): InsightSummaryRepository (get/upsert)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: `InsightSummaryService` (caching logic)

**Files:**
- Create: `backend/app/services/insight_summary_service.py`
- Test: `backend/tests/test_insight_summary_service.py`

- [ ] **Step 1: Write the failing tests** (`tests/test_insight_summary_service.py`)

```python
import app.services.insight_summary_service as S

_TEXT = ("### Spending Patterns\nA.\n### How To Save More\nB.\n### Money Radar\nC.")


def _patch_common(monkeypatch, payload):
    monkeypatch.setattr(S.settings, "insights_narrate", True)
    monkeypatch.setattr(S.settings, "llm_model", "m1")
    monkeypatch.setattr(S, "build_payload", lambda rows, period: payload)


async def test_disabled_is_noop(session, monkeypatch):
    monkeypatch.setattr(S.settings, "insights_narrate", False)
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

    r2 = await S.get_summary(session, "2026-05")   # same payload+model -> cache hit
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
    monkeypatch.setattr(S, "build_payload", lambda rows, period: {"x": 2})  # data changed
    await S.get_summary(session, "2026-05")
    assert calls["n"] == 2


async def test_llm_down_no_cache_is_unavailable(session, monkeypatch):
    _patch_common(monkeypatch, {"x": 1})

    async def fake_gen(rows, period):
        return {"text": "", "ok": False}

    monkeypatch.setattr(S, "generate_insights", fake_gen)
    r = await S.get_summary(session, "2026-05")
    assert r["enabled"] is True and r["available"] is False
```

- [ ] **Step 2: Run to verify it fails**

Run: `.venv/bin/python -m pytest tests/test_insight_summary_service.py -q`
Expected: FAIL (`ModuleNotFoundError: app.services.insight_summary_service`).

- [ ] **Step 3: Implement the service** (`app/services/insight_summary_service.py`)

```python
"""Per-month LLM insight summaries with deterministic, hash-based caching.

Lazy: generated on first request for a month and cached; regenerated only when that
month's transactions change (payload hash) or the model changes. No scheduler.
"""
from __future__ import annotations

import hashlib
import json
from datetime import datetime

from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.enrichment.insights_llm import build_payload, generate_insights, split_sections
from app.repositories import InsightSummaryRepository, TransactionRepository

_EMPTY = {"spending_patterns": "", "how_to_save": "", "money_radar": ""}


def _month_bounds(period: str) -> tuple[datetime, datetime]:
    y, m = int(period[:4]), int(period[5:7])
    start = datetime(y, m, 1)
    end = datetime(y + 1, 1, 1) if m == 12 else datetime(y, m + 1, 1)
    return start, end


def _payload_hash(payload: dict) -> str:
    return hashlib.sha256(
        json.dumps(payload, sort_keys=True, ensure_ascii=False).encode()
    ).hexdigest()


def _resp(period, *, enabled, available, sections, model, generated_at) -> dict:
    return {
        "period": period, "enabled": enabled, "available": available, "model": model,
        "generated_at": generated_at.isoformat() if generated_at else None,
        "spending_patterns": sections["spending_patterns"],
        "how_to_save": sections["how_to_save"],
        "money_radar": sections["money_radar"],
    }


async def get_summary(session: AsyncSession, period: str) -> dict:
    if not settings.insights_narrate:
        return _resp(period, enabled=False, available=False, sections=dict(_EMPTY),
                     model=settings.llm_model, generated_at=None)

    start, end = _month_bounds(period)
    rows = await TransactionRepository(session).analytics_rows(start, end)
    payload = build_payload(rows, period)
    h = _payload_hash(payload)

    repo = InsightSummaryRepository(session)
    row = await repo.get(period)
    if row is not None and row.payload_hash == h and row.model == settings.llm_model:
        return _resp(period, enabled=True, available=True, model=row.model,
                     generated_at=row.generated_at,
                     sections={"spending_patterns": row.spending_patterns,
                               "how_to_save": row.how_to_save,
                               "money_radar": row.money_radar})

    res = await generate_insights(rows, period)
    if not res["ok"]:
        if row is not None:  # LLM down but an older summary exists -> serve it
            return _resp(period, enabled=True, available=True, model=row.model,
                         generated_at=row.generated_at,
                         sections={"spending_patterns": row.spending_patterns,
                                   "how_to_save": row.how_to_save,
                                   "money_radar": row.money_radar})
        return _resp(period, enabled=True, available=False, sections=dict(_EMPTY),
                     model=settings.llm_model, generated_at=None)

    sections = split_sections(res["text"])
    saved = await repo.upsert(period, h, settings.llm_model,
                              spending_patterns=sections["spending_patterns"],
                              how_to_save=sections["how_to_save"],
                              money_radar=sections["money_radar"])
    await session.commit()
    return _resp(period, enabled=True, available=True, sections=sections,
                 model=saved.model, generated_at=saved.generated_at)
```

- [ ] **Step 4: Run to verify it passes**

Run: `.venv/bin/python -m pytest tests/test_insight_summary_service.py -q`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/services/insight_summary_service.py tests/test_insight_summary_service.py
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "feat(insights): InsightSummaryService with hash-based caching

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: `GET /insights/summary` endpoint

**Files:**
- Modify: `backend/app/routers/insights.py` (add an endpoint)
- Test: `backend/tests/test_api_insight_summary.py` (create)

- [ ] **Step 1: Write the failing tests** (`tests/test_api_insight_summary.py`)

```python
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.db import Base, new_session_factory
from app.deps import get_session
from app.main import app


@pytest_asyncio.fixture
async def client():
    engine, Session = new_session_factory("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async def override():
        async with Session() as s:
            yield s

    app.dependency_overrides[get_session] = override
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c
    app.dependency_overrides.clear()
    await engine.dispose()


async def test_summary_disabled_by_default(client):
    resp = await client.get("/insights/summary?month=2026-05")
    assert resp.status_code == 200
    body = resp.json()
    assert body["enabled"] is False and body["available"] is False
    assert body["period"] == "2026-05"
    assert body["spending_patterns"] == ""


async def test_summary_rejects_bad_month(client):
    resp = await client.get("/insights/summary?month=2026-5")  # not YYYY-MM
    assert resp.status_code == 422


async def test_summary_defaults_to_current_month(client):
    resp = await client.get("/insights/summary")
    assert resp.status_code == 200
    assert len(resp.json()["period"]) == 7  # "YYYY-MM"
```

- [ ] **Step 2: Run to verify it fails**

Run: `.venv/bin/python -m pytest tests/test_api_insight_summary.py -q`
Expected: FAIL (404 from the missing route, so the assertions fail).

- [ ] **Step 3: Add the endpoint** to `app/routers/insights.py`

Add this import near the top of the file (with the other `from fastapi import ...`):
```python
from fastapi import Query
```

Append this endpoint to `app/routers/insights.py`:
```python
@router.get("/insights/summary")
async def insights_summary(
    month: str | None = Query(default=None, pattern=r"^\d{4}-\d{2}$"),
    session: AsyncSession = Depends(get_session),
):
    """Per-month LLM insight summary (Spending Patterns / How To Save More / Money
    Radar). No-op (enabled=false) unless ET_INSIGHTS_NARRATE is set. Never 5xx on
    LLM failure — the screen falls back to the deterministic /insights cards."""
    from datetime import date

    from app.services.insight_summary_service import get_summary
    period = month or date.today().strftime("%Y-%m")
    return await get_summary(session, period)
```

(`APIRouter`, `Depends`, `AsyncSession`, and `get_session` are already imported in `insights.py`.)

- [ ] **Step 4: Run to verify it passes**

Run: `.venv/bin/python -m pytest tests/test_api_insight_summary.py -q`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/routers/insights.py tests/test_api_insight_summary.py
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "feat(insights): GET /insights/summary endpoint

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: Full-suite check + acceptance gate

**Files:** none (verification)

- [ ] **Step 1: Run the whole backend test suite**

Run: `cd /Users/harshkhandelwal/Documents/expense-tracker/backend && .venv/bin/python -m pytest -q`
Expected: all pass except the known pre-existing failure `tests/test_api_push.py::test_invalid_token_is_pruned` (unrelated to this work). No new failures.

- [ ] **Step 2: Live smoke test (optional, needs an LLM on :8081)**

```bash
# serve a model:  (in another terminal)  cd ~/Documents/txn-classifier && .venv/bin/python -m mlx_lm server --model mlx-community/Qwen2.5-7B-Instruct-4bit --port 8081
cd /Users/harshkhandelwal/Documents/expense-tracker/backend
ET_INSIGHTS_NARRATE=true ET_LLM_URL=http://localhost:8081 \
ET_LLM_MODEL=mlx-community/Qwen2.5-7B-Instruct-4bit \
.venv/bin/python -c "
import asyncio
from app.db import new_session_factory, Base
# point at the real emu.db for a real month
import os; os.environ['ET_DATABASE_URL']=os.environ.get('ET_DATABASE_URL','sqlite+aiosqlite:///emu.db')
from app.services.insight_summary_service import get_summary
from app.deps import _factory
async def main():
    _, Session = _factory()
    async with Session() as s:
        r = await get_summary(s, '2026-05')
        print('enabled', r['enabled'], 'available', r['available'])
        print('SPENDING:', r['spending_patterns'][:200])
asyncio.run(main())
"
```
Expected: `enabled True available True` and a grounded Spending Patterns paragraph. (Second run for the same month is a cache hit — no LLM call.)

- [ ] **Step 3: Acceptance gate (documented, before enabling the flag anywhere)**

Per the spec, before `ET_INSIGHTS_NARRATE=true` is set in any real environment, re-run the 6-month / 42-claim insight benchmark through this path and confirm **≥95%** claim-level accuracy. Record the number in the PR/commit message. Do not enable the flag if it regresses below 95%.

- [ ] **Step 4: Finalize**

Use the `superpowers:finishing-a-development-branch` skill to decide merge/PR. Keep the `insights_narrate` flag OFF on merge — this ships dark.

---

## Self-review notes

- **Spec coverage:** endpoint (Task 5) ✓; lazy hash-based caching + model invalidation (Task 4) ✓; `InsightSummary` table via create_all (Task 2) ✓; repository (Task 3) ✓; `split_sections` reusing the proven single-call path (Task 1) ✓; flag-off no-op + LLM-down fallback + 422 on bad month (Tasks 4–5 tests) ✓; benchmark acceptance gate (Task 6) ✓; out-of-scope items (Android UI, cross-month Radar anomalies, scheduler) untouched ✓.
- **Type/signature consistency:** `split_sections` returns the exact keys `spending_patterns/how_to_save/money_radar` consumed by `_resp`, `upsert`, and the service; `analytics_rows(start, end)` → `build_payload(rows, period)` shape matches; `generate_insights` `{"text","ok"}` keys used consistently; `upsert(..., *, spending_patterns, how_to_save, money_radar)` keyword-only args match the service call.
- **No placeholders:** every step has runnable code/commands and expected output.
- **Known caveat:** the live smoke test (Task 6 Step 2) assumes `ET_DATABASE_URL`/`_factory()` point at the real DB; if the backend resolves its DB differently in your env, run the smoke test through the running API instead (`curl` the endpoint with the flag set).
