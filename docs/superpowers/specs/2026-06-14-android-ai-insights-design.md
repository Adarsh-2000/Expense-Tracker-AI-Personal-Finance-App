# Android "AI Insights" Screen — Design

**Date:** 2026-06-14
**Component:** expense-tracker Android app (`android/app/.../com/ledgernoir/tracker`)
**Goal:** Add a dedicated "AI Insights" screen that shows the backend's three per-month LLM
summary sections (Spending Patterns / How To Save More / Money Radar), with a month stepper,
loading shimmer, and a subtle off/unavailable placeholder — consuming the existing
`GET /insights/summary` endpoint and **conforming to the app's existing design patterns**.

Layout reference (illustrative only, NOT new visual primitives):
`docs/superpowers/specs/2026-06-14-android-ai-insights-mockup.html`.

---

## 1. Background

The backend feature (merged) exposes `GET /insights/summary?month=YYYY-MM` returning
`{period, enabled, available, model, generated_at, spending_patterns, how_to_save, money_radar}`.
It is off by default (`ET_INSIGHTS_NARRATE`), lazily generated, and never 5xx's on LLM failure.
No Android code consumes it yet. This spec covers the Android consumption only.

### Decisions (locked during brainstorming)
- **Placement:** a new dedicated "AI Insights" screen showing all three sections (not per-screen).
- **Month:** current calendar month by default, with a **month stepper** to view past months.
  (Backend is keyed per calendar month; arbitrary date ranges are out of scope — would need a new
  backend endpoint.)
- **Fetch/loading:** the screen fetches the summary for the selected month; cards show a shimmer
  while the ~3–5s first generation runs; the stepper stays interactive.
- **Off/unavailable:** show a subtle placeholder card (never an error dialog).

---

## 2. Design conformance (hard requirement)

The feature MUST look and be built like the rest of the app — no bespoke styling:
- **Theme & typography:** use the app's `MaterialTheme` colors/typography (the same tokens the
  existing insights screens use). The mockup's specific hex colors are illustrative; map them to
  existing theme roles (e.g. section accent = the theme's secondary/tertiary, surfaces = the card
  surface already used by `SaveMoreScreen`/`MoneyRadarScreen`).
- **Components:** reuse the existing card/surface composables and section-header style used by the
  current insights screens rather than introducing new ones. If a shimmer/skeleton helper already
  exists in the codebase, reuse it; otherwise add a minimal one consistent with existing loading
  states.
- **Navigation:** add the route via the existing `Screen` sealed class + `NavHost` in
  `ui/shell/AppShell.kt`, exactly like `Screen.SaveMore`/`Screen.MoneyRadar`.
- **Data layering:** follow `ExpenseApi` → `ExpenseRepository` (Result-based) → UseCase →
  ViewModel (`StateFlow<UiState>`) → Compose screen, mirroring `GetInsightsUseCase` /
  `SaveMoreViewModel`.
- **Money formatting:** render the backend's `*_display`-derived section strings **verbatim** — do
  NOT apply client-side currency/number formatting (the strings are already formatted, and the
  whole pipeline depends on numbers passing through unaltered).

---

## 3. Architecture

```
AiInsightsScreen (Compose)  ──observes──>  AiInsightsViewModel : StateFlow<AiInsightsUiState>
                                                 │
                                                 └─ GetInsightSummaryUseCase(month)
                                                      └─ ExpenseRepository.getInsightSummary(month): Result<InsightSummaryResponse>
                                                           └─ ExpenseApi @GET("insights/summary") @Query("month")
```

Entry: a card on the Insights hub (`InsightsScreen`) navigates to `Screen.AiInsights`.

---

## 4. Components

