# Credit Utilization Tracker — Design Spec

**Date:** 2026-06-06
**Status:** Approved design, pending spec review → implementation plan
**Part of:** the SMS/email signal-extraction roadmap (feature 1 of 4: utilization →
refunds → cash/forex → declined+income).

## Goal

Surface per-credit-card **utilization** (used ÷ sanctioned limit) — a core credit-health
metric — extracted from the `Avl Limit` figure that Axis/BOB embed in every card
transaction SMS. Show a utilization bar on each card in the Bills screen, a credit-used
chip on the Dashboard, and a Money Radar alert when utilization runs high.

## Data extraction (the crux)

`app/enrichment/balance_scan.py` deliberately skips credit-card `Avl Limit` (only savings
balance). We add a parallel limit scanner.

- **Available credit** = latest `Avl Limit: INR X` from the card's most recent
  transaction SMS. Coverage: Axis cards carry it (`Avl Limit: INR 766974.71`), BOB
  similar; IDFC SMS mostly do not → those cards report "limit unknown."
- **Total sanctioned limit** — `max(Avl Limit)` ever observed for that card ≈ the
  sanctioned limit (available peaks when outstanding ≈ 0). A user-set `credit_limit`
  override takes precedence when present. Heuristic caveat: max under-reads until a
  near-zero-balance SMS arrives; the override fixes it.
- **Used** = `total − available`; **Utilization %** = `used / total` (0 if total ≤ 0).
- Cards with no observed `Avl Limit` and no override → `source: "unknown"`, no gauge,
  show a "Set limit" affordance.

Observed numbers (real data): XX6978 max≈₹986,798, XX9966 max≈₹990,997 (~₹10L limits);
359 `Avl Limit` SMS across the two Axis cards.

## Backend

Follows the repository/entity + async patterns.

### Entity (`app/entities.py`)
- `Account`: add `credit_limit: float | None` (nullable; manual override, null = heuristic).

### Scanner (`app/enrichment/limit_scan.py`)
```python
async def latest_avl_limit_for(session, *, last4) -> tuple[float, str] | None
async def max_avl_limit_for(session, *, last4) -> float | None
```
Regex `r"Avl\.?\s*Limit:?\s*INR\s*([\d,]+\.?\d*)"` (case-insensitive) over
`RawMessage` bodies containing the last4, newest-first for `latest_`, max over all for
`max_`. Mirrors `balance_scan.latest_balance_for`.

### Service (`app/services/utilization.py`)
`async def card_utilization(session) -> list[dict]`: for each `type="card"` account,
compute `{account_id, bank, last4, total_limit, available, used, utilization_pct, as_of,
source}` where `source ∈ {observed, manual, unknown}`. `total_limit` = `credit_limit`
override if set else `max_avl_limit_for`; `available` = `latest_avl_limit_for`;
`used`/`utilization_pct` derived. `unknown` when neither override nor observed limit.

### Router (`app/routers/accounts.py` — extend, it already owns `/accounts`)
- `GET /cards/utilization` → `{cards: [...]}` from the service.
- `PUT /cards/{account_id}/limit` body `{credit_limit: float}` → set the override via a
  repository method; returns `{ok: true}`.

### Money Radar finding (`app/enrichment/money_radar.py` — add a detector)
For each card with `source != "unknown"`: if `utilization_pct ≥ 50` → severity `action`;
elif `≥ 30` → `warning`. `dedup_key = f"util-{bank}-{last4}-{bucket}"` (bucket = 30/50 so
it re-alerts only on crossing up). Title/detail e.g. "AXIS ••6978 is 62% utilized" /
"₹612k of ₹987k used — high utilization can hurt your credit score." Rides the existing
findings → notifications → FCM pipeline (no new push code).

## Android

