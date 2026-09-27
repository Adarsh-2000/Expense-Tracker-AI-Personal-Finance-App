# Expense Tracker — Design Spec

**Date:** 2026-06-02
**Status:** Approved (design), pending implementation plan
**Owner:** Harsh Khandelwal

## 1. Purpose

A personal, single-user expense tracker that builds one clean ledger of all
spending by reading bank and credit-card transaction alerts the user already
receives — from **both email and SMS** — without handing financial data to any
third-party aggregator.

Region: **India**, currency **INR**. The user's actual institutions:
- **Credit cards:** Axis Bank, IDFC First Bank
- **Savings accounts:** Axis Bank, Bank of Baroda

The defining constraint: the same transaction frequently arrives via *both*
channels, so the system must **deduplicate** rather than double-count.

## 2. Scope

### In scope (v1)
- Ingest transaction alerts from Gmail (email) and Android SMS.
- Parse alerts into structured transactions via per-bank parsers.
- Cross-channel deduplication (email + SMS for the same purchase = one row).
- Auto-categorization (rules / merchant map first; ML later).
- **Zero-based budgeting** (YNAB-style): every rupee assigned to a category each
  month; "Ready to assign" = income − assignments; per-category alerts on
  approach/exceed.
- Dashboards & reports (trends, per-account, per-category, monthly summaries).
- Recurring / subscription detection.
- A "review queue" for messages that fail to parse.
- **Manual & cash entry** — add transactions by hand (cash account, or anything
  not in SMS/email).
- **Edit / recategorize / split** — correct any transaction; split one
  transaction across multiple categories.
- **Search & filter** transactions (by text, date, account, category, amount).
- **Account balances & net worth** — capture "Avl Bal" from parsed alerts;
  per-account running balance + balance snapshots over time → net-worth trend.
- **CSV export** of all transactions.
- **App lock** (PIN / biometric) + visible trust cues; "data stays on your own
  server" framing.

*(Scope additions above are driven by the industry research in
[research-features-and-uiux](2026-06-02-research-features-and-uiux.md).)*

### Out of scope (v1) — YAGNI
- Account Aggregator (Finvu/Setu) integration. This is the **planned fallback**
  only if email + SMS parsing proves to miss too many transactions.
- Multi-user / multi-tenant support.
- Multi-currency (INR only for v1).
- ML-based categorization (rules first; ML is a later enhancement).
- Investment / portfolio tracking; savings-goals; receipt OCR; AI assistant;
  shared/family spend; UPI-app notification parsing (all noted as future
  differentiators in the research doc).
- iOS app (SMS reading is forbidden on iOS).

## 3. Approach

**Stack (Approach A):**
- **Backend:** Python + FastAPI, async I/O throughout. Repository + entity
  patterns (per project coding standards).
- **Database:** PostgreSQL.
- **Web:** React dashboard.
- **Mobile:** Native Android (Kotlin) — captures SMS and forwards to the
  backend; also a viewing client.

**Deployment:** Local-first / self-hosted, single user. Financial data never
leaves the user's own infrastructure — consistent with the decision to avoid
aggregators for privacy.

## 4. Architecture

```
 Gmail API ─┐
            ├─► [ FastAPI Backend ] ─► PostgreSQL
 Android ───┘        │  (ingest → parse → dedup → enrich → serve)
 (SMS fwd)           │
                     ├─► React Web Dashboard
                     └─► Android app (capture SMS + view)
```

The backend is the only component that touches the database. Both clients
communicate with it over a REST API.

### Pipeline
1. **Ingest** — raw messages from both channels are stored *unparsed* first.
2. **Parse** — per-bank parsers convert raw messages to `Transaction` entities.
3. **Dedup** — fuzzy, time-windowed matching collapses cross-channel duplicates.
4. **Enrich** — categorize, detect recurring, evaluate budgets.
5. **Serve** — REST API for web + mobile clients.

## 5. Backend components

