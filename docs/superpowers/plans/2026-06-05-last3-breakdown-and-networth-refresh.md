# Last 3 Months Breakdown + Net Worth Freshness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the dashboard "Last 3 Months" card open a single screen combining a category donut + tap-to-filter chips + the full transaction list, and keep the Net Worth card and screen current as investments/savings/FDs change.

**Architecture:** The Last-3-Months screen is driven by a single `searchTransactions` call; category totals (donut + chips) and the filtered list are all derived client-side from that one list. Net worth freshness is achieved by (a) re-fetching on screen resume + pull-to-refresh on the Android side and (b) folding investment-portfolio sync into the backend `/sync/gmail` pipeline (best-effort). Follows the existing repository / use-case / Compose-screen + ViewModel patterns.

**Tech Stack:** Kotlin + Jetpack Compose (Material3 1.2.1, compose-bom 2024.04.01), AndroidX Lifecycle (`lifecycle-runtime-compose`), Retrofit/Moshi; Python FastAPI + SQLAlchemy async + pytest/httpx.

---

## Testing conventions

- **Backend:** TDD with pytest. Run from `backend/` using the venv:
  `cd backend && .venv/bin/pytest <path> -v`. Tests use the `client` fixture
  (in-memory SQLite + ASGI transport) from `tests/conftest.py` / per-file fixtures,
  matching `tests/test_api_accounts.py`.
- **Android:** This project has **no Compose UI test harness**; the established
  verification path is a real-device run via the project `run-app` skill. For
  Android tasks the "test" gate is a successful Kotlin compile
  (`./gradlew :app:compileDebugKotlin`) followed by an explicit on-device
  verification checkpoint (Task 10). Each Android task therefore ends with a
  compile + commit rather than a unit test.
- `JAVA_HOME` for gradle: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.

---

## File Structure

**Backend**
- Modify: `backend/app/services/networth.py` — add `sync_investments(session)` reusable service fn.
- Modify: `backend/app/routers/portfolio.py` — route delegates to `sync_investments`.
- Modify: `backend/app/routers/sync.py` — call `sync_investments` best-effort in `/sync/gmail`.
- Create: `backend/tests/test_sync_investments.py` — tests for the new behaviour.

**Android — Net worth freshness**
- Modify: `ui/networth/NetWorthViewModel.kt` — extract reusable `load()`, add `refresh()`.
- Modify: `ui/networth/NetWorthScreen.kt` — resume re-fetch + pull-to-refresh.
- Modify: `ui/dashboard/DashboardViewModel.kt` — add `refreshNetWorth()`.
- Modify: `ui/dashboard/DashboardScreen.kt` — call `refreshNetWorth()` on `ON_RESUME`.

**Android — Last 3 Months breakdown**
- Create: `ui/designsystem/CategoryDonut.kt` — shared donut with optional selection highlight.
- Modify: `ui/dashboard/DashboardScreen.kt` — use the shared donut (remove the private one).
- Create: `ui/transactions/Last3BreakdownUiState.kt`
- Create: `ui/transactions/Last3BreakdownViewModel.kt`
- Create: `ui/transactions/Last3BreakdownScreen.kt`
- Modify: `ui/transactions/TransactionListScreen.kt` — make the date-grouped list row + helpers reusable (extract to a shared file).
- Create: `ui/transactions/TxnListShared.kt` — shared row/header/sort/grouping used by both detail screens.
- Modify: `ui/shell/AppShell.kt` — point `ROUTE_TXN_RANGE` at `Last3BreakdownScreen`.

---

## PHASE A — Backend: investment ingestion in the main sync

### Task 1: Extract `sync_investments` and wire it into `/sync/gmail`

**Files:**
- Modify: `backend/app/services/networth.py`
- Modify: `backend/app/routers/portfolio.py:69-105`
- Modify: `backend/app/routers/sync.py:41-78`
- Test: `backend/tests/test_sync_investments.py`

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test_sync_investments.py`:

```python
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

import app.routers.sync as sync_router
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


