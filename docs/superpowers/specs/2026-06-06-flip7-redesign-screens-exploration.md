# Ledger Noir — Flip 7 Redesign: Screen-by-Screen Exploration

**Date:** 2026-06-06
**Device:** Samsung Galaxy Z Flip 7 (SM-F766B) — Main 6.9″ (2520×1080, ~407×950dp) · Cover 4.1″ (948×1048, ~361×390dp)
**Design reference:** CRED (deep black, huge display numbers, generous rhythm, rounded cards, minimal snappy nav, one primary action per screen)
**Approach:** Two layout trees per screen (`…Main()` / `…Cover()`), shared design tokens + components.

Legend: `▮` hero number · `▭` card · `◷` chip · `●` nav dot

---

## Preserved components (user likes these — keep, don't redesign)
The existing **card and credit-card visual language stays** as the foundation; the redesign reworks layout/nav/spacing *around* it.
- **`CredCard`** — `SurfaceDark` fill, 20dp radius, 8dp soft shadow, 1dp `BorderGray` hairline, 20dp padding.
- **`MoneyText`** — display typeface, tabular figures (`tnum`), +/− rose/green sign coloring.
- **Credit-card treatment** — bank-color chip (`AXIS #AE275F`, `IDFC #9C1D26`), `••last4`, utilization bar + chevron + "Adjust limit" (just shipped), RewardDetail accent-bar hero.
- **`formatMoney` / `formatWhole`** — Indian `%,` grouping.
These become the shared building blocks both `…Main()` and `…Cover()` layout trees compose.

## Cross-app foundations (apply to every screen)
- **Edge-to-edge + insets:** respect status bar, nav bar, cover-screen rounded corners & camera cutout (`WindowInsets.safeDrawing`). Today screens use a flat `padding(vertical=20.dp)` that ignores insets.
- **No restart on fold:** `configChanges` on MainActivity so opening/closing keeps state & scroll position.
- **Spacing scale:** 4/8/12/16/24/32; **type scale** with a Cover ramp (display 56→34sp, title 22→18sp).
- **Cover nav:** the 6-tab bottom bar can't fit 361dp square → icon-only bar of 5 + a "More" sheet, OR a swipe-able top segment.
- **Touch targets:** min 48dp everywhere (several current chips/links are ~32dp).

---

## 1. Dashboard (Home)
Current: NET WORTH, Notifications, Sync Messages/Email (SOURCES), "Where it went", LAST 3 MONTHS, Spent, View-all.

```
MAIN 6.9″                          COVER 4.1″
┌────────────────────────┐         ┌──────────────┐
│ Hi Harsh      ⚙  🔔3   │         │ Spent · Jun  │
│ NET WORTH              │         │ ▮ ₹1,24,500  │
│ ▮ ₹ 18,42,300   ▲2.1% │         │ ───────────  │
│ ───────────────────── │         │ ▭ 2 bills due│
│ [Spend] [Bills] [Radar]│        │ ▭ 1 alert ●  │
│ ▭ Where it went (donut)│         │ [donut sm]   │
│ ▭ Last 3 months (bars) │         │ ⌄ more       │
│ Recent ▾  View all →   │         │ ● ● ● ● ●    │
│ ● ● ● ● ● ●            │         └──────────────┘
└────────────────────────┘
```
**Suggestions**
1. **One hero metric.** Make Net Worth the single dominating display number (CRED-style), freshness (`▲2.1% · as of …`) as a quiet sub-line — not competing cards.
2. **Quick-action row** (Spend / Bills / Money Radar) as pill buttons under the hero — replaces scattered entry points, thumb-reachable.
3. **Collapse "SOURCES" (Sync SMS/Email)** into Settings or a dismissible "set-up" banner; it's setup noise on a daily home screen.
4. **Cover = glance feed:** hero (month spend), then the 2–3 most urgent items (bills due, radar alert) as tappable rows; everything else one tap away.
5. **Notification bell with count** in the top bar instead of a full "Notifications" card.
6. **Snap the donut + bars into a swipeable "insight carousel"** on cover (one viz at a time) to save vertical space.

---

## 2. Transactions (Txns)
Current: date groups (Today/Yesterday/…), All/Spending(debit)/Income(credit) filter, search, From/To, Add transaction.

