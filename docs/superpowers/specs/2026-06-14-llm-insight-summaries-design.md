# LLM Insight Summaries — Design

**Date:** 2026-06-14
**Component:** expense-tracker backend
**Goal:** Serve per-month, LLM-generated qualitative spending insights (Spending Patterns /
How To Save More / Money Radar) on the Insight screen, generated from deterministic stats,
cached so they run at most once per data change, behind an off-by-default flag.

---

## 1. Background & motivation

The Insight screen is currently powered by `app/enrichment/insights_engine.py` — deterministic,
template-based cards (biggest spend, top category, MoM trend). They are exact but terse and
generic. Benchmarking established two firm conclusions on this user's real data:

- An LLM must **never compute** insight numbers — asked to read SMS/stats and total them, it was
  off by 66% (and 3× elsewhere). Numbers come from the deterministic engine.
- An LLM **fed pre-computed, labeled stats** generates qualitative insights far richer than the
  templates. Under the "arrange facts, never derive them" contract (display-string-only payload +
  grounding guard), the local 7B reached **~100% claim-level accuracy** across a 6-month,
  42-claim benchmark (66% raw → 90% structured → 100% after two payload fixes).

The generation machinery already exists and is tested: `app/enrichment/insights_llm.py`
(`build_payload`, `generate_insights`, `ground_guard`) with `tests/test_insights_llm.py`. This
spec covers only the **integration**: persistence, caching, and the API contract.

### Decisions (locked during brainstorming)
- **Trigger:** lazy + cached, auto-refresh on data change (payload-hash invalidation). No scheduler.
- **Scope:** one summary per calendar month, generated on demand when that month is requested.
- **API:** a new `GET /insights/summary` endpoint returning 3 sections; the existing `/insights`
  deterministic cards are untouched.
- **Model/rollout:** local 7B via `settings.llm_url`/`llm_model`, behind `settings.insights_narrate`
  (default off), Opus-swappable later via config.

---

## 2. Architecture

Single read path with deterministic, hash-based cache invalidation:

```
GET /insights/summary?month=YYYY-MM
  └─ InsightSummaryService.get_summary(month)
       1. rows  = TransactionRepository: that month's debit transactions
       2. payload = insights_llm.build_payload(rows, month)     # display-string-only (existing)
       3. h     = sha256(canonical_json(payload))               # exact content hash
       4. row   = InsightSummaryRepository.get(month)
            • FRESH  (row exists AND row.payload_hash == h AND row.model == current_model)
                     → return cached sections (no LLM call)
            • STALE/MISSING
                     → res = insights_llm.generate_insights(rows, month)   # call + guard (existing)
                     → sections = insights_llm.split_sections(res["text"])
                     → InsightSummaryRepository.upsert(month, h, current_model, sections)
                     → return sections
       5. disabled (flag off) OR generate_insights ok=False (LLM down)
                     → return empty sections + enabled/available flags; never raise
```

**Why hash-based invalidation:** `build_payload` is a pure function of the month's transactions, so
its hash changes exactly when the underlying data changes (new transaction, recategorization) or the
model changes. That gives free, precise "regenerate only when needed" — no TTL, no scheduler, never
stale. This is the brainstormed "lazy + auto-refresh on data change."

**Cost:** generation is one LLM call (~3–5 s) per month per data-change. A cache hit is one indexed DB
read. Browsing N months the first time costs N calls, each cached thereafter.

---

## 3. Components

Each unit has one responsibility, a clear interface, and is independently testable. Repository/entity
patterns and async I/O per the project's global standards.

### 3.1 `InsightSummary` entity — `app/entities.py`
New table `insight_summaries`:

| Column | Type | Notes |
|---|---|---|
| `id` | int PK | |
| `period` | str, unique | `"YYYY-MM"` |
| `payload_hash` | str | sha256 hex of the canonical payload |
| `model` | str | the `settings.llm_model` that produced it |
| `spending_patterns` | str | section text (may be empty) |
| `how_to_save` | str | section text |
| `money_radar` | str | section text |
| `generated_at` | datetime | |

New table → picked up by `create_all`; no manual ALTER (only new *columns* on existing tables need that).

### 3.2 `InsightSummaryRepository` — `app/repositories.py`
- `async get(period: str) -> InsightSummary | None`
- `async upsert(period, payload_hash, model, spending_patterns, how_to_save, money_radar) -> InsightSummary`
  (insert or update the row for `period`, set `generated_at` to now)