async def test_sync_gmail_invokes_investment_sync(client, monkeypatch):
    # No Gmail creds in test env, so ingestion does no work — we only assert that
    # the sync pipeline calls investment sync.
    calls = {"n": 0}

    async def fake_sync_investments(session):
        calls["n"] += 1
        return {"status": "ok"}

    monkeypatch.setattr(sync_router, "sync_investments", fake_sync_investments)

    r = await client.post("/sync/gmail")
    assert r.status_code == 200
    assert calls["n"] == 1


async def test_sync_gmail_survives_investment_failure(client, monkeypatch):
    # A portfolio-sync failure must never break transaction ingestion.
    async def boom(session):
        raise RuntimeError("portfolio blew up")

    monkeypatch.setattr(sync_router, "sync_investments", boom)

    r = await client.post("/sync/gmail")
    assert r.status_code == 200
    body = r.json()
    assert "ingested" in body and "fetched" in body
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && .venv/bin/pytest tests/test_sync_investments.py -v`
Expected: FAIL — `AttributeError: module 'app.routers.sync' has no attribute 'sync_investments'`.

- [ ] **Step 3: Add `sync_investments` to the networth service**

In `backend/app/services/networth.py`, add this function (it factors out the core
of the existing `sync_portfolio` route handler). Place it after `sync_us_portfolio`:

```python
async def sync_investments(session: AsyncSession) -> dict:
    """Refresh all investment sources used by net worth: the US aggregate (parsed
    from email) and, when a PAN is set, the Indian holdings statement. Reusable by
    both the explicit /sync/portfolio route and the main /sync/gmail pipeline.
    Commits its own work. Returns a status dict."""
    from app.parsing.holdings import parse_holding_statement
    from app.repositories import HoldingRepository, ProfileRepository

    # US aggregate is independent of PAN (parsed from the email body).
    us_result = await sync_us_portfolio(session)

    profile = await ProfileRepository(session).get()
    pan = profile.pan if profile else None
    if not pan:
        await session.commit()
        return {"status": "ok" if us_result else "no_pan", "us_stocks": us_result}

    pdf_bytes = gmail.fetch_latest_holding_pdf_bytes()
    if not pdf_bytes:
        await session.commit()
        return {"status": "no_statement", "us_stocks": us_result}

    parsed = parse_holding_statement(pdf_bytes, pan)
    if not parsed or not parsed.get("holdings"):
        await session.commit()
        return {"status": "parse_failed", "us_stocks": us_result}

    holdings = parsed["holdings"]
    as_of = parsed.get("as_of") or ""
    await HoldingRepository(session).replace_all(holdings, as_of)
    await session.commit()
    return {
        "status": "ok",
        "holdings": len(holdings),
        "total_value": round(sum(h["value"] for h in holdings), 2),
        "as_of": as_of,
        "us_stocks": us_result,
    }
```

- [ ] **Step 4: Refactor the `/sync/portfolio` route to delegate**

Replace the body of `sync_portfolio` in `backend/app/routers/portfolio.py` (lines
69-105) with a delegation that preserves its current response shape:

```python
@router.post("/sync/portfolio")
async def sync_portfolio(session: AsyncSession = Depends(get_session)):
    from app.services.networth import sync_investments
    result = await sync_investments(session)
    # Preserve the previous route response contract.
    if result.get("status") == "no_pan":
        return {"status": "no_pan", "us_stocks": result.get("us_stocks"),
                "detail": "Set your PAN in profile first"}
    if result.get("status") in ("no_statement", "parse_failed", "ok") and "holdings" not in result:
        return {"status": result["status"], "us_stocks": result.get("us_stocks")}
    return {
        "holdings": result.get("holdings"),
        "total_value": result.get("total_value"),
        "as_of": result.get("as_of"),
        "us_stocks": result.get("us_stocks"),
    }
