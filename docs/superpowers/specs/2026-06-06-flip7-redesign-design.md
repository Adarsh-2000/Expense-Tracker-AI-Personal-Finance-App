# Ledger Noir — Galaxy Z Flip 7 Foldable-First Redesign — Design Spec

**Date:** 2026-06-06
**Status:** Approved direction (mockups v2 approved), pending spec review → implementation plan
**Device:** Samsung Galaxy Z Flip 7 (SM-F766B) — Main inner **6.9″ (2520×1080, ≈407×950dp)** · Cover **4.1″ (948×1048, ≈361×390dp)**
**Visual reference:** CRED (+ Jupiter, Fi, INDmoney, Groww patterns)
**Companion artifacts:** `docs/superpowers/specs/flip7-mockups-v2.html` (approved mockups), `docs/superpowers/specs/2026-06-06-flip7-redesign-screens-exploration.md` (per-screen exploration)

## Goal
Redesign the Android app to be **foldable-first for the Flip 7**, running well on both the tall main screen and the near-square cover screen, with a CRED-grade visual execution — while **preserving the existing card visual language** the user likes. Folding/unfolding must never restart the app or lose state. Fix all alignment/inset/overflow issues along the way.

## Non-goals (YAGNI)
- Generic flex/tabletop (half-folded hinge) postures — not requested.
- Tablet/desktop multi-pane beyond a safe `Wide` fallback.
- Backend changes (this is an Android-only redesign).
- Replacing the card/credit-card visual language (explicitly kept).

## Design language
**Keep (reuse as-is):**
- `CredCard` — `SurfaceDark` fill, 20dp radius, 8dp soft shadow, 1dp `BorderGray` hairline.
- `MoneyText` — display typeface, tabular figures, +/− rose/green sign coloring.
- Bank colors (`AXIS #AE275F`, `IDFC #9C1D26`), `formatMoney`/`formatWhole`, existing theme palette (CanvasBlack, AccentTeal/Green, DebitRose, AmberWarn).

**Add (CRED-grade):** credit-card-art faces (bank gradient, network, chip, `••last4`, on-card utilization/due), card carousel, merchant logos in the feed, allocation ring with center total, one dominating hero number per screen, generous vertical rhythm, gradient primary CTAs.

## Architecture

### Form-factor detection (`ui/designsystem/FormFactor.kt` — new)
```kotlin
enum class FormFactor { Cover, Phone, Wide }

val LocalFormFactor = staticCompositionLocalOf { FormFactor.Phone }

@Composable
fun ProvideFormFactor(content: @Composable () -> Unit) {
    BoxWithConstraints {
        val ff = when {
            maxWidth >= 600.dp -> FormFactor.Wide          // safety fallback
            maxHeight < 480.dp -> FormFactor.Cover          // 4.1" square ≈ 390dp tall
            else -> FormFactor.Phone                        // 6.9" main ≈ 950dp tall
        }
        CompositionLocalProvider(LocalFormFactor provides ff, content = content)
    }
}
```
- Wraps the app content under the theme. Recomposes automatically when the window resizes on fold (given `configChanges`, no Activity recreation).
- `Wide` renders the `Main` tree inside a width-capped `ResponsiveContainer`.

### Two layout trees per screen
Each screen exposes a small dispatcher that selects by form factor and shares all building blocks:
```kotlin
@Composable
fun DashboardScreen(/* deps */) = when (LocalFormFactor.current) {
    FormFactor.Cover -> DashboardCover(/* state, callbacks */)
    else             -> DashboardMain(/* state, callbacks */)   // Phone + Wide
}
```
- ViewModel/state is **hoisted above** the dispatcher and shared, so switching trees on fold preserves data and scroll is restored via `rememberSaveable`.
- `…Cover()` and `…Main()` are pure presentation over the same state.

### Adaptive navigation (`ui/shell/AppShell.kt` — modify)
- **Phone/Wide:** full bottom nav (current 5–6 destinations with labels).
- **Cover:** compact bottom bar — 3 primary icons (Home, Bills, Txns) + a **More** sheet for the rest (icon-only, 48dp targets).
- Nav state hoisted so fold doesn't reset the back stack.

### No restart on fold (`AndroidManifest.xml` — modify)
Add to `<activity .MainActivity>`:
```
android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation|keyboardHidden|density|uiMode"
android:resizeableActivity="true"
```
Compose recomposes on configuration change; ViewModels + `rememberSaveable` preserve state.

### Insets / edge-to-edge (`MainActivity` + scaffold)
- `enableEdgeToEdge()`; replace flat `padding(vertical=20.dp)` with `WindowInsets.safeDrawing` (status bar, nav/gesture bar, cover camera cutout, rounded corners) via a shared scaffold.

