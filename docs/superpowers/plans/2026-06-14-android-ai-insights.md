# Android "AI Insights" Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a dedicated "AI Insights" screen to the Android app that shows the backend's three per-month LLM summary sections (Spending Patterns / How To Save More / Money Radar), with a month stepper, loading shimmer, and a subtle off/unavailable placeholder — consuming `GET /insights/summary`.

**Architecture:** Follows the app's existing layering — `ExpenseApi` (Moshi) → `ExpenseRepository` (`runCatching`/`Result`) → use case in `UseCases` container → `AiInsightsViewModel` (`StateFlow<UiState>` built via `ledgerViewModelFactory`) → Compose `AiInsightsScreen`, reached through a `Screen` route in `AppShell`. Reuses Material3/theme + existing card styling; the HTML mockup (`docs/superpowers/specs/2026-06-14-android-ai-insights-mockup.html`) is layout reference only.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Retrofit + Moshi (`@JsonClass(generateAdapter = true)`), AndroidX Navigation-Compose, JUnit + Robolectric (unit tests). `java.time.YearMonth` for months.

---

## Reference facts (verified in the codebase)

- App module root: `/Users/harshkhandelwal/Documents/expense-tracker/android`. Source root: `app/src/main/java/com/ledgernoir/tracker` (referred to below as `…/tracker`). Unit tests: `app/src/test/java/com/ledgernoir/tracker`.
- Build/test commands (run from `android/`): compile = `./gradlew :app:compileDebugKotlin -q`; unit tests = `./gradlew :app:testDebugUnitTest -q`; run one test class = `./gradlew :app:testDebugUnitTest --tests "com.ledgernoir.tracker.<pkg>.<Class>" -q`.
- JSON: Moshi codegen. Models use `@JsonClass(generateAdapter = true)` + `@Json(name = "snake_case")`. Example: `data class InsightsResponse(val insights: List<InsightCard>, val count: Int)` in `…/tracker/data/api/ExpenseApi.kt`.
- API method style (same file): `@GET("insights") suspend fun insights(): InsightsResponse`. Queries: `@Query("start") start: String`.
- Repository style (`…/tracker/data/repository/ExpenseRepository.kt`): `suspend fun insights(): Result<InsightsResponse> = runCatching { api().insights() }`.
- Use cases live grouped in `…/tracker/domain/usecase/InsightsUseCases.kt`, pattern: `class GetInsightsUseCase(private val repo: ExpenseRepository) { suspend operator fun invoke(): Result<InsightsResponse> = repo.insights() }`.
- DI container `…/tracker/domain/UseCases.kt` `class UseCases(repo: ExpenseRepository)` exposes `val getInsights = GetInsightsUseCase(repo)` (and siblings). `LedgerNoirApp` exposes `val repository` and `val useCases`.
- ViewModel construction in screens: `val vm: XViewModel = viewModel(factory = ledgerViewModelFactory { XViewModel(it.useCases.getX) })`, then `val state by vm.uiState.collectAsStateWithLifecycle()`. `ledgerViewModelFactory` is in `…/tracker/ui/common/ViewModelFactory.kt`; its lambda receives a scope with `.useCases` and `.repository`.
- Navigation (`…/tracker/ui/shell/AppShell.kt`): `sealed class Screen(val route, val title)` with e.g. `object MoneyRadar : Screen("money_radar", "Money Radar")`; registered as `composable(Screen.MoneyRadar.route) { MoneyRadarScreen(...) }`. The Insights hub `InsightsScreen` receives `onOpenMoneyRadar: () -> Unit` etc., wired in `AppShell` as `onOpenMoneyRadar = { navController.navigate(Screen.MoneyRadar.route) }`.
- **Design conformance is a hard requirement** (spec §2): use `MaterialTheme.colorScheme`/`typography`, mirror the card surface + section-header style of `SaveMoreScreen`/`MoneyRadarScreen`, render backend strings verbatim (no client-side number formatting).
- Commit messages end with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## Task 0: Feature branch

- [ ] **Step 1:** `cd /Users/harshkhandelwal/Documents/expense-tracker && git checkout -b feature/android-ai-insights && git branch --show-current` → `feature/android-ai-insights`.

---

## Task 1: API model + endpoint (TDD via Moshi round-trip)