```
MAIN 6.9″                          COVER 4.1″
┌────────────────────────┐         ┌──────────────┐
│ Transactions       +   │         │ Txns      🔍 +│
│ [All][Spend][Income]   │         │ [All][−][+]  │
│ 🔍 Search merchants…   │         │ Today        │
│ Today          −₹2,340 │         │ ▭ Swiggy −340│
│ ▭ Swiggy        −₹340  │         │ ▭ Uber  −180 │
│ ▭ Uber          −₹180  │         │ Yesterday    │
│ Yesterday              │         │ ▭ Amzn −1,2k │
│ ▭ Amazon      −₹1,299  │         │ ● ● ● ● ●    │
│ ● ● ● ● ● ●            │         └──────────────┘
└────────────────────────┘
```
**Suggestions**
1. **Sticky date headers** with a per-day net total on the right (`Today −₹2,340`) — fast scanning.
2. **Segmented filter as a single pill control** (All · Spend · Income), not three separate chips; selected state filled teal.
3. **Search + date range collapse into one expandable bar** (🔍 → reveals From/To) to reclaim vertical space, critical on cover.
4. **Merchant avatars/emoji glyphs** for instant recognition (CRED uses brand marks); fallback to category color.
5. **Cover:** icon-only filter (`All / − / +`), search as an icon that expands full-screen; rows show merchant + amount only (drop secondary metadata).
6. **FAB for "Add transaction"** anchored bottom-right above nav, instead of a header text button (bigger target, reachable).

---

## 3. Insights (hub)
Current: launcher grid → How I Pay, Save More, Budgets, Rewards, Money Radar; Allocation, Total Spent.

```
MAIN 6.9″                          COVER 4.1″
┌────────────────────────┐         ┌──────────────┐
│ Insights               │         │ Insights     │
│ ▭ Total spent  ▮₹1.24L │         │ ▮ ₹1.24L     │
│ ▭ Allocation (donut)   │         │ [donut]      │
│ How I Pay   Save More  │         │ ▭ How I Pay  │
│ ▭ cards·upi ▭ subs·leak│         │ ▭ Save More  │
│ Budgets     Rewards    │         │ ▭ Budgets    │
│ ▭ caps     ▭ pts·expiry│         │ ▭ Rewards    │
│ Money Radar            │         │ ▭ Radar      │
│ ▭ mandates·forex       │         │ ● ● ● ● ●    │
└────────────────────────┘         └──────────────┘
```
**Suggestions**
1. **2-col tile grid on main, 1-col list on cover** — tiles carry an icon + title + one-line "what's inside" (you already have the subtitles).
2. **Lead with the Total-Spent hero + allocation donut**, then the tiles — gives the hub a focal point instead of a flat menu.
3. **Live badge on tiles** (e.g., Rewards "320 pts expiring", Radar "1 alert") so the hub surfaces urgency.
4. **Consistent tile height & icon size** — current grid items vary; lock to a token (e.g., 96dp tile, 24dp icon).
5. **Cover:** drop the donut to a compact ring, tiles become full-width rows with chevrons; keep the spend hero pinned at top.
6. **Reorder by usage** (most-tapped insight first) — or let the order be data-driven later.

---

## 4. Portfolio (Invest)
Current: PORTFOLIO VALUE, US STOCKS (aggregate), ALLOCATION (Stocks/Funds/Others), Sync now, empty states.

```
MAIN 6.9″                          COVER 4.1″
┌────────────────────────┐         ┌──────────────┐
│ Investments      ⟳ Sync│         │ Invest    ⟳  │
│ PORTFOLIO VALUE        │         │ ▮ ₹4.10L     │
│ ▮ ₹ 4,10,250    ▲4.6%  │         │ ▲4.6%        │
│ ▭ Allocation (donut)   │         │ [ring]       │
│  Stocks 62% Funds 31%  │         │ Stk62 Fnd31  │
│ 🇺🇸 US Stocks  ₹88,400 │         │ 🇺🇸 ₹88,400  │
│ Holdings ▾             │         │ ▭ holdings   │
│ ● ● ● ● ● ●            │         │ ● ● ● ● ●    │
└────────────────────────┘         └──────────────┘
```
**Suggestions**
1. **Portfolio value as the hero** with day/total change colored (green/red) — the number people open this for.
2. **Allocation donut with inline legend** (Stocks/Funds/Others %); tap a slice to filter holdings.
3. **US Stocks as a distinct card** (you only have the aggregate) with a clear "aggregate only" note so it doesn't look like missing data.
4. **Last-synced timestamp + freshness** near Sync; disable button while syncing with a spinner (you have the states — make them consistent).
5. **Empty state with a single CTA** ("Connect a broker / Sync now") instead of plain "No holdings yet".
6. **Cover:** value + change + compact ring only; holdings behind a tap (cover is for the glance, not a table).

