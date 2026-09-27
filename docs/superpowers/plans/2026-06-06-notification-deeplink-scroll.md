# Notification Deep-link → Scroll-to-Item — Implementation Plan

> REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Tapping a notification opens the right screen AND scrolls to + briefly highlights the specific item it refers to (Money Radar finding, Bills card, Rewards card, Budget category).

**Architecture:** Carry an `anchor` (item id) end-to-end: backend notification → FCM/REST → Android `NotificationDto.anchor` → `ln_anchor` intent extra (and in-app notification-center tap) → `MainActivity.pendingAnchor` → `AppShell` → passed as `scrollAnchor` to the 4 target screens. Each screen attaches a `BringIntoViewRequester` to the matching item and calls `bringIntoView()` + a short highlight, then consumes the anchor. `BringIntoViewRequester` works in both `LazyColumn` and `verticalScroll` `Column`.

**Anchor values:** Bills → card `last4`; Money Radar → finding `dedup_key`; Rewards → card `last4`; Budget → `category` name.

**Conventions:** Android build env `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; export PATH="$JAVA_HOME/bin:$PATH"`, gradle from `android/`, gate `./gradlew :app:assembleDebug -q && echo BUILD_OK`. Backend: `cd backend && .venv/bin/python -m pytest -q` (full suite must stay green). No early-return in composables. `BringIntoViewRequester` needs `@OptIn(ExperimentalFoundationApi::class)`.

---

## Task 1: Backend — add `anchor` to notifications
**Files:** `backend/app/services/notifications.py`, the notification response schema (find it — likely `backend/app/schemas.py` `NotificationOut`/similar), the FCM push data builder (find `dispatch_new_pushes`/`build_notifications` / `app/services/push.py` or `routers/push.py`), tests under `backend/tests/`.

- [ ] Add `"anchor": <value>` to the notification dicts in `notifications.py`:
  - bill dict (the `for b in bills` block): `"anchor": b.last4 or ""`
  - budget dicts (the `for s in statuses` block, all 3 — exceeded/at_risk/80%): `"anchor": s.category`
  - money_radar finding dict (the `for f in findings` per-finding block, id == dedup_key): `"anchor": f.dedup_key`
  - reward dicts (the rewards block): `"anchor": <the reward card's last4>` (read the block for the variable)
  - Leave digest/goal/spending notifications' anchor unset (None) — they're screen-level only.
- [ ] Add `anchor: str | None = None` to the notification response schema so the REST `/notifications` payload includes it. Verify the dict→schema mapping passes it through.
- [ ] Add `anchor` to the FCM data payload (alongside `route`) in the push builder, so a tapped system notification carries it. Use `data={..., "route": ..., "anchor": ...}` (FCM data values must be strings; pass `anchor or ""`).
- [ ] Update/extend a backend test to assert a bill/budget/radar/reward notification includes the expected `anchor`. Run `cd backend && .venv/bin/python -m pytest -q` → all green.
- [ ] Commit: `feat(backend): notification anchor (item id) for deep-link scroll`

## Task 2: Android — plumb the anchor to the shell
**Files:** `data/api/ExpenseApi.kt`, `notifications/NotificationHelper.kt`, `MainActivity.kt`, `ui/shell/AppShell.kt`.

- [ ] `ExpenseApi.kt` `NotificationDto`: add `val anchor: String? = null` (after `route`).
- [ ] `NotificationHelper.kt`: where it does `item.route?.let { putExtra("ln_route", it) }`, add `item.anchor?.let { putExtra("ln_anchor", it) }`.
- [ ] `MainActivity.kt`: add `const val EXTRA_ANCHOR = "ln_anchor"`; a `private var pendingAnchor by mutableStateOf<String?>(null)`; set it in `onCreate` (`pendingAnchor = intent?.getStringExtra(EXTRA_ANCHOR)`) and `onNewIntent`; pass `pendingAnchor = pendingAnchor` + `onAnchorConsumed = { pendingAnchor = null }` to `AppShell`.
- [ ] `AppShell.kt`: add params `pendingAnchor: String? = null, onAnchorConsumed: () -> Unit = {}`. Keep the existing `pendingRoute` deep-link `LaunchedEffect` as-is (it navigates). Do NOT consume the anchor there — pass `pendingAnchor` down to the 4 destination composables so the navigated screen reads it. In the `NavHost`, pass to each of the 4 screens a `scrollAnchor = pendingAnchor` and `onAnchorConsumed = onAnchorConsumed` (added in Tasks 3–5). Also change the in-app notification-center wiring: `NotificationsScreen(onOpenRoute = ...)` currently passes a route; change `onOpenRoute` to also carry the anchor — update `NotificationsScreen`'s `onOpenRoute: (route: String) -> Unit` to `(route: String, anchor: String?) -> Unit`, pass `item.notification.anchor` at the call site (`item.notification.route?.let { onOpenRoute(it, item.notification.anchor) }`), and in AppShell's handler set the same `pendingAnchor` mechanism (e.g. a local `var deepAnchor` state) before navigating so the destination receives it. Keep it simple and consistent with how `pendingRoute` already flows.
- [ ] Compile gate → BUILD_OK. Commit: `feat(android): plumb notification anchor to the app shell`

