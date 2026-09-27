# Reward Card Detail Screen + Offers Catalog — Design Spec

**Date:** 2026-06-06
**Status:** Approved design, pending spec review → implementation plan
**Builds on:** `2026-06-06-credit-card-rewards-design.md` (the rewards feature)

## Goal

Tapping a card on the Rewards screen opens a per-card **detail screen** showing that
card's points hero, a **curated benefits/offers catalog** (lounge, dining, fuel,
partner brands, milestone perks), a **link out to the bank's live offers portal**, the
full redemption options, and the complete redemption/voucher history.

## Research finding (why curated, not fetched)

A full scan of the 12,769-message `emu.db` corpus confirmed **real per-card merchant
offers are not present at usable granularity**. The inbox contains: (a) card-acquisition
marketing for cards the user does not hold ("Apply for FIRST SWYP… 20% off on food/
travel/cosmetics"), (b) generic benefit/earn-rules mostly for other card variants
(FIRST POWER+, Mayura, Ashva), and (c) transaction noise. Genuine merchant deals live on
the bank/Visa/Mastercard offers portals, never in the inbox in parseable form.

**Decision:** the detail screen's offers come from a hand-maintained catalog of each
program's *standing benefit categories*, with a "Explore live offers →" deep-link to the
bank portal for real-time specifics. We never fabricate a specific "X% at merchant Y till
Z". This mirrors the existing redemption-catalog pattern.

## Architecture (no new endpoint, no nav-arg objects)

The catalog is static and per-card data is already returned by `GET /rewards`. So:

- **Backend:** extend each program in `app/data/reward_catalogs.py` with an
  `offer_categories` list. Surface `portal_url` (already in the catalog) and
  `offer_categories` in the existing `/rewards` card payload.
- **Android:** each `RewardCard` on `RewardsScreen` becomes tappable → navigates to
  `RewardDetailScreen(bank, last4)`. The detail screen has a thin ViewModel that calls the
  same `GetRewardsUseCase`, then filters the card list to the matching `bank+last4`. DRY,
  trivial for 2 cards, no object passed through navigation.

## Backend changes

### `app/data/reward_catalogs.py`
Add an `offer_categories` list to each program entry. Each item:
`{title: str, description: str, kind: str}` where `kind` ∈
{lounge, dining, movies, fuel, shopping, travel, milestone, forex}. Keep wording factual
and non-specific (benefit *types*, not dated deals). Add a helper:
```python
def offer_categories_for(program: str) -> list[dict]:
    cat = _CATALOG.get(program)
    return cat.get("offer_categories", []) if cat else []
```

**IDFC_FIRST** `offer_categories` (FIRST Rewards / Millennia standing benefits):
- ✈ Airport lounge — complimentary domestic lounge visits on milestone spends (lounge)
- 🍽 Dining — discounts at partner restaurants (dining)
- 🎬 Movies — Buy-1-Get-1 movie tickets via partner (movies)
- ⛽ Fuel — 1% fuel surcharge waiver (fuel)
- 🛍 Online brands — accelerated reward points on online spends (shopping)
- 🌍 Low forex markup on international spends (forex)

**AXIS_EDGE** `offer_categories`:
- 🍽 Dining Delights — savings at partner restaurants (dining)
- ✈ Airport lounge — complimentary lounge access on eligible cards (lounge)
- ⛽ Fuel — fuel surcharge waiver (fuel)
- 🛍 EDGE partner brands — offers across shopping/travel partners (shopping)

### `app/routers/rewards.py`
In each card dict, add:
```python
"portal_url": cat.get("portal_url"),
"offer_categories": cat.get("offer_categories", []),
```
(`cat` is already `catalog_for(card.program)` in the loop.)

## Android changes

### DTOs (`data/api/ExpenseApi.kt`)
Add:
```kotlin
@JsonClass(generateAdapter = true)
data class RewardOfferCategoryDto(
    val title: String, val description: String, val kind: String,
)
```
Extend `RewardCardDto` with:
```kotlin
    @Json(name = "portal_url") val portalUrl: String?,
    @Json(name = "offer_categories") val offerCategories: List<RewardOfferCategoryDto> = emptyList(),
```

### `ui/rewards/RewardDetailViewModel.kt` + `RewardDetailUiState.kt`
Thin VM: takes `GetRewardsUseCase` + the target `bank`/`last4`; on load, fetches `/rewards`
and exposes the single matching `RewardCardDto` (or null + a not-found state). `refresh()`
mirrors `RewardsViewModel`.

### `ui/rewards/RewardDetailScreen.kt`
`RewardDetailScreen(repository, bank, last4, onBack)`. Sections top→bottom:
1. **App bar** — "${displayName} ••${last4}" + back.
2. **Hero `CredCard`** — `bank ••last4` w/ `cardAccent`, points balance, ≈₹ value, "as of"
   line (muted "may be outdated" when `stale`), amber expiry banner when `expiringPoints>0`.
3. **"CARD BENEFITS & OFFERS"** — one row per `offerCategories` item: leading emoji/icon
   derived from `kind`, `title` (primary), `description` (secondary). Empty → hide section.
4. **"Explore live offers →" button** — opens `portalUrl` in a browser via
   `Intent(Intent.ACTION_VIEW, Uri.parse(portalUrl))` (guard null/blank → hide).
5. **"REDEEM POINTS"** — the `redemptionOptions` chips (same chip style as RewardsScreen).
6. **"HISTORY"** — the FULL `recentEvents` list (redemptions show merchant + pts + date;
   vouchers show 🎟 code + expiry). Empty → "No redemptions yet."
   No early returns in composables — use if/else.

### `ui/rewards/RewardsScreen.kt`
Make each `RewardCard` tappable. Add an `onOpenCard: (bank, last4) -> Unit` param to
`RewardsScreen`; wrap each `RewardCard` so a tap calls `onOpenCard(card.bank, card.last4)`
(add the click to the existing `CredCard` or an outer Box — match how other tappable
CredCards in the app do it). Pass a small affordance (e.g. a chevron) is optional.

### `ui/shell/AppShell.kt`
Add a route `rewarddetail/{bank}/{last4}` with two String nav args. Wire
`onOpenCard = { bank, last4 -> navController.navigate("rewarddetail/$bank/$last4") }` into
the `RewardsScreen(...)` invocation. Add the `composable(...)` block rendering
`RewardDetailScreen(repository = app.repository, bank = …, last4 = …, onBack = { popBackStack() })`.
Add the route to the `hideBottomBar` set (full-screen).

## Testing

- **Backend (pytest):** extend `tests/test_api_rewards.py` —
  `test_rewards_card_includes_offers_and_portal` asserts a seeded IDFC card's `/rewards`
  payload contains a non-empty `offer_categories` (each with title/description/kind) and a
  `portal_url`. Add a unit test for `offer_categories_for("IDFC_FIRST")` non-empty and
  `offer_categories_for("UNKNOWN") == []`.
- **Android:** Kotlin compile gate per task; final on-device verify — tap a Rewards card →
  detail screen renders hero + benefits + redeem + history; "Explore live offers" opens the
  browser; back returns to the Rewards list.

## Out of scope (YAGNI)
- Live/real-time merchant deal fetching (not in inbox; portal link covers it).
- Parsing earn-rules from promo SMS (deferred; noisy, mostly other cards).
- Per-offer redemption/booking flows.

## Affected / new files
**Backend:** `app/data/reward_catalogs.py` (offer_categories + helper),
`app/routers/rewards.py` (surface 2 fields), `tests/test_api_rewards.py`.
**Android:** `data/api/ExpenseApi.kt` (+DTO, +2 fields), `ui/rewards/RewardDetailUiState.kt`
(new), `ui/rewards/RewardDetailViewModel.kt` (new), `ui/rewards/RewardDetailScreen.kt`
(new), `ui/rewards/RewardsScreen.kt` (tappable card + onOpenCard), `ui/shell/AppShell.kt`
(route).