---

## 5. Savings
Current: TOTAL LIQUID, Savings accounts (AXIS/IDFC), Fixed deposits (Add FD, edit balance, rate), empty states.

```
MAIN 6.9″                          COVER 4.1″
┌────────────────────────┐         ┌──────────────┐
│ Savings                │         │ Savings      │
│ TOTAL LIQUID           │         │ ▮ ₹3.62L     │
│ ▮ ₹ 3,62,140           │         │ ───────────  │
│ Savings accounts       │         │ ▭ AXIS 2.1L  │
│ ▭ AXIS ••3421  2,10,000│         │ ▭ IDFC 1.5L  │
│ ▭ IDFC ••9182  1,52,140│         │ FDs          │
│ Fixed deposits   + Add │         │ ▭ +Add FD    │
│ ▭ HDFC FD 7.1% ₹1,00,000│        │ ● ● ● ● ●    │
│ ● ● ● ● ● ●            │         └──────────────┘
└────────────────────────┘
```
**Suggestions**
1. **Total Liquid hero** combining accounts + FDs, with a small split caption ("₹2.6L accounts · ₹1.0L FDs").
2. **Account rows with bank color chip + last4 + balance**, "Edit balance" as a quiet inline pencil (not a full button).
3. **FD cards show maturity progress** (a thin bar to maturity date) + rate badge — more useful than rate alone.
4. **Single "+ Add FD" affordance** styled like the rest of the app's add actions (consistency with Txns FAB).
5. **Friendly empty states** with one CTA, matching Bills' "all caught up" pattern.
6. **Cover:** total hero + account rows; FDs collapsed under a row count ("3 FDs ›").

---

## 6. Bills
Current (already partly redesigned): Unbilled/Due summary, Your cards (utilization bar + Adjust limit), View rewards, Pending bills.

```
MAIN 6.9″                          COVER 4.1″
┌────────────────────────┐         ┌──────────────┐
│ Pending Bills          │         │ Bills        │
│ ▭ Unbilled  Due now    │         │ ▮ ₹3,389 due │
│  ₹1,01,252  ₹3,389     │         │ ───────────  │
│ Your cards             │         │ ▭ AXIS 22%▆  │
│ ▭ AXIS ••6978 22%▆ ⌄  │         │ ▭ IDFC set ›│
│   Adjust limit ›       │         │ ▭ 1 bill due │
│ ▭ IDFC ••2198 17%▆ ⌄  │         │ ● ● ● ● ●    │
│ Pending bills …        │         └──────────────┘
└────────────────────────┘
```
**Suggestions**
1. **Lead with "Due now" as the hero** (the actionable number) and Unbilled as secondary.
2. **Utilization bar already good** — add a one-glance color legend the first time, keep the chevron/Adjust pattern we just shipped.
3. **Overdue bills float to top** with a red accent (the "overdue 66d" we saw should be unmissable).
4. **Mark-paid as a swipe action** on a bill row (CRED-like), not just a text link.
5. **Cover:** due-hero + each card as a one-line `BANK pct ▆`; tap → detail; bills as a count row.
6. **Group "Your cards" vs "Pending bills" with clearer section dividers** + spacing tokens.

---

## 7. Rewards
Current: TOTAL REDEEMABLE, per-card (pts, value), REDEEM AT, RECENT, expiry strip.

