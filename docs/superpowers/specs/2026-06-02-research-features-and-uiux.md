# Expense Tracker — Industry Research: Features & UI/UX

**Date:** 2026-06-02
**Method:** Deep-research workflow — 5 search angles, 25 sources fetched, 121 claims
extracted, 25 adversarially verified (3-vote, need 2/3 to refute), 18 confirmed,
7 refuted. India/INR market focus.

---

## A. Verified findings (high confidence)

1. **Common core feature set** across leading apps: categorization, automatic
   ingestion, spending summaries, bill reminders, net worth, investment
   dashboards, subscription tracking, savings goals. *(Common, not universal.)*
   — NerdWallet, Engadget, Moneyview.
2. **Budgeting methodology is a key differentiator**: zero-based (YNAB), envelope
   (Goodbudget), flexible (Monarch/Simplifi), 50/30/20 (NerdWallet),
   cash-after-bills "safe-to-spend" (PocketGuard). India adds ET Money, axio,
   offline expense managers. — vendor docs, Moneyview.
3. **India is a fragmented money landscape**: UPI, cards, bank SMS, wallets, EMIs,
   cash, subscriptions, family/shared spend. **SMS/notification parsing is the
   dominant ingestion method** (axio/Walnut, Money Lover, SMSOrganizer). Android
   can parse SMS + notifications; iOS relies on SMS-share, email, Apple Pay, PDF.
   — FinArt (PwC/PIB-corroborated), Google Play, Tracxn.
4. **RBI Account Aggregator (AA)** is the consent-based, data-blind "right" path,
   but data flow is **restricted to RBI/SEBI/IRDAI/PFRDA-regulated FIUs** (or via
   a TSP partner). Heavy for an individual — confirms AA as a *future fallback*,
   not the v1 path. — HyperVerge (vs GoI DFS/Sahamati/PIB).
5. **Fintech UX principles** (high confidence, multi-source):
   - Zero-friction onboarding (minimal steps, progress indicators, biometrics).
   - Low-cognitive-load dashboards: **one primary number**, color-coded cards,
     progressive disclosure, personalization.
   - **Visible trust cues**: lock/biometric, encryption microcopy, privacy
     framing.
   - **Insights should empower, not judge** — neutral framing, never guilt-based
     (corroborated by BAI + ScienceDirect financial-shame research).
   — ProCreator, Eleken, OneThing, designstudio.

## B. Refuted claims (do NOT design around these)

- ❌ "Google Play bans SMS/Call-Log access for finance apps" (0-3) and "enforcement
  began Oct 2023" (0-3). The blanket-ban framing did not survive verification.
  **Open question:** the *exact current* Play policy for `READ_SMS` — but moot for
  us: this app is **self-hosted + sideloaded, not distributed on Play**.
- ❌ "All leading budget apps require auto-sync via Plaid/Yodlee/Finicity" (0-3).
  Auto-sync is common but NOT a universal must-have → SMS/email parsing is a
  legitimate primary strategy.
- ❌ "Copilot Money is iOS/Mac-only" (0-3) — outdated.

## C. Feature matrix (vs. our current plan)

### Must-have (table stakes)
| Feature | Status in our plan |
|---|---|
| Multi-account aggregation (cards + bank) | ✅ have |
| Automatic ingestion (SMS + email parse) | ✅ have |
| **Cross-channel dedup** | ✅ have (our edge) |
| Auto-categorization | ✅ have |
| Spending dashboards & summaries | ✅ have |
| Budgets + alerts | ✅ have |
| Recurring / subscription detection | ✅ have |
| **Manual entry (cash!)** | ❌ MISSING |
| **Edit / recategorize / split transactions** | ❌ MISSING |
| **Search & filter** | ⚠️ partial |
| **Account balances / net worth** | ❌ MISSING (we store txns, not balances) |
| **Bill reminders / upcoming** | ⚠️ partial (recurring gives next-date) |
| **CSV / data export** | ❌ MISSING |
| **App lock (PIN / biometric) + trust cues** | ❌ MISSING |

### Differentiators (pick to stand out)
- Budgeting methodology choice (50/30/20, zero-based, safe-to-spend, envelope)
- **"Safe to spend" hero number** (PocketGuard-style)
- Cash-flow forecast; net-worth trend over time
- Savings goals; spending insights / anomaly detection ("dining +30% vs last mo")
- Merchant logo enrichment; tags & notes
- Natural-language / AI search & assistant
- Receipt capture (OCR); split / shared / family spend
- **UPI-app notification parsing** (captures GPay/PhonePe/Paytm beyond bank SMS)

## D. UI/UX & visual design direction

**Layout / dashboard**
- One **hero metric** at top (e.g. "Safe to spend ₹X" or "Spent this month ₹Y").
- Color-coded **category cards**; progressive disclosure (summary → tap for detail).
- Sections: hero → budget progress → category breakdown → recent transactions →
  upcoming/recurring → insights.

**Charts** (Syncfusion/Eliftech guidance)
- Category split: **donut** + ranked bar list.
- Trend: **area/line** by month; **calendar heatmap** of daily spend (Copilot-style).
- Budget: **horizontal progress bars** / radial gauges.
- Cash flow: in-vs-out **bars** or waterfall.

**Onboarding**
- Minimal: connect Gmail (OAuth) → grant SMS on Android → done, with a progress
  bar. Defer everything optional.

**Visual / brand**
- Large, bold **numeric typography** with tabular figures; clean sans (Inter/SF).
- Generous whitespace, large rounded cards, **first-class dark mode**.
- Calm, trustworthy base palette + one vibrant accent; semantic colors for
  debit/credit and budget status. (Patrick Huijs / Phoenix palettes.)

**Microinteractions**
- Animated number count-ups, smooth chart transitions, pull-to-refresh sync,
  subtle haptics on mobile, skeleton loaders.

**Trust & tone**
- Visible lock / biometric, "your data stays on your own server" microcopy.
- Insights phrased neutrally and empoweringly — never shaming.

## E. Implications for our spec/plan
- Add to v1 scope (must-haves we lacked): **manual/cash entry, transaction
  edit+recategorize+split, search/filter, account balances + net worth, CSV
  export, app lock + trust cues.**
- Choose a **budgeting methodology** and a **dashboard hero metric** (product
  decisions — see questions).
- Adopt the UI/UX direction above; use the `frontend-design` skill at build time
  for the React dashboard to avoid generic AI aesthetics.
- Keep AA (Finvu/Setu) as documented future fallback; SMS+email parsing stays the
  validated v1 path. Consider **UPI-app notification parsing** on Android as a
  high-value differentiator.

## Sources (selected)
- NerdWallet best budget apps; Engadget budgeting apps; Moneyview India PFM apps
- FinArt (India expense tracking); HyperVerge (RBI AA framework)
- ProCreator, Eleken (fintech UX best practices + trusted UI examples)
- Syncfusion (financial charts), Eliftech (finance dashboards), SCT (dashboards)
- Patrick Huijs / Phoenix Strategy (fintech color palettes); Shakuro; OneThing
- Copilot teardown (moneywithkatie); Era vs Monarch vs Copilot vs YNAB