## Task 3: Money Radar — scroll to finding (anchor = dedup_key)
**Files:** `ui/insights/MoneyRadarScreen.kt`.
- [ ] Add `scrollAnchor: String? = null, onAnchorConsumed: () -> Unit = {}` to `MoneyRadarScreen` (dispatcher), `MoneyRadarMain`, `MoneyRadarCover` (forward them).
- [ ] The screen uses a `LazyColumn` with `listState`. Compute the flat index of the finding whose `dedup_key == scrollAnchor` (mirror the existing section-index math used for severity pills). In a `LaunchedEffect(scrollAnchor, state.findings)`, if a match is found: `listState.animateScrollToItem(index)`, trigger a brief highlight on that finding card (e.g. an `animateColorAsState` border/background that pulses for ~1.2s keyed on the matched dedup_key), then call `onAnchorConsumed()`. If no match (e.g. findings still loading), do nothing until they load.
- [ ] Pass the matched key down to `moneyRadarFindingsItems` so the matching `FindingCard` renders the highlight. Verify `FindingDto` exposes `dedupKey` (read the DTO; use the real field name).
- [ ] Compile gate → BUILD_OK. Commit: `feat(android): Money Radar scroll-to-finding on notification tap`

## Task 4: Bills — scroll to card (anchor = last4)
**Files:** `ui/bills/BillsScreen.kt`.
- [ ] Add `scrollAnchor: String? = null, onAnchorConsumed: () -> Unit = {}` to `BillsScreen` (dispatcher), `BillsMain`, `BillsCover` (forward).
- [ ] Bills "Your cards" loop renders `BillCardArt` per card in a `verticalScroll` Column (Main) / `CardCarousel` (Cover). For the card whose `last4 == scrollAnchor`, attach a `BringIntoViewRequester` (`@OptIn(ExperimentalFoundationApi::class)`): `Modifier.bringIntoViewRequester(req)`. In a `LaunchedEffect(scrollAnchor, cards)`, if a card matches: `req.bringIntoView()` + brief highlight (pulse border on that `BillCardArt`), then `onAnchorConsumed()`.
- [ ] Compile gate → BUILD_OK. Commit: `feat(android): Bills scroll-to-card on notification tap`

## Task 5: Rewards + Budget — scroll to item
**Files:** `ui/rewards/RewardsScreen.kt`, `ui/budget/BudgetScreen.kt`.
- [ ] Rewards: add `scrollAnchor`/`onAnchorConsumed` to dispatcher + Main + Cover. Match the reward card by `last4 == scrollAnchor`; attach `BringIntoViewRequester` to that card; `bringIntoView()` + highlight + consume in a `LaunchedEffect`.
- [ ] Budget: add `scrollAnchor`/`onAnchorConsumed` to dispatcher + Main + Cover. Match the category row by `category == scrollAnchor` (the budget category name); attach `BringIntoViewRequester`; `bringIntoView()` + highlight + consume.
- [ ] Wire both screens' new params from `AppShell`'s NavHost (Task 2 plumbing) with `scrollAnchor = pendingAnchor`, `onAnchorConsumed = onAnchorConsumed`.
- [ ] Compile gate → BUILD_OK. Commit: `feat(android): Rewards + Budget scroll-to-item on notification tap`

## Acceptance
- Backend suite green; `assembleDebug` clean; public NavHost destinations still resolve.
- Tapping a bill/radar/reward/budget notification opens the screen, scrolls the specific item into view, and briefly highlights it; the anchor is consumed (no re-scroll on rotation/recomposition).
- On-device verification on the Flip 7 (owed): trigger a notification (or use the test push) and confirm scroll+highlight.