```
MAIN 6.9″                          COVER 4.1″
┌────────────────────────┐         ┌──────────────┐
│ ‹ Rewards              │         │ ‹ Rewards    │
│ TOTAL REDEEMABLE       │         │ ▮ ₹4,820     │
│ ▮ ₹ 4,820              │         │ ⚠ 320 exp 12d│
│ ⚠ 320 pts expire in 12d│         │ ▭ AXIS 9,640 │
│ ▭ AXIS ••6978  9,640pts│         │ ▭ IDFC 2,110 │
│   REDEEM AT · ✈🍽🛍   │         │ ● ● ● ● ●    │
│ ▭ IDFC ••2198  2,110pts│         └──────────────┘
│ RECENT …               │
└────────────────────────┘
```
**Suggestions**
1. **Total redeemable as hero** in rupee value (not just points) — people think in money.
2. **Expiry as an amber alert chip** right under the hero (you have the strip — elevate it).
3. **Per-card: points + ₹ value + redeem icons** in a tidy row; tap → existing RewardDetail.
4. **Redeem-at icons standardized** (✈ 🍽 🛍 ⛽) with consistent sizing.
5. **Cover:** hero + expiry + per-card value rows only; redeem detail one tap away.
6. **Empty/zero states** per card ("No points yet") instead of blank.

---

## 8. Settings
Current: base URL field, Sync Gmail, account, etc.

```
MAIN 6.9″                          COVER 4.1″
┌────────────────────────┐         ┌──────────────┐
│ ‹ Settings             │         │ ‹ Settings   │
│ ACCOUNT                │         │ ▭ Account    │
│ ▭ Harsh · signed in    │         │ ▭ Server     │
│ SERVER                 │         │ ▭ Sync Gmail │
│ ▭ http://192.168.1.5…  │         │ ▭ Sources    │
│ DATA SOURCES           │         │ ▭ About      │
│ ▭ Sync Gmail   ▭ SMS   │         │ ● ● ● ● ●    │
│ ABOUT / SIGN OUT       │         └──────────────┘
└────────────────────────┘
```
**Suggestions**
1. **Group into labeled sections** (Account / Server / Data sources / About) with consistent row styling + chevrons.
2. **Server URL with inline validation + "test connection"** (would've caught the VPN/IP issues today!).
3. **Sign-out + account email** clearly shown at top (currently buried).
4. **Each row = icon + title + value/secondary + chevron**, 56dp height token.
5. **Cover:** single-column rows, no two-column source grid.
6. **Danger actions (sign out / clear)** visually separated at the bottom in rose.

---

## 9. Sign-in
Current: wordmark, "Continue with Google", error+retry, footer.

```
MAIN 6.9″                          COVER 4.1″
┌────────────────────────┐         ┌──────────────┐
│                        │         │   Ledger     │
│      Ledger            │         │    Noir      │
│      Noir              │         │ [G Continue] │
│  private money…        │         │ server ok ●  │
│  [ G Continue ]        │         │ (compact)    │
│  Verified by your srvr │         └──────────────┘
└────────────────────────┘
```
**Suggestions**
1. **Vertically center the block** on main (lots of dead space today) and shrink the wordmark on cover so the button stays above the fold.
2. **Show a tiny server-reachability dot** ("server reachable ●/○") so connectivity issues are obvious before tapping (today's whole saga).
3. **Inline, specific errors** (not-reachable vs not-allowed vs token) with the right fix hint — keep the retry.
4. **Respect insets** so the button never sits under the gesture bar on cover.
5. **Brand gradient + subtle motion** on the wordmark (CRED-grade first impression).

---

## Phasing (each phase ships working software)
1. **Phase 1 — Foundation:** FormFactor detection (width+height), `LocalFormFactor`, adaptive AppShell + cover nav, manifest `configChanges`, edge-to-edge insets, spacing/type tokens, shared components (HeroNumber, AdaptiveCard, SectionHeader). Prove on Dashboard.
2. **Phase 2 — Money core:** Dashboard, Transactions, Bills (Main+Cover).
3. **Phase 3 — Insights cluster:** Insights hub, Rewards, Money Radar, Save More, How I Pay, Budgets.
4. **Phase 4 — Wealth:** Portfolio, Savings, Net Worth.
5. **Phase 5 — Utility:** Settings, Sign-in, Onboarding, Notifications, detail screens.

Each phase = its own implementation plan via subagent-driven development.
