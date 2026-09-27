# Realistic Credit-Card Faces (+ per-card network) — Implementation Plan

> REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Make Bills (and Rewards) cards look like real credit cards — brand gradient + bank logo fetched from the internet (Clearbit, cached) + EMV chip + contactless + embossed `•••• last4` + holder name + a **network mark the user sets per card** (RuPay/Visa/Mastercard/None) — matching the approved dummy `docs/superpowers/specs/realistic-cards-mockup.html`. Utilization moves below the card face.

**Architecture:** Network isn't in SMS, so it's a per-card override stored on `Account.network` (like `credit_limit`), set via the card edit dialog. Bank logo loads via Coil from Clearbit (`bankLogoUrl(bank)`), with a bank-name text fallback. `CreditCardArt` is rewritten into the realistic face; `BillCardArt` keeps utilization/affordances below it.

**Tech stack:** FastAPI/SQLAlchemy (async), Kotlin/Compose (BOM 2024.04.01), **Coil 2.6.0**.

**Conventions:** Android build env `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; export PATH="$JAVA_HOME/bin:$PATH"`, gradle from `android/`, gate `./gradlew :app:assembleDebug -q && echo BUILD_OK`. Backend tests `cd backend && .venv/bin/python -m pytest -q` (387 baseline, must stay green). No early-return in composables. **`emu.db` is real user data — back it up before ALTER.**

---

## Task 1: Backend — per-card network
**Files:** `backend/app/entities.py`, `backend/app/repositories.py`, `backend/app/schemas.py`, `backend/app/routers/accounts.py`, the cards-state builder (find where `CardStateDto`/cards response + utilization are built — `app/services/utilization.py` / `app/enrichment/cards.py` / `routers/accounts.py`), rewards builder (`routers/rewards.py`/service), tests.