All high-I/O paths (Gmail polling, SMS webhook, DB access) use **async/await**.
Database access is confined to **repository** classes; domain data is modeled as
**entity** classes (project coding standards).

- **Ingestion layer**
  - `GmailPoller` — async OAuth client that polls/watches the inbox.
  - `SmsWebhook` — endpoint the Android app POSTs SMS to.
  - Both write to a `raw_messages` table **before** parsing, so no data is lost
    to a parser bug and history can be re-parsed at any time.
- **Parser layer**
  - `BankParserRegistry` of per-bank parsers (Axis, IDFC First, Bank of
    Baroda; extensible to more).
  - Each parser is one focused, independently testable unit producing a
    `Transaction` entity: amount, account_last4, direction (debit/credit),
    merchant, timestamp, channel, raw_ref.
  - Unknown / unmatched formats are flagged for review, never dropped.
- **Dedup engine** — computes a fingerprint and matches incoming transactions
  against recent ones within a time window (see §7).
- **Enrichment**
  - `Categorizer` — rules + merchant map (ML later).
  - `RecurringDetector` — identifies recurring charges / subscriptions / EMIs.
  - `ZeroBasedBudgetService` — per-month category assignments, spent-vs-assigned,
    and "Ready to assign" (income − Σ assignments); raises approach/exceed alerts.
  - `BalanceService` — maintains `Account.current_balance` from parsed "Avl Bal",
    writes `BalanceSnapshot`s, computes net worth.
- **API layer** — REST endpoints for transactions (incl. manual create, edit,
  split), accounts & balances, net worth, categories, budget assignments &
  "ready to assign", reports/summaries, search, CSV export, auth/app-lock, and
  the review queue.
- **Repositories** — `TransactionRepository`, `TransactionSplitRepository`,
  `AccountRepository`, `BalanceSnapshotRepository`, `CategoryRepository`,
  `BudgetAssignmentRepository`, `MonthlyIncomeRepository`, `RawMessageRepository`,
  `RecurringSeriesRepository` — the only code that touches the DB.

## 6. Data model (core entities)

- **RawMessage** — `id, channel (email|sms), sender, body, received_at,
  parsed_status (pending|parsed|needs_review), transaction_id?`
- **Account** — `id, bank, type (card|bank|cash), last4, currency (INR),
  current_balance?`
- **Transaction** — `id, amount, direction (debit|credit), merchant,
  category_id, occurred_at, account_id, fingerprint, entry_type (auto|manual),
  note?, balance_after?`
- **TransactionSplit** — `id, transaction_id, category_id, amount` — when present,
  the transaction's amount is divided across these splits; category breakdowns
  use splits instead of the parent `category_id`.
- **Category** — `id, name, parent_id?`
- **BalanceSnapshot** — `id, account_id, balance, captured_at` — time series for
  net-worth trend; appended whenever an alert carries an "Avl Bal".
- **BudgetAssignment** (zero-based) — `id, category_id, month (YYYY-MM),
  assigned_amount` — how much is given to each category for a given month.
- **MonthlyIncome** — `id, month (YYYY-MM), amount` — income available to assign
  (sum of credits or user-set); "Ready to assign" = income − Σ assignments.
- **RecurringSeries** — `id, merchant, account_id, cadence, expected_amount,
  last_seen_at, next_expected_at`

A `Transaction` may be linked to **multiple** `RawMessage` sources (one email +
one SMS for the same purchase). A manual/cash transaction has `entry_type=manual`
and **no** `RawMessage` source. Net worth = Σ `Account.current_balance`.

## 7. Deduplication logic (critical)

**Fingerprint** = `account_last4 + amount + direction + normalized_merchant`.

**On insert of a parsed transaction:**
1. Search for an existing transaction with the same fingerprint whose
   `occurred_at` is within a configurable window (**default 36h** — email and
   SMS for the same purchase rarely arrive at the same second).
2. **Match found** → attach the new message as an additional source of the
   existing transaction (no new row).