### Design tokens (`ui/designsystem/Tokens.kt` — new)
- **Spacing:** `Space.xs=4, s=8, m=12, l=16, xl=24, xxl=32`.
- **Type scale** with a cover ramp: `display 56→34sp`, `title 22→18sp`, `body 14→13sp`, chosen by `LocalFormFactor`.
- Corner radii (card 20dp, cardArt 18dp), elevation tokens.

### New shared components (`ui/designsystem/`)
- `CreditCardArt(bank, last4, network, utilizationPct?, due?, points?)` — gradient face per bank; overlays utilization bar / due pill / points depending on context (used on Dashboard, Bills, Rewards).
- `CardCarousel(items)` — horizontal `Pager` with peeking next card + dots.
- `MerchantLogo(merchant, category)` — colored chip with brand letter/emoji, category fallback color.
- `AllocationRing(slices, centerLabel)` — extends existing `CategoryDonut`.
- `HeroNumber(label, amount, change?)` — the dominating hero block (label + `MoneyText` + colored change).
- `LedgerScaffold(formFactor, nav, content)` — applies insets + picks navigation.

## Per-screen designs
Authoritative layouts are the approved **`flip7-mockups-v2.html`**; summary per screen lives in `…-screens-exploration.md`. Each screen gets a `…Main()` and `…Cover()` tree:
1. **Dashboard** — net-worth hero → card-art carousel → quick actions → recent feed (merchant logos). Cover: hero + one swipeable card.
2. **Bills** — total-due hero → per-card *card art* with due-urgency pill + utilization + gradient Pay CTA. Cover: due hero + most-urgent card + Pay.
3. **Transactions** — segmented filter, sticky date + daily net, merchant logos + category·source, FAB. Cover: trimmed rows + icon filters.
4. **Portfolio** — value hero + day-change → allocation ring + legend → holdings with P&L. Cover: value + ring.
5. **Savings** — total-liquid hero → account cards → FD maturity progress. Cover: total + accounts, FDs collapsed.
6. **Insights** — spend hero + trend bars → tile grid with live badges. Cover: hero + urgent (badged) tiles.
7. **Rewards** — ₹-value hero + expiry alert → per-card *card art* (pts + ₹ + redeem icons). Cover: hero + expiry + top card.
8. **Settings** — labeled sections, server health dot + test-connection, account + sign-out, danger in rose. Cover: single-column rows.
9. **Sign-in** — centered brand, single CTA, server-reachability dot, specific inline errors.
Remaining screens (Net Worth, Money Radar, Save More, How I Pay, Budget, Notifications, Onboarding, detail screens) follow the same hero+cards+adaptive-nav patterns; mocked/handled within their phases.

## Phasing (each phase = working software + its own implementation plan)
1. **P1 — Foundation:** `FormFactor`, `ProvideFormFactor`, tokens, `LedgerScaffold` + adaptive nav, manifest `configChanges`, edge-to-edge insets, shared components (`CreditCardArt`, `CardCarousel`, `MerchantLogo`, `AllocationRing`, `HeroNumber`). Prove end-to-end by converting **Dashboard** to Main+Cover. **Acceptance:** fold/unfold keeps state; Dashboard renders correctly on main and cover on device.
2. **P2 — Money core:** Bills, Transactions (Main+Cover).
3. **P3 — Insights cluster:** Insights, Rewards, Money Radar, Save More, How I Pay, Budgets.
4. **P4 — Wealth:** Portfolio, Savings, Net Worth.
5. **P5 — Utility:** Settings, Sign-in, Onboarding, Notifications, detail screens (RewardDetail, Last3Breakdown, TransactionList).

## Testing
- **Per screen:** compile gate; on-device verification on **both** the main 6.9″ screen and the cover 4.1″ screen (cover via Good Lock/Labs enablement); fold↔unfold transition keeps state & scroll; insets correct (no content under status/gesture bars or camera cutout); no overflow/clipping; 48dp touch targets.
- Spot-check `Wide` fallback via a resized window / large emulator.

## Affected / new files (P1)
**New:** `ui/designsystem/FormFactor.kt`, `ui/designsystem/Tokens.kt`, `ui/designsystem/CreditCardArt.kt`, `ui/designsystem/CardCarousel.kt`, `ui/designsystem/MerchantLogo.kt`, `ui/designsystem/AllocationRing.kt`, `ui/designsystem/HeroNumber.kt`, `ui/designsystem/LedgerScaffold.kt`.
**Modify:** `AndroidManifest.xml` (configChanges/resizeable), `MainActivity.kt` (edge-to-edge + `ProvideFormFactor`), `ui/shell/AppShell.kt` (adaptive nav), `ui/designsystem/Responsive.kt` (form-factor aware), `ui/dashboard/DashboardScreen.kt` (split into Main/Cover).
Later phases modify their respective screen files following the same split.

## Open risks
- **Cover-screen launch** requires the user to enable the app on the cover via Good Lock → MultiStar; the app only guarantees a correct layout when it runs there. Documented, not a code task.
- Full redesign touches every screen — mitigated by phasing + reusing the existing (liked) card components + per-task review.