### DTOs (`data/api/ExpenseApi.kt`)
```kotlin
@JsonClass(generateAdapter = true)
data class CardUtilizationDto(
    @Json(name = "account_id") val accountId: Int,
    val bank: String, val last4: String?,
    @Json(name = "total_limit") val totalLimit: Double,
    val available: Double, val used: Double,
    @Json(name = "utilization_pct") val utilizationPct: Double,
    @Json(name = "as_of") val asOf: String?, val source: String,
)
@JsonClass(generateAdapter = true)
data class CardUtilizationResponse(val cards: List<CardUtilizationDto>)
```
API: `@GET("cards/utilization") suspend fun cardUtilization(): CardUtilizationResponse`
and `@PUT("cards/{id}/limit") suspend fun setCardLimit(@Path("id") id: Int, @Body body: SetLimitPayload)`
with `SetLimitPayload(@Json(name="credit_limit") val creditLimit: Double)`.
Repository: `cardUtilization(): Result<CardUtilizationResponse>`,
`setCardLimit(id, limit): Result<Unit>`. Use-case: `GetCardUtilizationUseCase`,
`SetCardLimitUseCase`, wired in `domain/UseCases.kt`.

### Bills screen (`ui/bills/BillsScreen.kt`) — progressive disclosure, NOT info-dense
**UX principle:** the `CardStateCard` already stacks 3 rows (unbilled/due, txns·overdue,
view-transactions). To avoid overload, utilization adds **one** glanceable line by default
and reveals detail only on tap, reusing the app's existing expand-in-place idiom
(`animateContentSize`, as in `CategoryBreakdownCard`/`RailRow`).

`BillsViewModel` fetches `/cards/utilization` and maps by `account_id`. Each card gains:
- **Collapsed (default):** a slim utilization bar + compact **"24%"** only — no rupee
  breakdown inline. Color ramp green <30% / amber 30–50% / red ≥50%
  (`#34D399`→`#FBBF24`→`#EF4444`). Shown only when `source != "unknown"`.
- **Expanded (tap the bar):** reveals `₹237k used · ₹750k available · as of <date>` +
  a `Set limit ›` action — in place, collapsed again on second tap.
- **Unknown-limit cards** (`source == "unknown"`, e.g. IDFC with no `Avl Limit`): a single
  subtle `Set limit to track utilization ›` line → a small number-field dialog calling
  `setCardLimit`, then refresh. No fake bar.

Reuse `cardBankColor`/`CredCard`/the existing mini-bar pattern.

### Dashboard — deliberately NO new chip
We do **not** add a utilization chip to the Dashboard. Rationale: (1) the Dashboard is
already dense (~8 stacked cards/strips); (2) the **Money Radar high-utilization finding
already surfaces on the Dashboard** via the "You might've missed" strip + FCM when
utilization is high — a chip would duplicate the urgent case and add noise in the healthy
case. The Bills screen is the single home for the detail; the alert path covers urgency.
(No `DashboardUiState`/VM/Screen changes.)

## Testing
- **Backend (pytest):** `limit_scan` unit tests (latest + max over seeded RawMessages);
  `card_utilization` service test (override vs heuristic vs unknown); `/cards/utilization`
  + `PUT /cards/{id}/limit` API tests; a Money Radar detector test asserting a ≥30%/≥50%
  finding appears. Full suite stays green.
- **Android:** compile gate per task; on-device verify — Bills card shows the collapsed
  bar at the right color/percentage, tapping expands the breakdown, "Set limit" dialog
  persists an override, Money Radar lists a high-utilization finding.

## Out of scope (YAGNI)
- Statement-PDF limit parsing (deferred — heuristic + override suffices).
- Utilization history/charts over time.
- The other three roadmap features (separate specs).

## Affected / new files
**Backend:** `entities.py` (+`credit_limit`), `repositories.py` (limit setter),
`enrichment/limit_scan.py` (new), `services/utilization.py` (new),
`routers/accounts.py` (+2 routes), `enrichment/money_radar.py` (+detector),
`schemas.py` (+`SetLimitIn`), tests.
**Android:** `data/api/ExpenseApi.kt`, `data/repository/ExpenseRepository.kt`,
`domain/usecase/UtilizationUseCases.kt` (new) + `domain/UseCases.kt`,
`ui/bills/{BillsUiState,BillsViewModel,BillsScreen}.kt` (collapsed bar + expand + dialog).
No Dashboard changes (see "Dashboard — deliberately NO new chip").
