# Money Radar — "Hidden Money" Insights — Design Spec

**Date:** 2026-06-04
**Status:** Approved (design), pending implementation plan
**Owner:** Harsh Khandelwal

## 1. Purpose

Surface the money that moves through the user's accounts but that the app shows
**nowhere today** — silent auto-pay mandates, foreign-exchange markup, card
fees/interest buried in statement totals, duplicate charges, refunds owed back,
declined/blocked transactions, and other "things you didn't know about."

The app already *parses* the signals (SMS + Gmail → `transactions` +
`raw_messages`) but **throws the surprising ones away**: mandate-creation alerts,
declines, refunds, and EMI conversions land in `raw_messages` with
`parsed_status = needs_review` and never reach the user. Money Radar mines them.

### Why now — grounded in real data

Findings from the live dataset (`backend/emu.db`, 8,665 transactions 2022–2026,
plus `raw_messages` with the original bodies). These are the evidence each
detector is built on, not hypotheticals:

| Signal | Real evidence in the data |
|---|---|
| Auto-pay mandates | 82 mandate messages: Netflix, Spotify (₹119 **and** ₹59), Disney+ Hotstar, JioHotstar, Jiocinema ₹29, Apple Media, Google Play ₹119, Viacom18, Razorpay ₹79, **SafeGold** (auto gold buy), **Garuda Construction ₹14,915** |
| Forex markup | 20 USD card charges ≈ **₹79,504** on IDFC XX0684 — ANTHROPIC ₹2,537 (USD 29.50), CLAUDE AI ₹2,030 / ₹8,870, one USD 295 ≈ ₹25,370 — markup (~3.5% + GST) never shown |
| Hidden card costs | Only **6** fee-like transactions captured app-wide; real fees/interest/GST are folded inside statement totals |
| Duplicate charges | ANTHROPIC ₹2,537 **×5 in 48h**; Tirupati Jewellers ₹38,000 ×2 same day; Other Bank ATM ₹10,000 ×2 same day |
| Refunds / credit owed | 82 refund/reversal messages; a **₹20,430 credit balance** sitting on the Axis card |
| Declined / blocked | 67 messages: IDFC "incorrect CVV" ₹6,802, **Axis "shared limit exhausted across all cards,"** debit card XX4145 **blocked**, ₹14,400 Malabar declined on wrong PIN |
| Subscription price hike | **Spotify ₹59 → ₹119** confirmed |
| EMI conversions | 55 EMI messages — hidden interest |
| Forgotten recurring | Life Insurance ₹16,844, Airtel — annual cadence the 30-day subscription detector cannot see |
| Categorization leakage | 1,600 uncategorized incl. Titan ₹43,558, INDwealth ₹40,572/₹28,430, jewellery — under-counts real spend |

