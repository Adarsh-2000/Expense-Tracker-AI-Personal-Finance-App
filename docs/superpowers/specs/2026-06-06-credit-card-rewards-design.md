# Credit-Card Rewards — Design Spec

**Date:** 2026-06-06
**Status:** Approved design, pending spec review → implementation plan

## Goal

Give the user a per-credit-card view of reward points: **how many points** each card
holds, **what they're worth** (≈₹), **where/how to redeem** them, **when they expire**,
and a **history of redemptions + active vouchers** — sourced primarily from the
password-protected statement PDFs (decrypted with name + DOB), supplemented by
SMS/email redemption events and a hand-curated redemption catalog.

## Data sources & honest reliability

| Source | What we extract | Reliability |
|--------|-----------------|-------------|
| **Statement PDF** (Axis `cc.statements@axisbank.com`, IDFC `statement@idfcfirstbank.com`), decrypted with name+DOB | Opening balance, earned, redeemed, expired, **closing points balance**, points-expiring-next + date | **High** — structured table, the primary source |
| **SMS/email redemption events** (~17 in corpus) | "1,780 pts redeemed at MakeMyTrip", available-after balance | Medium — opportunistic; fills gaps between statements |
| **EDGE voucher emails** | Voucher code, denomination, **expiry date** (e.g. Amazon Prime, exp 07 Jun 2026) | High when present |
| **Curated catalog** (maintained in code, *not* parsed) | Redemption partners, ≈₹ conversion rate, expiry policy, portal URL — per program | Static knowledge; IDFC FIRST + Axis EDGE only (the user's cards) |

**Promotional reward SMS (the ~90% "noise")** encode *earn rules* ("30 pts per ₹150 on
grocery with FIRST POWER+"). Not used now; noted as a future earn-optimizer extension.

### Risk / assumption (must be verified live)
The PDF points-summary parser **cannot be fully verified offline** — `emu.db` stores
email *bodies*, not attachments. The parser is built against the known Axis/IDFC
statement layouts and unit-tested with synthetic decrypted text; final verification is
an on-device sync against real Gmail. IDFC's exact statement-password format is treated
as **confirm-on-first-statement**: the decrypt step tries candidate passwords in order
(see below) and the first that opens the PDF wins.

## Statement password derivation

Stored identity lives in `user_profile` (already has `pan`, `dob`; **add `name`**).

- **Axis:** `name[:4].upper()` (letters only, ignoring spaces/periods) + `DDMM` of DOB.
  `"Harsh Khandelwal"` + `29-03-1998` → `HARS2903`.
- **IDFC (candidates, tried in order):** `name4+DDMM`, `DDMMYYYY`, `name4+last4(card)`.

The existing `holdings.py:_decrypted_text(pdf_bytes, password)` returns `None` on failure,
so the service loops candidates until one decrypts. This mirrors the proven Zerodha path
(PAN as statement password).

---

## Backend architecture

Follows the existing **Zerodha-holdings** pattern (entity + repository, async I/O,
best-effort fold into `/sync/gmail`) and the project's global standards (repository &
entity patterns; async/await for all I/O).

### Entities (`app/entities.py`)
- **`UserProfile`** — add `name: str | None` (for password derivation).
- **`CardReward`** — latest snapshot **per card** (replace-per-card, like `Holding`):
  `bank, last4, program, points_balance, earned, redeemed, expiring_points,
  expiring_date, statement_period, as_of`.
- **`RewardEvent`** — append-only log: `bank, last4, points, merchant, occurred_at,
  voucher_code, voucher_expiry, kind` (`kind` ∈ redemption | voucher). Deduped on
  `(bank, last4, occurred_at, points, voucher_code)`.

### Repositories (`app/repositories.py`)
- `CardRewardRepository` — `upsert_for_card(...)`, `list_all()`.
- `RewardEventRepository` — `add_if_new(...)`, `list_recent(limit)`, `list_expiring_vouchers(by_date)`.

### Parsing (`app/parsing/card_rewards.py`)
- `extract_points_summary(text, bank) -> dict | None` — per-bank regex/positional
  extraction over decrypted statement text (Axis EDGE block, IDFC FIRST block).
- `parse_redemption_message(channel, sender, body) -> RewardEvent-dict | None` — pulls
  redemption points + merchant + available-after balance from SMS/email.
- `parse_voucher_email(body) -> RewardEvent-dict | None` — voucher code + denomination +
  expiry date (EDGE voucher emails).

### Catalog (`app/data/reward_catalogs.py`)
Static dict keyed by program enum (`IDFC_FIRST`, `AXIS_EDGE`):
`{ display_name, conversion_rate_inr_per_point, expiry_policy, portal_url,
   partners: [ {label, kind} ] }`. Drives ≈₹ value + "Redeem at" chips.

### Gmail fetch (`app/connectors/gmail_poller.py`)
Add statement-PDF fetch queries (reusing the existing first-PDF-attachment fetcher):
- `from:cc.statements@axisbank.com has:attachment`
- `from:statement@idfcfirstbank.com has:attachment`

### Service (`app/services/card_rewards.py`)
`async def sync_card_rewards(session) -> dict`:
1. Load profile (name, dob). If no name → skip statement parse (still parse SMS/email).
2. For each issuer: fetch latest statement PDF → derive password candidates → decrypt →
   `extract_points_summary` → `CardRewardRepository.upsert_for_card`.
3. Scan recent `raw_messages` for redemption/voucher events → `RewardEventRepository.add_if_new`.
4. Commit. Return status dict. **Best-effort**: wrapped in try/except, never breaks ingestion.
Wired into `routers/sync.py:/sync/gmail` after `sync_investments` (same best-effort shape).

### FCM (reuse existing Money Radar push pipeline)
When `expiring_date` (points) or `voucher_expiry` falls within the alert window
(≤ N days, N configurable, default 14), enqueue a push via the existing pushed-
notifications path. Deduped via `pushed_notifications` so a card only pings once per
expiry window.

### Router (`app/routers/rewards.py`)
`GET /rewards` → per-card payload:
```
{ total_value_inr, total_points, cards: [
   { bank, last4, program, display_name, points_balance, value_inr, as_of,
     statement_period, expiring_points, expiring_date, stale,
     redemption_options: [ {label, kind} ],
     recent_events: [ {kind, points, merchant, occurred_at, voucher_code, voucher_expiry} ] }
 ] }
```
Value/options come from the catalog; `stale = as_of older than 35 days`.

---

## Android UI — 3-tier surface model

Placement is a **hierarchy matched to urgency**, not a single screen.

| Tier | Surface | Shows | Trigger |
|------|---------|-------|---------|
| **Reference** (pull) | Insights → **Rewards tile** → `RewardsScreen` | balances, ≈₹, redemption options, history | always |
| **Urgent** (push) | **Dashboard strip** (mirrors `MoneyRadarStripCard`) + **FCM push** | "⚠️ X pts expire in N days · Redeem →" | only when expiring ≤ window |
| **Confidence** | history list on `RewardsScreen` + existing notifications | redemption events | on event |

Cross-link both ways: Bills card → its rewards; Rewards card → its bill (`txncard/...`).

### Insights hub tile grid (5 tiles)
```
[ How I Pay | Save More ]
[ Budgets   | Rewards ★ ]
[ Money Radar (full width)]
```

### `RewardsScreen` (`ui/rewards/`)
`RewardsScreen.kt` + `RewardsViewModel.kt` + `RewardsUiState.kt`, plus API DTOs,
`ExpenseRepository` method, and a `GetRewardsUseCase` (matches existing use-case wiring).

Layout (reuses `CredCard`, `MoneyText`, `cardBankColor`, amber→red ramp
`#FBBF24`→`#EF4444`):
```
←  Rewards
┌─────────────────────────────────────┐
│  TOTAL REDEEMABLE   ≈ ₹ 4,960        │  ← aggregate hero (omitted if 1 card)
│  across 2 cards · 21,300 pts         │
└─────────────────────────────────────┘
⚠️ 1,250 IDFC FIRST pts expire 18 Jun    ← amber banner, only if expiring
┌─────────────────────────────────────┐
│ ▎IDFC FIRST ••0684    FIRST Rewards  │  ← ▎ cardBankColor accent
│  12,400 pts        ≈ ₹3,100          │
│  as of 02 Jun (statement)            │
│  ⚠ 1,250 expire 18 Jun               │
│  Redeem at: [Travel][Amazon][…]      │  ← catalog chips
│  ⌄ recent redemptions                │
└─────────────────────────────────────┘
┌─────────────────────────────────────┐
│ ▎Axis EDGE ••9966     EDGE Rewards   │
│  8,900 pts         ≈ ₹1,860          │
│  🎟 Amazon Prime voucher · exp 07 Jun │  ← active voucher + expiry
│  Redeem at: [Flights][Gift cards]    │
└─────────────────────────────────────┘
```
Sort: expiring-soonest first, then ≈₹ value desc. ViewModel exposes `refresh()`;
screen uses pull-to-refresh + `LifecycleResumeEffect` (matches NetWorthScreen).

### Dashboard strip
A conditional `RewardsExpiryStripCard` shown when the `/rewards` payload reports any
card expiring within the window — same visual/interaction pattern as
`MoneyRadarStripCard`, tapping opens `RewardsScreen`.

### Edge / empty / stale states
- **No statement parsed** → card shows "Sync to load rewards" + catalog redemption
  options still render (they don't need a balance).
- **Stale** (`as_of` > 35 days) → balance muted + "as of <date> · may be outdated."
- **Single card** → no aggregate hero.
- **No name set** → a one-time prompt to add name (Settings) to unlock statement parsing.

---

## Out of scope (YAGNI)
- Earn-rate optimizer ("use FIRST POWER+ for groceries") — parseable from promo SMS, future.
- Points-value-over-time charts / balance history.
- In-app redemption booking flows (we link out to the portal).

## Testing approach
- **Backend (TDD, pytest):** unit tests for `extract_points_summary` (synthetic Axis &
  IDFC decrypted text), `parse_redemption_message`, `parse_voucher_email`, password
  derivation, and catalog ≈₹ math. Service test asserts `/sync/gmail` calls
  `sync_card_rewards` best-effort and survives its failure (mirrors
  `test_sync_investments.py`).
- **Android:** Kotlin compile gate per task; final on-device verification via `run-app`
  (real backend + statement sync) — confirm balances, ≈₹, expiry banner, dashboard
  strip, and FCM push.

## Affected / new files (summary)
**Backend:** `entities.py` (+name, +CardReward, +RewardEvent), `repositories.py`,
`parsing/card_rewards.py` (new), `data/reward_catalogs.py` (new),
`connectors/gmail_poller.py` (+queries), `services/card_rewards.py` (new),
`routers/rewards.py` (new), `routers/sync.py` (wire-in), `main.py` (register router),
`tests/test_card_rewards.py` (new).
**Android:** `ui/rewards/RewardsScreen.kt` + `RewardsViewModel.kt` + `RewardsUiState.kt`
(new), `ui/insights/InsightsScreen.kt` (+tile), `ui/dashboard/DashboardScreen.kt`
(+strip) + `DashboardViewModel`/`UiState` (+expiry data), `ui/bills/BillsScreen.kt`
(+cross-link), `ui/shell/AppShell.kt` (+route), `data/api/ExpenseApi.kt` +
`data/repository/ExpenseRepository.kt` + `domain/UseCases.kt` (+rewards endpoint).