3. **No match** → create a new transaction.

**Edge case — genuine identical purchases** (e.g. two ₹100 coffees the same
day): distinguished by checking whether the matched transaction already has a
source from *this same channel*. If an SMS-sourced transaction is matched by a
*new SMS*, that is treated as a real second purchase (new row), not a duplicate.
Cross-channel matches (SMS↔email) collapse; same-channel repeats do not.

Window size and merchant-normalization rules are configurable.

## 8. Error handling

- Unparseable messages → `parsed_status = needs_review`, surfaced in a web
  **review queue**; never silently dropped.
- Gmail and SMS ingestion are **idempotent** — re-running never creates
  duplicate raw messages or transactions (dedup on message id / source id).
- Parser failures are isolated per message; one bad message never aborts a
  batch.

## 9. Testing strategy

- **TDD** for parsers and dedup — these are where regressions hide.
- Each bank parser has a unit-test suite built from real, **redacted** sample
  messages.
- The dedup engine has a dedicated suite covering: email/SMS overlap collapse,
  the identical-twin-purchase edge case, and time-window boundaries.
- Ingestion idempotency is tested (re-ingest → no new rows).
- Repository/API integration tests against a test Postgres instance.

## 9a. UI/UX & visual design

Driven by the research synthesis. Chosen direction:

- **Aesthetic:** premium **dark mode first**, vibrant teal/gradient accents, bold
  **tabular numeric** typography, generous whitespace, large rounded "glowing"
  cards. Clean sans (Inter/SF). First-class dark theme; light theme later.
- **Dashboard hero:** **"Spent this month"** (vs last month) as the single large
  hero number. Secondary prominent metric: **"Ready to assign"** (zero-based).
- **Dashboard composition (top → bottom):** hero spent → ready-to-assign →
  budget envelopes (progress bars) → category donut + ranked list → daily-spend
  **calendar heatmap** → recent transactions → upcoming recurring → neutral
  insights.
- **Charts:** donut + ranked bars (category), area/line (monthly trend), calendar
  heatmap (daily), horizontal progress bars (budget envelopes), in-vs-out bars
  (cash flow).
- **Onboarding:** minimal, progress-indicated — connect Gmail (OAuth) → grant SMS
  on Android → set income / assign budgets. Defer everything optional.
- **Microinteractions:** animated number count-ups, smooth chart transitions,
  pull-to-refresh sync, skeleton loaders, subtle haptics on mobile.
- **Trust & tone:** app lock (PIN/biometric), "your data stays on your own
  server" microcopy; insights phrased **neutrally and empoweringly, never
  shaming**.
- **Implementation:** use the `frontend-design` skill when building the React
  dashboard to avoid generic AI aesthetics.

## 10. Build order (high level)

1. Backend skeleton: DB schema, entities, repositories, `raw_messages` storage.
2. SMS ingestion via Android (or an off-the-shelf forwarder first to validate)
   + Gmail ingestion.
3. Per-bank parsers (start with the user's primary banks) — TDD.
4. Dedup engine — TDD.
5. Enrichment: categorizer, recurring detector, zero-based budget service,
   balance/net-worth service.
6. Manual/cash entry, edit/recategorize/split, search/filter, CSV export.
7. REST API + React web dashboard (premium dark + vibrant; `frontend-design`).
8. App lock (PIN/biometric) + trust cues.
9. Native Kotlin Android app (SMS capture + viewing).

## 11. Open decisions deferred to implementation

- Initial parser set is fixed to the user's banks: Axis Bank (card +
  savings), IDFC First Bank (card), Bank of Baroda (savings). Parser regexes
  must be calibrated against real redacted alert samples from these banks.
- Whether to start SMS capture with an off-the-shelf forwarder before building
  the Kotlin app.
- Gmail access mode: polling vs. push (Pub/Sub watch).
- Hosting specifics (local machine vs. small self-hosted server).