```

Update the import line at the top of `portfolio.py` — it currently imports
`sync_us_portfolio, us_stocks_block` from `app.services.networth`; add
`sync_investments`:

```python
from app.services.networth import sync_investments, sync_us_portfolio, us_stocks_block
```

- [ ] **Step 5: Wire `sync_investments` into `/sync/gmail` (best-effort)**

In `backend/app/routers/sync.py`, add the import near the top (with the other
`app.services` imports):

```python
from app.services.networth import sync_investments
```

Then in `sync_gmail`, after the Money Radar scan block and before the push-dispatch
block (i.e. after the `except Exception: ... logging...money_radar...` block at
line ~69), insert:

```python
    # Refresh investment portfolio (Indian holdings + US stocks) so net worth
    # reflects new statements. Best-effort: never break transaction ingestion.
    try:
        await sync_investments(session)
    except Exception:
        await session.rollback()
        logging.getLogger("portfolio").exception("post-sync investment sync failed")
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd backend && .venv/bin/pytest tests/test_sync_investments.py -v`
Expected: PASS (2 passed).

- [ ] **Step 7: Run the full backend suite (no regressions)**

Run: `cd backend && .venv/bin/pytest -q`
Expected: all tests pass (the existing portfolio/accounts tests still green).

- [ ] **Step 8: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add backend/app/services/networth.py backend/app/routers/portfolio.py backend/app/routers/sync.py backend/tests/test_sync_investments.py
git commit -m "feat(backend): sync investment portfolio during gmail sync

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## PHASE B — Android: Net worth UI freshness

### Task 2: NetWorthViewModel — reusable load + refresh()

**Files:**
- Modify: `android/app/src/main/java/com/ledgernoir/tracker/ui/networth/NetWorthViewModel.kt`

- [ ] **Step 1: Replace the ViewModel body with an extracted `load()` + `refresh()`**

```kotlin
class NetWorthViewModel(
    private val getNetWorthFull: GetNetWorthFullUseCase,
    private val getProjection: GetProjectionUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NetWorthUiState())
    val uiState: StateFlow<NetWorthUiState> = _uiState.asStateFlow()

    init { load(showSpinner = true) }

    /** Re-fetch the breakdown + projection. Used on resume and pull-to-refresh. */
    fun refresh() = load(showSpinner = false)

    private fun load(showSpinner: Boolean) {
        viewModelScope.launch {
            if (showSpinner) _uiState.value = _uiState.value.copy(loading = true)
            getNetWorthFull().onSuccess {
                _uiState.value = _uiState.value.copy(components = it.components, total = it.netWorth)
            }
            getProjection(10).onSuccess {
                _uiState.value = _uiState.value.copy(projection = it)
            }
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/ui/networth/NetWorthViewModel.kt
git commit -m "refactor(android): NetWorthViewModel exposes refresh()

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 3: NetWorthScreen — resume re-fetch + pull-to-refresh

**Files:**
- Modify: `android/app/src/main/java/com/ledgernoir/tracker/ui/networth/NetWorthScreen.kt`

- [ ] **Step 1: Add imports**

Add to the import block:

```kotlin
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.compose.LifecycleResumeEffect
```

- [ ] **Step 2: Wrap the screen content with pull-to-refresh + resume effect**

In `NetWorthScreen`, after `val loading = state.loading`, add the opt-in annotation
to the function (`@OptIn(ExperimentalMaterial3Api::class)` above `fun NetWorthScreen`)
and set up the state + resume effect:

```kotlin
    val ptr = rememberPullToRefreshState()
    if (ptr.isRefreshing) {
        LaunchedEffect(Unit) { vm.refresh() }
    }
    // End the indicator once the (non-spinner) refresh has settled.
    LaunchedEffect(loading) { if (!loading) ptr.endRefresh() }
    // Re-fetch whenever the screen returns to the foreground (e.g. after adding an
    // FD or editing a balance on another screen).
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
```

Then wrap the outer `Column` in a `Box` carrying the nested-scroll connection and
overlay the container. Change the outer `Column(...)` to:

```kotlin
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .nestedScroll(ptr.nestedScrollConnection),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            // ... existing ResponsiveContainer { ... } body unchanged ...
        }
        PullToRefreshContainer(
            state = ptr,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
```

(Move the existing `ResponsiveContainer { ... }` content inside the inner `Column`
unchanged; only the outermost container changes from `Column` to `Box` + inner
`Column`.)

- [ ] **Step 3: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/ui/networth/NetWorthScreen.kt
git commit -m "feat(android): pull-to-refresh + resume refresh on Net Worth screen

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 4: Dashboard net worth card refreshes on resume

**Files:**
- Modify: `android/app/src/main/java/com/ledgernoir/tracker/ui/dashboard/DashboardViewModel.kt`
- Modify: `android/app/src/main/java/com/ledgernoir/tracker/ui/dashboard/DashboardScreen.kt`

- [ ] **Step 1: Add `refreshNetWorth()` to DashboardViewModel**

In `DashboardViewModel.kt`, add this method (next to `onSyncCompleted`):

```kotlin
    /** Lightweight net-worth-only refresh, e.g. after returning from Savings/
     *  Portfolio where an FD/balance/investment may have changed. */
    fun refreshNetWorth() {
        viewModelScope.launch {
            getNetWorth()
                .onSuccess { _uiState.value = _uiState.value.copy(netWorth = it) }
                .onFailure { /* non-fatal */ }
        }
    }
```

- [ ] **Step 2: Call it on resume from DashboardScreen**

In `DashboardScreen.kt`, add imports:

```kotlin
import androidx.lifecycle.compose.LifecycleResumeEffect
```

After the existing `LaunchedEffect(phase) { ... }` block (around line 131), add:

```kotlin
    // Refresh net worth each time the dashboard returns to the foreground so
    // changes made on Savings/Portfolio (new FD, edited balance, synced holdings)
    // are reflected without a full reload.
    LifecycleResumeEffect(Unit) {
        vm.refreshNetWorth()
        onPauseOrDispose { }
    }
```

- [ ] **Step 3: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/ui/dashboard/DashboardViewModel.kt android/app/src/main/java/com/ledgernoir/tracker/ui/dashboard/DashboardScreen.kt
git commit -m "feat(android): refresh dashboard net worth card on resume

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## PHASE C — Android: Last 3 Months breakdown screen

### Task 5: Extract a shared `CategoryDonut` composable

**Files:**
- Create: `android/app/src/main/java/com/ledgernoir/tracker/ui/designsystem/CategoryDonut.kt`
- Modify: `android/app/src/main/java/com/ledgernoir/tracker/ui/dashboard/DashboardScreen.kt` (remove private donut, use shared)

- [ ] **Step 1: Create the shared donut**

Create `CategoryDonut.kt`. It generalizes the dashboard's private `CategoryDonut`
by adding an optional `selectedCategory` that highlights one slice (others dimmed)
and switches the center label/total. With `selectedCategory = null` it renders
exactly like today.

```kotlin
package com.ledgernoir.tracker.ui.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.data.api.SummaryItem
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary

/**
 * Centered donut of category spend with the total in the middle. When
 * [selectedCategory] is non-null, that slice is drawn at full opacity (others
 * dimmed) and the center shows the selected category's name + total.
 */
@Composable
fun CategoryDonut(
    summary: List<SummaryItem>,
    modifier: Modifier = Modifier,
    selectedCategory: String? = null,
    sizeDp: Int = 184,
) {
    val grandTotal = summary.sumOf { it.total }.takeIf { it > 0.0 } ?: 0.0
    val sel = selectedCategory?.let { s ->
        summary.firstOrNull { it.category.equals(s, ignoreCase = true) }
    }
    val centerLabel = sel?.category?.replaceFirstChar { it.uppercase() } ?: "TOTAL"
    val centerAmount = sel?.total ?: grandTotal

    val sweepProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(900),
        label = "donutSweep",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(sizeDp.dp)) {
            val strokeWidth = 30f
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(inset, inset)
            if (grandTotal <= 0.0) {
                drawArc(
                    color = SurfaceMid, startAngle = 0f, sweepAngle = 360f,
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            } else {
                var startAngle = -90f
                summary.sortedByDescending { it.total }.forEach { item ->
                    val sweep = (item.total / grandTotal * 360.0).toFloat() * sweepProgress
                    val isDimmed = sel != null && !item.category.equals(sel.category, true)
                    drawArc(
                        color = styleFor(item.category).color.copy(alpha = if (isDimmed) 0.18f else 1f),
                        startAngle = startAngle, sweepAngle = sweep, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                    startAngle += sweep
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centerLabel.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary, letterSpacing = 1.4.sp,
            )
            Spacer(Modifier.height(4.dp))
            MoneyText(amount = centerAmount, fontSize = 22.sp, color = TextPrimary)
        }
    }
}
```

- [ ] **Step 2: Use the shared donut from DashboardScreen**

In `DashboardScreen.kt`, delete the private `CategoryDonut` function (lines ~627-684).
The call site inside `WhereItWentCard` is `CategoryDonut(summary)`; it now resolves
to the shared one (same package import group `com.ledgernoir.tracker.ui.designsystem.*`
is already wildcard-imported at the top of the file — line 3). Remove now-unused
imports only if the compiler flags them.

- [ ] **Step 3: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -6`
Expected: BUILD SUCCESSFUL. (If it reports an ambiguity/duplicate `CategoryDonut`,
confirm the private one in `DashboardScreen.kt` was fully removed.)

- [ ] **Step 4: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/ui/designsystem/CategoryDonut.kt android/app/src/main/java/com/ledgernoir/tracker/ui/dashboard/DashboardScreen.kt
git commit -m "refactor(android): extract shared CategoryDonut with selection highlight

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 6: Extract shared transaction-list rendering

**Files:**
- Create: `android/app/src/main/java/com/ledgernoir/tracker/ui/transactions/TxnListShared.kt`
- Modify: `android/app/src/main/java/com/ledgernoir/tracker/ui/transactions/TransactionListScreen.kt`

The date-grouping, the `DetailTxnRow`, the `DetailDayHeader`, the `SortToggle`, the
`SortBy` enum, and the parse/format helpers are currently `private` in
`TransactionListScreen.kt`. Move them verbatim into a shared file so the new screen
reuses them (DRY) instead of duplicating.

- [ ] **Step 1: Create `TxnListShared.kt`**

Move these declarations out of `TransactionListScreen.kt` into a new
`TxnListShared.kt` (same package `com.ledgernoir.tracker.ui.transactions`), changing
their visibility from `private` to internal/public so both screens can use them:
`SortBy` (enum), `DetailListItem` (sealed interface), `parseOccurredLocal`,
`dayHeaderLabel`, `currencySymbolLocal`, `DETAIL_DAY_FMT`, `DETAIL_TIME_FMT`,
`buildGroupedDetailItems`, `SortToggle`, `SortChip`, `DetailDayHeader`,
`DetailTxnRow`. Keep their bodies exactly as they are today; only change `private`
→ (no modifier / `internal`) and add the necessary imports at the top of the new
file (copy the import set used by those functions from `TransactionListScreen.kt`:
Compose layout/material3/text, `TransactionDto`, theme colors, `java.time.*`).

- [ ] **Step 2: Remove the moved declarations from `TransactionListScreen.kt`**

Delete the now-moved declarations from `TransactionListScreen.kt`. Its remaining
`TransactionListScreen` composable keeps calling `buildGroupedDetailItems`,
`DetailTxnRow`, `DetailDayHeader`, `SortToggle`, and `SortBy` — now resolved from
the shared file in the same package (no import change needed).

- [ ] **Step 3: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -6`
Expected: BUILD SUCCESSFUL. (Fix any "unresolved reference" by ensuring the moved
symbols are non-private and imports were copied to `TxnListShared.kt`.)

- [ ] **Step 4: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/ui/transactions/TxnListShared.kt android/app/src/main/java/com/ledgernoir/tracker/ui/transactions/TransactionListScreen.kt
git commit -m "refactor(android): extract shared transaction-list rendering

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 7: Last3Breakdown UiState + ViewModel

**Files:**
- Create: `android/app/src/main/java/com/ledgernoir/tracker/ui/transactions/Last3BreakdownUiState.kt`
- Create: `android/app/src/main/java/com/ledgernoir/tracker/ui/transactions/Last3BreakdownViewModel.kt`

- [ ] **Step 1: Create the UiState**

```kotlin
package com.ledgernoir.tracker.ui.transactions

import com.ledgernoir.tracker.data.api.SummaryItem
import com.ledgernoir.tracker.data.api.TransactionDto

/**
 * State for the Last 3 Months breakdown screen. [categoryTotals] is derived from
 * [transactions] (debit-only, grouped by category) and drives the donut + chips.
 * [selectedCategory] == null means "All".
 */
data class Last3BreakdownUiState(
    val loading: Boolean = true,
    val transactions: List<TransactionDto> = emptyList(),
    val categoryNames: Map<Int, String> = emptyMap(),
    val categoryTotals: List<SummaryItem> = emptyList(),
    val totalSpent: Double = 0.0,
    val selectedCategory: String? = null,
)
```

- [ ] **Step 2: Create the ViewModel**

```kotlin
package com.ledgernoir.tracker.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.data.api.SummaryItem
import com.ledgernoir.tracker.domain.usecase.GetCategoriesUseCase
import com.ledgernoir.tracker.domain.usecase.SearchTransactionsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val UNCATEGORIZED = "Uncategorized"

/**
 * Backs [Last3BreakdownScreen]. One debit-only date-bounded fetch drives the donut,
 * the category chips, and the (filterable) transaction list — single source of
 * truth, so the donut total always equals the sum of the visible rows.
 */
class Last3BreakdownViewModel(
    private val getCategories: GetCategoriesUseCase,
    private val searchTransactions: SearchTransactionsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(Last3BreakdownUiState())
    val uiState: StateFlow<Last3BreakdownUiState> = _uiState.asStateFlow()

    fun load(startIso: String, endIso: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            val names = getCategories().getOrNull()?.associate { it.id to it.name } ?: emptyMap()
            searchTransactions(
                start = startIso, end = endIso, direction = "debit",
            ).onSuccess { list ->
                val debits = list.filter { it.direction != "credit" }
                val totals = debits
                    .groupBy { t -> t.categoryId?.let { names[it] } ?: UNCATEGORIZED }
                    .map { (cat, txns) -> SummaryItem(category = cat, total = txns.sumOf { it.amount }) }
                    .sortedByDescending { it.total }
                _uiState.value = _uiState.value.copy(
                    transactions = list,
                    categoryNames = names,
                    categoryTotals = totals,
                    totalSpent = debits.sumOf { it.amount },
                )
            }
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }

    fun selectCategory(category: String?) {
        _uiState.value = _uiState.value.copy(selectedCategory = category)
    }
}
```

- [ ] **Step 3: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/ui/transactions/Last3BreakdownUiState.kt android/app/src/main/java/com/ledgernoir/tracker/ui/transactions/Last3BreakdownViewModel.kt
git commit -m "feat(android): Last3Breakdown UiState + ViewModel

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 8: Last3BreakdownScreen composable

**Files:**
- Create: `android/app/src/main/java/com/ledgernoir/tracker/ui/transactions/Last3BreakdownScreen.kt`

- [ ] **Step 1: Create the screen**

```kotlin
package com.ledgernoir.tracker.ui.transactions

import com.ledgernoir.tracker.ui.designsystem.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.data.api.SummaryItem
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.ui.common.ledgerViewModelFactory
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.DisplayFamily
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary

/**
 * "Last 3 Months" breakdown: a category donut + tap-to-filter chips over the full
 * date-bounded transaction list. The donut/chips/list are all derived from one
 * fetch (see [Last3BreakdownViewModel]).
 */
@Composable
fun Last3BreakdownScreen(
    repository: ExpenseRepository,
    title: String,
    startIso: String,
    endIso: String,
    onBack: () -> Unit,
) {
    val vm: Last3BreakdownViewModel = viewModel(
        factory = ledgerViewModelFactory {
            Last3BreakdownViewModel(it.useCases.getCategories, it.useCases.searchTransactions)
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var sortBy by remember { mutableStateOf(SortBy.DATE) }

    LaunchedEffect(startIso, endIso) { vm.load(startIso, endIso) }

    val selected = state.selectedCategory
    val visibleTxns = remember(state.transactions, selected, state.categoryNames) {
        val debits = state.transactions.filter { it.direction != "credit" }
        if (selected == null) debits
        else debits.filter { t ->
            val name = t.categoryId?.let { state.categoryNames[it] } ?: "Uncategorized"
            name.equals(selected, ignoreCase = true)
        }
    }
    val visibleTotal = visibleTxns.sumOf { it.amount }

    Column(
        modifier = Modifier.fillMaxSize().background(CanvasBlack),
    ) {
        // App bar
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(44.dp).clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onBack()
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = DisplayFamily),
                color = TextPrimary, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentTeal)
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Donut header (reflects the current selection)
            item(key = "donut") {
                CategoryDonut(
                    summary = state.categoryTotals,
                    selectedCategory = selected,
                )
                Spacer(Modifier.height(16.dp))
            }
            // Filter chips: All + one per category
            item(key = "chips") {
                CategoryChips(
                    categories = state.categoryTotals,
                    selected = selected,
                    onSelect = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.selectCategory(it)
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
            // Summary line + sort toggle
            item(key = "summary") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val prefix = selected?.replaceFirstChar { it.uppercase() }?.let { "$it · " } ?: ""
                    Text(
                        text = "$prefix${visibleTxns.size} transaction" +
                            if (visibleTxns.size == 1) "" else "s",
                        style = MaterialTheme.typography.labelMedium, color = TextSecondary,
                    )
                    SortToggle(sortBy = sortBy, onSelect = {
                        if (it != sortBy) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            sortBy = it
                        }
                    })
                }
                Spacer(Modifier.height(8.dp))
            }

            if (visibleTxns.isEmpty()) {
                item(key = "empty") {
                    Text("No transactions", style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary, modifier = Modifier.padding(vertical = 24.dp))
                }
            } else when (sortBy) {
                SortBy.DATE -> {
                    val items = buildGroupedDetailItems(visibleTxns, state.categoryNames)
                    items.forEach { di ->
                        when (di) {
                            is DetailListItem.Header -> item(key = "h-${di.label}") { DetailDayHeader(di) }
                            is DetailListItem.Row -> item(key = "t-${di.txn.id}") { DetailTxnRow(di) }
                        }
                    }
                }
                SortBy.AMOUNT -> {
                    val sorted = visibleTxns.sortedByDescending { it.amount }
                    items(sorted.size, key = { sorted[it].id }) { idx ->
                        val txn = sorted[idx]
                        DetailTxnRow(DetailListItem.Row(txn, txn.categoryId?.let { state.categoryNames[it] }))
                    }
                }
            }
            item(key = "tail") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Horizontally scrollable category filter chips ("All" + one per category). */
@Composable
private fun CategoryChips(
    categories: List<SummaryItem>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "chip-all") {
            FilterChipPill(label = "All", color = null, selected = selected == null) { onSelect(null) }
        }
        items(categories.size, key = { categories[it].category }) { i ->
            val c = categories[i]
            FilterChipPill(
                label = c.category.replaceFirstChar { it.uppercase() },
                color = styleFor(c.category).color,
                selected = selected.equals(c.category, ignoreCase = true),
            ) { onSelect(c.category) }
        }
    }
}

@Composable
private fun FilterChipPill(
    label: String,
    color: Color?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) AccentTeal else SurfaceMid)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (color != null) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp))
                .background(if (selected) CanvasBlack else color))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) CanvasBlack else TextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, softWrap = false,
        )
    }
}
```

- [ ] **Step 2: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -6`
Expected: BUILD SUCCESSFUL. (If `DetailListItem`, `SortBy`, `buildGroupedDetailItems`,
`DetailTxnRow`, `DetailDayHeader`, or `SortToggle` are unresolved, confirm Task 6
made them non-private in `TxnListShared.kt`.)

- [ ] **Step 3: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/ui/transactions/Last3BreakdownScreen.kt
git commit -m "feat(android): Last 3 Months breakdown screen (donut + filter chips + list)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 9: Route the "Last 3 Months" card to the new screen

**Files:**
- Modify: `android/app/src/main/java/com/ledgernoir/tracker/ui/shell/AppShell.kt:357-381`

- [ ] **Step 1: Add the import**

Add near the other transactions imports (line ~50):

```kotlin
import com.ledgernoir.tracker.ui.transactions.Last3BreakdownScreen
```

- [ ] **Step 2: Swap the `ROUTE_TXN_RANGE` composable body**

In the `composable(route = ROUTE_TXN_RANGE, ...)` block, replace the
`TransactionListScreen(...)` call with `Last3BreakdownScreen`:

```kotlin
            ) { entry ->
                val args = entry.arguments
                Last3BreakdownScreen(
                    repository = app.repository,
                    title      = dec(args?.getString("title")),
                    startIso   = args?.getString("start") ?: "",
                    endIso     = args?.getString("end") ?: "",
                    onBack     = { navController.popBackStack() },
                )
            }
```

Leave `ROUTE_TXN_CAT`, `ROUTE_TXN_CARD`, and `ROUTE_TXN_MERCHANT` untouched — they
keep using `TransactionListScreen`. The `hideBottomBar` set already includes
`ROUTE_TXN_RANGE`, so the new screen stays full-screen with no bottom bar.

- [ ] **Step 3: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/ui/shell/AppShell.kt
git commit -m "feat(android): open Last 3 Months breakdown from dashboard card

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## PHASE D — Verification

### Task 10: Build, install, and verify on device

**Use the project `run-app` skill** (backend on real emu.db + wireless adb). The
device verified previously is `SM-S901E` at `192.168.1.2` (re-discover the port via
`adb mdns services`).

- [ ] **Step 1: Full build + install + launch**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker/android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ADB=~/Library/Android/sdk/platform-tools/adb
DEV=$($ADB devices | awk '/device$/{print $1; exit}')
./gradlew :app:assembleDebug --console=plain 2>&1 | tail -3   # BUILD SUCCESSFUL
$ADB -s "$DEV" install -r app/build/outputs/apk/debug/app-debug.apk
$ADB -s "$DEV" reverse tcp:8000 tcp:8000
$ADB -s "$DEV" shell am start -n com.ledgernoir.tracker/.MainActivity
```

- [ ] **Step 2: Verify the Last 3 Months screen**

Tap the "Last 3 Months" card. Screenshot
(`$ADB -s "$DEV" exec-out screencap -p > /tmp/v.png`) and confirm:
  - Donut + center TOTAL render; chips row shows "All" + categories.
  - Tapping a category chip dims other donut slices, switches the center to that
    category's name + total, filters the list, and updates the "N transactions"
    line. Donut center total equals the sum of the visible rows.
  - The Date/Amount sort toggle still works.
  - Back returns to the dashboard.

- [ ] **Step 3: Verify net worth freshness**

  - Open Savings → add a Fixed Deposit → back to Dashboard: the Net Worth card
    increases by the FD principal (resume refresh).
  - Open the Net Worth screen: the breakdown shows the new FD; pull down →
    pull-to-refresh spinner appears and the screen re-fetches.

- [ ] **Step 4: Confirm backend suite still green**

Run: `cd backend && .venv/bin/pytest -q`
Expected: all pass.

---

## Self-Review notes (author)

- **Spec coverage:** Feature 1 layout/data-flow → Tasks 5-9; donut highlight-in-place
  → Task 5; single-fetch source of truth → Task 7. Feature 2a screen refresh +
  pull-to-refresh → Tasks 2-3; dashboard card resume refresh → Task 4. Feature 2b
  best-effort portfolio sync in `/sync/gmail` → Task 1. Savings-live / FD-manual
  notes are reflected (no extra ingestion tasks, by design).
- **Type consistency:** `Last3BreakdownViewModel.selectCategory(String?)` /
  `uiState.selectedCategory` / `CategoryDonut(selectedCategory=)` /
  `CategoryChips(onSelect: (String?) ->)` all use the nullable category-name string
  (null = All). `categoryTotals: List<SummaryItem>` reused by donut + chips.
- **Material3 1.2.1:** pull-to-refresh uses `PullToRefreshContainer` +
  `rememberPullToRefreshState` (the experimental 1.2.1 API), not the newer
  `PullToRefreshBox`; no BOM bump.