**Files:**
- Modify: `android/app/src/main/java/com/ledgernoir/tracker/data/api/ExpenseApi.kt`
- Test: `android/app/src/test/java/com/ledgernoir/tracker/data/api/InsightSummaryResponseTest.kt` (create)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ledgernoir.tracker.data.api

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightSummaryResponseTest {
    private val adapter = Moshi.Builder().build().adapter(InsightSummaryResponse::class.java)

    @Test
    fun parses_enabled_response() {
        val json = """{"period":"2026-06","enabled":true,"available":true,"model":"q7b",
            "generated_at":"2026-06-14T01:12:30+05:30","spending_patterns":"A","how_to_save":"B","money_radar":"C"}"""
        val r = adapter.fromJson(json)!!
        assertEquals("2026-06", r.period)
        assertTrue(r.enabled); assertTrue(r.available)
        assertEquals("A", r.spendingPatterns)
        assertEquals("B", r.howToSave)
        assertEquals("C", r.moneyRadar)
        assertEquals("2026-06-14T01:12:30+05:30", r.generatedAt)
    }

    @Test
    fun parses_disabled_response_with_null_generated_at() {
        val json = """{"period":"2026-06","enabled":false,"available":false,"model":"q7b",
            "generated_at":null,"spending_patterns":"","how_to_save":"","money_radar":""}"""
        val r = adapter.fromJson(json)!!
        assertFalse(r.enabled); assertFalse(r.available)
        assertNull(r.generatedAt)
        assertEquals("", r.spendingPatterns)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.ledgernoir.tracker.data.api.InsightSummaryResponseTest" -q`
Expected: FAIL — `InsightSummaryResponse` unresolved (compile error).

- [ ] **Step 3: Add the model + endpoint**

In `ExpenseApi.kt`, add this data class next to `InsightsResponse`:
```kotlin
@JsonClass(generateAdapter = true)
data class InsightSummaryResponse(
    val period: String,
    val enabled: Boolean,
    val available: Boolean,
    val model: String,
    @Json(name = "generated_at") val generatedAt: String?,
    @Json(name = "spending_patterns") val spendingPatterns: String,
    @Json(name = "how_to_save") val howToSave: String,
    @Json(name = "money_radar") val moneyRadar: String,
)
```
And add this method inside the Retrofit interface, next to `suspend fun insights()`:
```kotlin
    @GET("insights/summary")
    suspend fun insightSummary(@Query("month") month: String?): InsightSummaryResponse
```
(`@GET`, `@Query`, `@Json`, `@JsonClass` are already imported in this file.)

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ledgernoir.tracker.data.api.InsightSummaryResponseTest" -q`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/data/api/ExpenseApi.kt \
        android/app/src/test/java/com/ledgernoir/tracker/data/api/InsightSummaryResponseTest.kt
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "feat(android): InsightSummaryResponse model + insights/summary endpoint

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Repository method + use case + DI wiring (compile-verified)

These three are one-liners following exact existing patterns; verified by compiling.

**Files:**
- Modify: `…/data/repository/ExpenseRepository.kt`
- Modify: `…/domain/usecase/InsightsUseCases.kt`
- Modify: `…/domain/UseCases.kt`

- [ ] **Step 1: Repository** — add next to `suspend fun insights()`:
```kotlin
    suspend fun insightSummary(month: String?): Result<InsightSummaryResponse> =
        runCatching { api().insightSummary(month) }
```
Add the import if not present: `import com.ledgernoir.tracker.data.api.InsightSummaryResponse`.

- [ ] **Step 2: Use case** — append to `InsightsUseCases.kt` (and add `import com.ledgernoir.tracker.data.api.InsightSummaryResponse` at the top with the other api imports):
```kotlin
/** Per-month LLM insight summary (Spending Patterns / How To Save More / Money Radar). */
class GetInsightSummaryUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(month: String?): Result<InsightSummaryResponse> =
        repo.insightSummary(month)
}
```

- [ ] **Step 3: DI container** — in `UseCases.kt`, add next to `val getInsights = GetInsightsUseCase(repo)`:
```kotlin
    val getInsightSummary = GetInsightSummaryUseCase(repo)
```

- [ ] **Step 4: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL (no errors).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/data/repository/ExpenseRepository.kt \
        android/app/src/main/java/com/ledgernoir/tracker/domain/usecase/InsightsUseCases.kt \
        android/app/src/main/java/com/ledgernoir/tracker/domain/UseCases.kt
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "feat(android): repository + use case for insight summary

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: `MonthStepper` pure helper (TDD)

Pure month logic, framework-free, fully unit-testable (the ViewModel delegates to it so the stepper behavior is covered without coroutines-test).

**Files:**
- Create: `…/ui/insights/MonthStepper.kt`
- Test: `android/app/src/test/java/com/ledgernoir/tracker/ui/insights/MonthStepperTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ledgernoir.tracker.ui.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class MonthStepperTest {
    private val now = YearMonth.of(2026, 6)

    @Test fun prev_goes_back_one_month() {
        assertEquals(YearMonth.of(2026, 5), MonthStepper.prev(now))
    }

    @Test fun next_at_current_is_noop() {
        assertEquals(now, MonthStepper.next(now, now))
    }

    @Test fun next_below_current_advances() {
        assertEquals(now, MonthStepper.next(YearMonth.of(2026, 5), now))
    }

    @Test fun can_go_forward_only_below_now() {
        assertFalse(MonthStepper.canGoForward(now, now))
        assertTrue(MonthStepper.canGoForward(YearMonth.of(2026, 5), now))
    }

    @Test fun label_and_api_param() {
        assertEquals("June 2026", MonthStepper.label(now))
        assertEquals("2026-06", MonthStepper.apiParam(now))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ledgernoir.tracker.ui.insights.MonthStepperTest" -q`
Expected: FAIL — `MonthStepper` unresolved.

- [ ] **Step 3: Implement** (`MonthStepper.kt`)

```kotlin
package com.ledgernoir.tracker.ui.insights

import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure month-navigation helpers for the AI Insights screen. The summary endpoint is
 *  keyed per calendar month and cannot show future months. */
object MonthStepper {
    private val LABEL = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

    fun current(): YearMonth = YearMonth.now()
    fun prev(month: YearMonth): YearMonth = month.minusMonths(1)
    fun next(month: YearMonth, now: YearMonth = current()): YearMonth =
        if (month < now) month.plusMonths(1) else month
    fun canGoForward(month: YearMonth, now: YearMonth = current()): Boolean = month < now
    fun label(month: YearMonth): String = month.format(LABEL)
    fun apiParam(month: YearMonth): String = month.toString()  // ISO "YYYY-MM"
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ledgernoir.tracker.ui.insights.MonthStepperTest" -q`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/ui/insights/MonthStepper.kt \
        android/app/src/test/java/com/ledgernoir/tracker/ui/insights/MonthStepperTest.kt
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "feat(android): MonthStepper pure month-navigation helper

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: UiState + ViewModel (compile-verified)

**Files:**
- Create: `…/ui/insights/AiInsightsUiState.kt`
- Create: `…/ui/insights/AiInsightsViewModel.kt`

- [ ] **Step 1: UiState** (`AiInsightsUiState.kt`)

```kotlin
package com.ledgernoir.tracker.ui.insights

import com.ledgernoir.tracker.data.api.InsightSummaryResponse
import java.time.YearMonth

/** Immutable snapshot of the AI Insights screen. */
data class AiInsightsUiState(
    val month: YearMonth,
    val loading: Boolean = true,
    val summary: InsightSummaryResponse? = null,
    val canGoForward: Boolean = false,
)
```

- [ ] **Step 2: ViewModel** (`AiInsightsViewModel.kt`)

```kotlin
package com.ledgernoir.tracker.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.domain.usecase.GetInsightSummaryUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Loads the per-month LLM summary; the month stepper can't advance past the current month.
 *  A failed/unavailable load leaves [AiInsightsUiState.summary] null so the screen shows the
 *  subtle placeholder (the backend returns available=false rather than erroring). */
class AiInsightsViewModel(
    private val getInsightSummary: GetInsightSummaryUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiInsightsUiState(month = MonthStepper.current()))
    val uiState: StateFlow<AiInsightsUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() {
        val month = _uiState.value.month
        _uiState.value = _uiState.value.copy(loading = true, canGoForward = MonthStepper.canGoForward(month))
        viewModelScope.launch {
            getInsightSummary(MonthStepper.apiParam(month))
                .onSuccess { _uiState.value = _uiState.value.copy(summary = it, loading = false) }
                .onFailure { _uiState.value = _uiState.value.copy(summary = null, loading = false) }
        }
    }

    fun prevMonth() {
        _uiState.value = _uiState.value.copy(month = MonthStepper.prev(_uiState.value.month))
        load()
    }

    fun nextMonth() {
        val month = _uiState.value.month
        if (!MonthStepper.canGoForward(month)) return
        _uiState.value = _uiState.value.copy(month = MonthStepper.next(month))
        load()
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/ui/insights/AiInsightsUiState.kt \
        android/app/src/main/java/com/ledgernoir/tracker/ui/insights/AiInsightsViewModel.kt
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "feat(android): AiInsightsViewModel + UiState

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: `AiInsightsScreen` (compile-verified)

Renders the stepper + three section cards + shimmer + placeholder. **Before writing, open `SaveMoreScreen.kt` and `MoneyRadarScreen.kt` and match their card surface, section-header style, padding, and the `viewModel(factory = ledgerViewModelFactory { … })` usage.** The code below is a complete, compiling Material3 baseline using theme tokens; align its `Card`/`Text` styling to those screens (don't introduce new colors/shapes).

**Files:**
- Create: `…/ui/insights/AiInsightsScreen.kt`

- [ ] **Step 1: Implement the screen**

```kotlin
package com.ledgernoir.tracker.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.data.api.InsightSummaryResponse
import com.ledgernoir.tracker.ui.common.ledgerViewModelFactory

@Composable
fun AiInsightsScreen() {
    val vm: AiInsightsViewModel = viewModel(
        factory = ledgerViewModelFactory { AiInsightsViewModel(it.useCases.getInsightSummary) },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Month stepper
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.prevMonth() }) {
                Icon(androidx.compose.material.icons.Icons.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            Text(MonthStepper.label(state.month), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { vm.nextMonth() }, enabled = state.canGoForward) {
                Icon(androidx.compose.material.icons.Icons.Filled.KeyboardArrowRight, contentDescription = "Next month")
            }
        }

        val summary = state.summary
        when {
            state.loading -> {
                ShimmerCard("Spending Patterns")
                ShimmerCard("How To Save More")
                ShimmerCard("Money Radar")
            }
            summary == null || !summary.enabled || !summary.available -> {
                PlaceholderCard(
                    title = if (summary?.enabled == false) "AI insights aren't turned on"
                            else "Couldn't generate insights right now",
                    body = "Your monthly Spending, Save-More and Money-Radar summaries will appear here.",
                )
            }
            else -> {
                SectionCard("📊", "Spending Patterns", summary.spendingPatterns)
                SectionCard("💸", "How To Save More", summary.howToSave)
                SectionCard("📡", "Money Radar", summary.moneyRadar)
                summary.generatedAt?.let {
                    Text(
                        "Generated $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(emoji: String, title: String, body: String) {
    if (body.isBlank()) return  // omit empty sections
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$emoji  $title", style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ShimmerCard(title: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text("…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun PlaceholderCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("✨", style = MaterialTheme.typography.headlineMedium)
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
        }
    }
}
```

NOTE: if the `androidx.compose.material:material-icons-*` artifact is not on the classpath (check whether other screens import `androidx.compose.material.icons.Icons`), replace the two `Icon(Icons.Filled.KeyboardArrow…)` calls with `Text("‹")` / `Text("›")` inside the `IconButton`s. Also, after it compiles, **upgrade `ShimmerCard` to match the app's existing shimmer/skeleton if one exists** (search for "shimmer"/"placeholder" in `…/ui`); the `"…"` is a compiling stand-in, not the final look.

- [ ] **Step 2: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL. (If icons are unavailable, apply the `Text("‹")`/`Text("›")` fallback and recompile.)

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/ui/insights/AiInsightsScreen.kt
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "feat(android): AiInsightsScreen (stepper, section cards, states)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: Navigation route + hub entry (compile-verified)

Wire the screen into navigation and add the entry card on the Insights hub, mirroring how `MoneyRadar` is wired.

**Files:**
- Modify: `…/ui/shell/AppShell.kt`
- Modify: `…/ui/insights/InsightsScreen.kt`

- [ ] **Step 1: Add the route** — in `AppShell.kt`, next to `object MoneyRadar : Screen("money_radar", "Money Radar")` add:
```kotlin
    object AiInsights : Screen("ai_insights", "AI Insights")
```

- [ ] **Step 2: Register the composable** — in the `NavHost` block of `AppShell.kt`, next to the existing `composable(Screen.MoneyRadar.route) { … }`, add:
```kotlin
            composable(Screen.AiInsights.route) {
                AiInsightsScreen()
            }
```
Add the import `import com.ledgernoir.tracker.ui.insights.AiInsightsScreen` if screens aren't wildcard-imported.

- [ ] **Step 3: Thread the callback into the hub** — find every place `InsightsScreen(...)` is invoked in `AppShell.kt` (the same call sites that pass `onOpenMoneyRadar = { navController.navigate(Screen.MoneyRadar.route) }`) and add alongside it:
```kotlin
                onOpenAiInsights = { navController.navigate(Screen.AiInsights.route) },
```

- [ ] **Step 4: Accept + use the callback in `InsightsScreen.kt`** — add the parameter `onOpenAiInsights: () -> Unit = {}` to the `InsightsScreen` composable signature and any inner composable it forwards to (mirror exactly how `onOpenMoneyRadar` is declared and forwarded — same lines/positions). Then add an entry card at the top of the hub content (above the existing entry buttons), e.g.:
```kotlin
            Card(
                onClick = onOpenAiInsights,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("✨  AI Insights", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    Text("›", style = MaterialTheme.typography.titleLarge)
                }
            }
```
Match the card style to the surrounding hub entries (read the existing `onOpenMoneyRadar`/`onOpenSaveMore` entry composables and mirror their look). Add any missing imports (`Card`, `CardDefaults`, `Row`, `Text`, `Modifier`, `Alignment`, `fillMaxWidth`, `padding`, `dp`, `MaterialTheme`).

- [ ] **Step 5: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/ledgernoir/tracker/ui/shell/AppShell.kt \
        android/app/src/main/java/com/ledgernoir/tracker/ui/insights/InsightsScreen.kt
git -c user.name='Claude' -c user.email='noreply@anthropic.com' commit -m "feat(android): AI Insights nav route + Insights-hub entry card

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: Build, test suite, and manual verification

- [ ] **Step 1: Full unit tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest -q`
Expected: all pass (incl. the 2 new test classes; pre-existing `SmsFilterTest`/`FormFactorTest` unaffected).

- [ ] **Step 2: Debug build**

Run: `./gradlew :app:assembleDebug -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual device check (documented)**

With the backend running and `ET_INSIGHTS_NARRATE=true` + an LLM served, install the debug APK and: open Insights → tap "✨ AI Insights" → confirm the current month renders three section cards; step back a month and confirm it reloads; forward arrow is disabled at the current month. With the flag OFF, confirm the subtle placeholder shows (no crash). Match card look against `SaveMoreScreen`.

- [ ] **Step 4: Finalize**

Use `superpowers:finishing-a-development-branch` to merge/PR. The feature is inert until the backend flag is on, so it's safe to merge dark.

---

## Self-review notes

- **Spec coverage:** dedicated screen (Task 5) ✓; all three sections (Task 5) ✓; month stepper, default current, no future (Tasks 3/4/5) ✓; loading shimmer + off/unavailable placeholder + empty-section omit (Task 5) ✓; `Api→Repository→UseCase→ViewModel→Screen` layering (Tasks 1,2,4) ✓; nav route + hub entry (Task 6) ✓; verbatim string rendering, no client formatting (Task 5 — strings passed straight to `Text`) ✓; design conformance via theme tokens + "mirror existing screens" instructions (Tasks 5/6 + reference facts) ✓; testing the framework-free units, compile-verify the rest (Tasks 1,3 TDD; 2,4,5,6 compile) ✓; out-of-scope (date ranges, existing screens) untouched ✓.
- **Type consistency:** `InsightSummaryResponse` fields (`enabled/available/spendingPatterns/howToSave/moneyRadar/generatedAt`) are identical across the model, ViewModel, and screen; `GetInsightSummaryUseCase(month: String?)` ↔ `repo.insightSummary(month: String?)` ↔ `api.insightSummary(@Query month)`; `MonthStepper.apiParam/label/prev/next/canGoForward` signatures match their callers in the ViewModel and screen; `AiInsightsUiState(month, loading, summary, canGoForward)` used consistently.
- **No placeholders:** every code step is complete; the two "mirror the existing screen" notes are design-conformance instructions (with full compiling baselines provided), not missing code.
- **Known caveat:** Compose material-icons availability and the exact existing shimmer helper are environment details the implementer confirms at compile time; both have explicit fallbacks in Task 5.