### 3.3 `insights_llm.py` (exists) — add one pure function
`split_sections(text: str) -> dict[str, str]` → `{"spending_patterns", "how_to_save", "money_radar"}`.
Splits the LLM's markdown on the three known headers (case-insensitive, accepts `###`, `##`, or
`**Bold:**` forms). A missing header yields an empty string for that section. No other change to the
already-tested `build_payload` / `generate_insights` / `ground_guard`.

### 3.4 `InsightSummaryService` — `app/services/insight_summary_service.py` (new)
The only unit that knows about caching. `async get_summary(session, period) -> dict` implementing the
step 1–5 flow above. Returns:
```json
{"period": "2026-05", "enabled": true, "available": true,
 "spending_patterns": "...", "how_to_save": "...", "money_radar": "...",
 "model": "<id>", "generated_at": "<iso8601>"}
```
- `enabled` = `settings.insights_narrate`. When false: returns immediately with `enabled:false`,
  `available:false`, empty sections — no DB write, no LLM call.
- `available` = whether sections are present (cached or freshly generated). False when the LLM was
  unreachable and no cache existed.

### 3.5 Router — `app/routers/insights.py`
`GET /insights/summary?month=YYYY-MM` (default: current month). Validates `month` matches
`^\d{4}-\d{2}$`; delegates to the service; returns the service dict. Behind the same auth/lock as the
rest of the API.

### 3.6 Config — `app/config.py` (exists)
Reuses `insights_narrate`, `llm_url`, `llm_model`. No new settings.

---

## 4. Data flow

1. App opens a month → `GET /insights/summary?month=2026-05`.
2. Service loads the month's debit rows, builds the payload, hashes it.
3. Cache hit (hash + model match) → return stored sections instantly.
4. Cache miss/stale → one guarded LLM call → split → upsert → return.
5. The three section strings populate the existing `SpendingInsightsScreen`, `SaveMoreScreen`,
   `MoneyRadarScreen`. The deterministic `/insights` cards remain the always-on, instant fallback.

---

## 5. Error handling

| Condition | Behavior |
|---|---|
| Flag off (`insights_narrate=false`) | `enabled:false`, empty sections, no LLM/DB write |
| LLM down / timeout, cache exists | serve the cached (possibly stale) sections; never error |
| LLM down / timeout, no cache | `available:false`, empty sections; app shows deterministic cards |
| Guard strips a sentence | guarded text is what gets stored — an ungrounded number can never be persisted |
| Section header missing in output | that section stored empty; others unaffected |
| Invalid `month` param | 422 (FastAPI validation) |

The endpoint never returns 5xx for an LLM failure; the Insight screen degrades to its current
deterministic cards.

---

## 6. Testing

Unit (mocked LLM, no live model in CI):
- `split_sections`: all three headers present; `**Bold:**` form; a missing header → empty section;
  no headers → all empty.
- Cache decision: fresh (hash+model match → no generate), data-change (hash differs → regenerate),
  model-change (model differs → regenerate), missing row → generate.
- `InsightSummaryRepository`: insert then update same `period` (single row, `generated_at` advances).
- Service: flag off → `enabled:false`, no LLM call; LLM `ok:false` with no cache → `available:false`.

Acceptance gate (manual, documented — not CI):
- Re-run the 6-month / 42-claim insight benchmark through this path; **must stay ≥95%** before the
  `insights_narrate` flag is enabled in any environment.

---

## 7. Rollout

Ships dark: `insights_narrate` defaults off, so the endpoint returns `enabled:false` and nothing
changes for users. Enable per-environment by setting `ET_INSIGHTS_NARRATE=true` and serving a model
at `ET_LLM_URL`. Switch to Opus (hybrid) later by pointing `ET_LLM_MODEL`/`ET_LLM_URL` at the API —
no code change; the cache `model` column invalidates old rows automatically.

---

## 8. Out of scope (v1 / YAGNI)

- Cross-month anomaly detection for Money Radar (v1 Radar uses the existing payload's money-movement
  total + biggest single spend). A future enhancement can add MoM deltas to the payload.
- Opus/hybrid wiring beyond the existing config switch.
- Android UI changes — this spec defines the backend contract only; consuming the three sections in
  the app is a separate piece of work.
- Pre-warming / scheduled generation — generation stays lazy and on-demand.

## 9. Success criteria

1. `GET /insights/summary?month=` returns three grounded section strings for a month, generated once
   and served from cache until that month's data changes.
2. With the flag off, the endpoint is a no-op (`enabled:false`) and the existing Insight screen is
   unchanged.
3. An LLM outage never errors the request; the screen falls back to deterministic cards.
4. The benchmark stays ≥95% through the integrated path before the flag is enabled.