- [ ] `entities.py`: add `network: Mapped[str | None] = mapped_column(String, nullable=True)` to `Account` (mirror the existing `credit_limit` column).
- [ ] **Migrate emu.db (create_all won't ALTER existing tables):**
  ```bash
  cd backend && cp emu.db emu.db.bak.network.$(date +%s)
  .venv/bin/python -c "import sqlite3; c=sqlite3.connect('emu.db'); c.execute('ALTER TABLE accounts ADD COLUMN network VARCHAR'); c.commit(); print('ok')"
  ```
  (If the column already exists, the error is fine — ignore.)
- [ ] `repositories.py`: extend `AccountRepository.set_credit_limit` OR add `set_network(account_id, *, network)`. Prefer a single `set_card_details(account_id, *, credit_limit=None, network=None)` that updates only provided fields (keep `set_credit_limit` working, or have the router call both).
- [ ] `schemas.py`: change/extend the limit payload to carry an optional network — `class SetCardDetailsIn(BaseModel): credit_limit: float | None = Field(default=None, gt=0); network: str | None = None`. (Keep `SetLimitIn` if other code uses it, or migrate the route.)
- [ ] `routers/accounts.py`: the existing `PUT /cards/{account_id}/limit` → accept `SetCardDetailsIn`; set credit_limit and/or network as provided (404 if missing; `{"ok": True}`). Network value normalized to one of `visa|mastercard|rupay` or null.
- [ ] Expose `network` on the card DTOs the faces use: add `network` to the cards-state response (the per-card dict that becomes Android `CardStateDto`) and to the rewards card dict (Android `RewardCardDto`), reading `account.network` by account/last4.
- [ ] Tests: extend the utilization/cards API test to set + read network (e.g. `PUT /cards/{id}/limit` with `{"network":"rupay"}` → cards response shows `network=="rupay"`). Run full suite → 387+ green.
- [ ] Commit: `feat(backend): per-card network override (Account.network) + expose on card DTOs`

## Task 2: Coil + BankLogo
**Files:** `android/app/build.gradle.kts`, `android/gradle/libs.versions.toml`, new `ui/designsystem/BankLogo.kt`.
- [ ] Add Coil: `libs.versions.toml` `coil = "2.6.0"` + `coil-compose = { group = "io.coil-kt", name = "coil-compose", version.ref = "coil" }`; `build.gradle.kts` `implementation(libs.coil.compose)`.
- [ ] `BankLogo.kt`:
  - `fun bankLogoUrl(bank: String): String?` — normalized bank → `https://logo.clearbit.com/<domain>`: AXIS→axisbank.com, IDFC→idfcfirstbank.com, HDFC→hdfcbank.com, ICICI→icicibank.com, SBI→sbi.co.in, KOTAK→kotak.com, BOB→bankofbaroda.in, AMEX→americanexpress.com, AU→aubank.in, YES→yesbank.in, RBL→rblbank.com, INDUSIND→indusind.com, HSBC→hsbc.co.in, CITI→citibank.com; unknown→null.
  - `@Composable fun BankLogo(bank, modifier)` — white rounded chip; Coil `AsyncImage` of the URL with `error`/`fallback` = bold dark bank-name text; null url → name text. `ContentScale.Fit`, ~30dp tall. (Coil disk-caches by default → offline reuse.)
- [ ] Compile gate → BUILD_OK. Commit: `feat(android): Coil + BankLogo (clearbit logo with text fallback)`

## Task 3: Realistic CreditCardArt + network mark + edit dialog + call sites
**Files:** `ui/designsystem/CreditCardArt.kt` (rewrite), `ui/bills/BillsScreen.kt`, `ui/rewards/RewardsScreen.kt`, `data/api/ExpenseApi.kt` (DTOs + payload), `domain/usecase/UtilizationUseCases.kt` (or wherever `SetCardLimitUseCase` is).
- [ ] DTOs/payload (`ExpenseApi.kt`): add `network: String? = null` to `CardStateDto` and `RewardCardDto`; change `SetLimitPayload` to include `network` (`SetLimitPayload(creditLimit: Double? = null, network: String? = null)` with `@Json` names) — or add a parallel field; keep the `@PUT("cards/{id}/limit")` call.
- [ ] Use case: extend `SetCardLimitUseCase` (or add `SetCardNetworkUseCase`) so the dialog can set limit and/or network via the same endpoint.
- [ ] Rewrite `CreditCardArt`:
  ```kotlin
  @Composable fun CreditCardArt(bank: String, last4: String?, modifier: Modifier = Modifier,
      holderName: String? = null, network: String? = null, height: Dp = 200.dp)
  ```
  Brand gradient (reuse `bankGradient`) + sheen overlay; top = `BankLogo(bank)` + contactless glyph; EMV chip (gold rounded Box) + contactless; embossed `•••• •••• •••• <last4>`; bottom = holder name block (hidden if null) + `NetworkMark(network)` (hidden if null). NO utilization on the face. Add private `NetworkMark`: visa→italic bold "VISA"; rupay→"Ru"+green "Pay"; mastercard→two overlapping circles (Canvas red #EB001B / amber #F79E1B); else nothing.
- [ ] `BillsScreen.kt` `BillCardArt`: render the new `CreditCardArt(bank, last4, holderName=<profile name or null>, network = util?.let{null} ?: c.network /* use CardStateDto.network */, height=200.dp)` as the face; BELOW it the utilization bar (pct + "₹X due"/"no dues") + the "Adjust limit ›"/"Set limit" affordance (moved off the face). Preserve scroll-anchor highlight/onClick/onSetLimit. **Edit dialog:** replace `SetLimitDialog` with a "Card details" dialog = limit number field + a **network dropdown** (Visa/Mastercard/RuPay/None); on save call the use case with limit and/or network, then refresh. Pre-fill current values.
- [ ] `RewardsScreen.kt`: update `CreditCardArt(...)` calls (Main + Cover) to new signature; pass `network = card.network`, `holderName` if available else null. Keep points/value caption below.
- [ ] Compile gate → BUILD_OK. Commit: `feat(android): realistic CreditCardArt + per-card network picker; utilization below card`

## Task 4: Build, install, verify on device + set AXIS ••6978 = RuPay
- [ ] Reconnect device (`adb mdns services` / ask user for wireless-debugging IP:port), `adb install -r`, open Bills.
- [ ] Set **AXIS ••6978 → RuPay** via the new network picker (user-confirmed value); verify the RuPay mark renders, bank logo loads (or name fallback), chip + embossed number look right, utilization sits below the card. Screenshot as evidence. Repeat for other cards if the user provides their networks.
- [ ] (No commit unless fixes needed.)

## Acceptance
- Backend suite green (387+); emu.db migrated (backed up). Android `assembleDebug` clean.
- Bills + Rewards show realistic card faces with the bank logo (Clearbit, cached; name fallback) + chip + embossed number; the **network mark reflects the per-card setting** (AXIS ••6978 = RuPay); utilization is below the face; no fake expiry; no early-returns. On-device screenshot confirms.