De-prioritised (weak in *this* user's data): ATM leakage (only ₹26k), a
"forgotten card" (all 6 accounts used through Jun 2026), interest-earned, TCS/TDS
(4 messages).

## 2. Scope

### In scope (v1)
- Persisted `Finding` model + `FindingRepository` (async, repository/entity pattern).
- Four new event parsers for `needs_review` message types: mandates, declines,
  refunds, EMI conversions.
- A `MoneyRadarService.scan()` detector engine with the 10 detectors below,
  run after each SMS/email sync + a backfill endpoint for history.
- REST API for the feed, summary, scan, mark-seen, and dismiss.
- Android **Money Radar** screen + a **Dashboard "You might've missed" strip** +
  a 4th Insights-hub tile (all three entry points — the "both" decision).
- Notification integration: per-finding alerts (reusing the existing stable-id /
  client-dedup pipeline) + a weekly hidden-money digest.

### Out of scope (v1)
- Auto-resolving refunds (user dismisses manually).
- Account Aggregator / live balances (unchanged from product constraints).
- ML categorization improvements (tracked separately).

## 3. Architecture decision — persisted `Finding` model

The current `/insights` feed and `/notifications` feed are **computed-on-read**
(stateless; notifications carry a *stable `id`* and the Android client dedups).
That is correct for nudges but **cannot** support what this feature needs: a
"new since you last looked" badge, alert-once dedup across scans, and
dismiss/resolve. Those require **server state**.

**Chosen approach (B): a persisted `Finding` entity.** Detectors upsert findings
keyed by a stable `dedup_key`; the screen reads them with status; the dashboard
strip counts `new`; the notification feed emits each finding as a stable-id item
so the **existing client-dedup pipeline alerts once** with no new client work.

Event-type findings (mandate, price hike, duplicate, large charge, refund,
declined, EMI) are **persisted and stateful**. Aggregate-type findings (forex
markup meter, hidden card costs) are **recomputed each scan** but written through
the *same* `Finding` model so the API and UI stay uniform.

Rejected: (A) compute-on-read — no badge, re-alerts every run, no dismiss.
(C) hybrid split storage — unnecessary; the unified model already lets aggregates
recompute while events persist.

## 4. Data model

### 4.1 `Finding` entity (`app/entities.py`)

```
Finding:
  id: int (pk)
  type: str            # mandate | price_hike | duplicate | large_charge | refund
                       # | declined | emi | forex_markup | card_costs | forgotten_recurring
  severity: str        # action | warning | info | positive | security
  title: str
  detail: str
  amount: float | None
  currency: str = "INR"
  occurred_at: datetime          # event time, for sorting
  detected_at: datetime          # when a scan first created it
  dedup_key: str                 # UNIQUE, indexed — stable identity (upsert + notif id)
  status: str = "new"            # new | seen | dismissed | resolved
  evidence_kind: str | None      # transaction | raw_message | aggregate
  evidence_ref: str | None       # id or comma-joined ids for drill-down
  query_json: str | None         # optional drill-down filter, same shape as nudge `query`
```

`dedup_key` examples: `mandate:spotify:119`, `pricehike:spotify:2026-04`,
`dup:anthropic:2537:2026-06-01`, `refund:axis:9966:20430`,
`declined:idfc:0684:2026-05-31`, `forex_markup:2026`, `card_costs:axis:9966:2026-05`.

### 4.2 `FindingRepository` (`app/repositories.py`)
- `upsert_by_dedup_key(draft)` — insert or refresh title/detail/amount; **never
  resets `status` away from `dismissed`** (a dismissed finding stays dismissed).
- `list_active(*, include_dismissed=False)` — sorted by severity rank then amount.
- `count_new()` — for the badge.
- `mark_seen(ids)` / `mark_all_seen()` — on screen open.
- `dismiss(id)`.

### 4.3 New event parsers (`app/parsing/events/`)
Operate on `raw_messages` (including the `needs_review` backlog). They produce
**Findings, not Transactions** (no money moved):
- `mandates.py` → `{merchant, amount, frequency, valid_from, valid_to, action(created|revoked)}`
- `declines.py` → `{bank, last4, amount, reason}`
- `refunds.py` → `{amount, merchant, last4, kind(refund|reversal|credit_balance)}`
- `emi.py` → `{amount, tenure_months, interest?}`

Each ships with a small redacted-sample corpus pulled from real `raw_messages`.

## 5. Detector engine — `app/enrichment/money_radar.py`

`MoneyRadarService.scan(session)`:
1. Load transactions (`TransactionRepository.analytics_rows`) + relevant
   `raw_messages` (full set for backfill; since-last-scan for incremental).
2. Run each detector → `list[FindingDraft]`.
3. `upsert_by_dedup_key` for every draft (idempotent re-scan).

| # | Detector | Type | Sev | Logic (tuned to this dataset) |
|---|---|---|---|---|
| 1 | New auto-pay mandate | mandate | action | mandate-created parser → 1 finding per active mandate |
| 2 | Subscription price hike | price_hike | warning | recurring merchant, latest cycle > prior stable amount by **>15%** |
| 3 | Suspected duplicate | duplicate | warning | same normalized merchant + amount (±₹1), ≥2 within `dedup_amount_window_hours` |
| 4 | Unusually large charge | large_charge | info | debit ≥ max(**₹50,000** floor, 4× merchant median) |
| 5 | Refund / credit owed | refund | positive | refund/reversal parser + card credit-balance |
| 6 | Declined / blocked / limit | declined | security | decline/blocked/limit-exhausted parser |
| 7 | EMI conversion | emi | info | EMI-conversion parser; surface implied interest |
| 8 | Forex markup meter | forex_markup | info | Σ foreign charges; markup ≈ 3.5% + 18% GST on markup (estimate, labelled) |
| 9 | Hidden card costs | card_costs | info | **statement total − Σ captured card purchases in that cycle** ≈ fees/interest/charges (estimate; caveat: may include unparsed txns) |
| 10 | Forgotten recurring | forgotten_recurring | info | cadence ~340–400d the 30-day detector misses (insurance, telecom) |

Detector 9 is novel: because banks don't itemize fees in SMS, we *derive* the
invisible cost as the gap between the billed statement total and the card
purchases we actually captured for that cycle. Always shown as an estimate.

**Cross-link:** detector 1 also upserts the existing `recurring_series` so
mandates appear in **Save More** too, not only Money Radar.

## 6. API — `app/routers/money_radar.py`

| Method | Path | Purpose |
|---|---|---|
| GET | `/money-radar` | grouped active findings + `new_count` |
| GET | `/money-radar/summary` | `{new_count, top: [..3]}` for the dashboard strip |
| POST | `/money-radar/scan` | run detectors (backfill + invoked post-sync) |
| POST | `/money-radar/seen` | bulk mark-seen on screen open |
| POST | `/money-radar/{id}/dismiss` | dismiss one finding |

`scan` is also called at the end of the SMS/email sync flow so findings refresh
automatically. Pydantic v2 schemas in `app/schemas.py`.

## 7. Notifications + weekly digest (`app/services/notifications.py`)

- In `build_notifications`, append each `new` (or high-severity) finding as
  `{id: <dedup_key>, type: "money_radar", severity, route: "money_radar",
  title, body}`. The Android client's existing **stable-id dedup** alerts once.
- Add `_hidden_money_digest(findings, today)` keyed by ISO week (mirrors the
  existing `_digest_notification`) →
  *"Radar this week: 1 new mandate · ₹2,800 forex markup · 1 duplicate."*

## 8. Android UI

Ledger Noir dark theme; senior-level Compose; **no early returns in composables**
(per project standards). Reuse `CredCard`, `MoneyText`, `AccentTeal/AccentGreen`,
`CanvasBlack`, `TextPrimary/TextMuted`, the existing drill-down `query` route.

- **Dashboard strip** — `MoneyRadarStripCard`:
  `👁 You might've missed · 3 mandates · ₹2.8k markup · 1 duplicate  [See all →]`.
  Reactive `new_count` badge (same pattern as the notification bell). Hidden when
  `new_count == 0`. Taps → `money_radar` route.
- **`MoneyRadarScreen`** (+ `MoneyRadarUiState` + `MoneyRadarViewModel`):
  header + subtitle, a severity-count summary row, findings grouped by type in
  `CredCard`s (icon, title, detail, amount), tap → drill into the transaction
  list via the existing `query` pattern; aggregate findings (forex meter, hidden
  card costs) rendered as mini-meters; dismiss via row overflow.
- **Insights hub** — add a 4th tile *"Money Radar · Mandates · forex · fees"*
  with the new-count badge, beside How I Pay / Save More / Budgets.
- **Data layer** — `MoneyRadarApi` + DTOs, `ExpenseRepository.getMoneyRadar()` /
  `dismissFinding()` / `markRadarSeen()`, `GetMoneyRadarUseCase`.

## 9. Testing

In-memory SQLite, matching the existing 84-test style; no network/server.
- Parser unit tests from real redacted messages: Spotify mandate, Axis "shared
  limit exhausted", Amazon refund / card credit balance, EMI conversion.
- Detector unit tests: price hike (Spotify 59→119), duplicate (Anthropic ×5),
  forex markup math, card-cost gap derivation, forgotten-recurring annual cadence.
- API tests: feed shape, summary, dismiss, mark-seen.
- **Idempotency:** re-running `scan` upserts and never creates duplicate findings;
  a dismissed finding stays dismissed across re-scans.

## 10. Risks / open notes
- Detectors 8 & 9 are **estimates** (no FX spot rate; no itemized fees) — must be
  labelled as such in the UI to avoid implying precision.
- Mandate `valid_to` dates are often placeholders (e.g. 2035) — treat
  as "until revoked," don't surface bogus expiry alarms.
- Backfill over ~2,300 `needs_review` messages should be one-shot and idempotent;
  the dedup_key guarantees a re-run is safe.