| Unit | File | Responsibility |
|---|---|---|
| `InsightSummaryResponse` | `data/api/ExpenseApi.kt` | data class with `@SerializedName`/`@Json` mapping the backend snake_case keys: `period, enabled, available, model, generatedAt, spendingPatterns, howToSave, moneyRadar`. Sits beside `InsightsResponse`. |
| `getInsightSummary(month)` | `data/api/ExpenseApi.kt` | `@GET("insights/summary")` with `@Query("month") month: String?`, suspend, returns `InsightSummaryResponse`. |
| `ExpenseRepository.getInsightSummary` | `data/repository/ExpenseRepository.kt` | `suspend fun getInsightSummary(month: String?): Result<InsightSummaryResponse>` — same try/Result wrapping as `getInsights()`. |
| `GetInsightSummaryUseCase` | `domain/usecase/GetInsightSummaryUseCase.kt` | thin wrapper over the repository call; mirrors `GetInsightsUseCase`. |
| `AiInsightsUiState` | `ui/insights/AiInsightsUiState.kt` | immutable: `month: YearMonth`, `loading: Boolean`, `summary: InsightSummaryResponse?`, `canGoForward: Boolean`. |
| `AiInsightsViewModel` | `ui/insights/AiInsightsViewModel.kt` | holds selected month (default current); `load()` on init + on month change; `prevMonth()`/`nextMonth()` (next disabled at current month). |
| `AiInsightsScreen` | `ui/insights/AiInsightsScreen.kt` | month stepper + 3 section cards + shimmer + placeholder, using existing components/theme. |
| route + entry | `ui/shell/AppShell.kt`, `ui/insights/InsightsScreen.kt` | `object AiInsights : Screen("ai_insights", "AI Insights")`, a `composable(...)` in the `NavHost`, and an entry card on the hub. |

---

## 5. UX states (per the approved mockup)

- **Loaded** (`enabled && available`): stepper `‹ Month YYYY ›` (forward arrow disabled when the
  selected month is the current month); three cards in order **Spending Patterns → How To Save
  More → Money Radar**, each rendering its section string; a small footer showing
  `generated_at` + an "on-device"/model tag.
- **Loading:** while a fetch is in flight, the three cards show shimmer skeletons; the stepper
  remains usable (changing month cancels/replaces the in-flight load).
- **Off / unavailable** (`enabled == false` OR `available == false`): one subtle placeholder card
  ("AI insights aren't turned on" / "Couldn't generate insights right now"). No dialog, no crash.
- **Empty section:** if a section string is blank, omit that one card; render the others.

---

## 6. Data flow

1. Screen opens → ViewModel inits with `month = current YearMonth`, `loading = true`.
2. `GetInsightSummaryUseCase(month.toString())` → repository → API.
3. On `Result.success`, store `summary`, set `loading = false`, recompute `canGoForward = month < currentMonth`.
4. On `Result.failure`, set `loading = false`, `summary = null` (UI shows the placeholder).
5. `prevMonth()`/`nextMonth()` update `month` and re-run steps 2–4. `nextMonth()` is a no-op at the
   current month.

---

## 7. Error handling

The repository returns `Result`; failures map to the placeholder state (no exception escapes the
ViewModel, no dialog). Because the backend never 5xx's on LLM failure and returns
`available=false` instead, the common "not ready / off" path is a normal 200 the UI handles as the
placeholder.

---

## 8. Testing

`AiInsightsViewModel` unit tests with a fake `GetInsightSummaryUseCase` (matching how the other
insights ViewModels are tested):
- inits to the current month and loads it (success → `summary` set, `loading=false`).
- `prevMonth()` loads the previous month; `canGoForward` becomes true.
- `nextMonth()` at the current month is a no-op (`canGoForward=false`, no extra load).
- a `failure` result leaves `summary=null` (placeholder), `loading=false`.
- an `enabled=false` / `available=false` response is stored as-is (screen renders placeholder).

Compose UI tests are optional and only if the existing screens have them; otherwise ViewModel
tests + manual verification on device, consistent with the current test posture.

---

## 9. Out of scope (YAGNI)

- Arbitrary date-range selection (backend is per calendar month; would need a new endpoint).
- Changes to the three existing deterministic screens (Spending Insights / Save More / Money Radar).
- Pull-to-refresh beyond the month stepper; offline caching of summaries on-device.
- Hybrid/Opus model toggle UI (the footer simply reflects whatever `model` the backend reports).

## 10. Success criteria

1. A reachable "AI Insights" screen renders the three backend sections for the selected month,
   with a working month stepper that can't advance past the current month.
2. Loading shows shimmer; off/unavailable shows the subtle placeholder; failures never crash.
3. Section strings render verbatim (no client-side number reformatting).
4. The screen visually matches the app's existing insights screens (theme, cards, typography) — the
   HTML mockup informs layout only.
