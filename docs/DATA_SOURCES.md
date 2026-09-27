# How Ledger Noir Reads Your Financial Data

This document explains, end to end, **how this app actually gets your transaction
data today** (SMS + email parsing), **how the Google connection is wired**, and
**what the real industry options are** for pulling bank / card / portfolio data —
including what apps like CRED, Fi, and Jupiter actually do and why we can't simply
do the same.

---

## 1. The two data sources we use today

The app is **read-only and parse-based**. It never logs into your bank. It reads
the *notifications your bank already sends you* — transaction SMS and transaction
emails — and extracts structured data from them. Nothing else.

```
   Bank sends you            We read it from              We parse it into
   a notification     →      your phone / inbox      →    a Transaction row
   (SMS / email)             (with your permission)       (amount, merchant, …)
```

### A. SMS (on the phone)

1. **Permission**: on first launch the app requests Android's `READ_SMS`
   permission. You grant it explicitly; it can be revoked any time in Settings.
2. **Filtering** (`android/.../sms/SmsFilter.kt`): we only ever read messages from
   **whitelisted bank sender IDs** — `AXISBK`, `AXISBN`, `IDFCFB`, `IDFCBK`,
   `BOBTXN`, `BOBSMS`, `BOBIBN`. Indian DLT sender headers come as
   `AX-IDFCFB-S` (operator–entity–category); we normalize to the entity
   (`IDFCFB`) and exact-match the whitelist. Personal SMS, OTPs, and everything
   else are ignored and never leave the device.
3. **Upload**: each matching SMS is POSTed to your backend at `POST /ingest/sms`
   with a stable `external_id` (`sms-<id>`) so re-imports are idempotent.
4. **Sync cadence**: runs once automatically per app launch, plus the manual
   **"Sync Messages"** button on Home.

### B. Email (Gmail API)

1. **What we query** (`app/main.py` `GMAIL_QUERY`): only mail from bank domains —
   `axisbank.com`, `axis.bank.in`, `alerts@axis.bank.in`, `idfcfirstbank.com`,
   `idfcbank.com`, `bankofbaroda.*` (and statement senders like
   `cc.statements@axis.bank.in`, `statements@…indmoney.com`, `zerodha`).
   `newer_than:5y`, incremental after the last sync timestamp.
2. **Scope**: `https://www.googleapis.com/auth/gmail.readonly` — **read-only**.
   The app cannot send, delete, or modify any email.
3. **Attachments**: statement PDFs (Zerodha holdings, INDmoney) are downloaded
   and parsed in memory; the **PDF password is your PAN**, used only locally to
   decrypt and never logged or committed.
4. **Sync cadence**: auto on launch + the manual **"Sync Email"** button on Home.

### C. What a parsed transaction contains

Per message we extract: **amount, currency, direction (debit/credit), merchant
(cleaned), date & time, account/card last-4, txn reference, balance-after** (when
present). Per-bank parsers live in `app/parsing/banks/{axis,idfc,bob}.py` with
shared helpers in `_util.py`. We then **categorize** (food, groceries, bills,
bill payments, investments, transfers…), **sub-categorize**, derive a **payment
rail** (credit card / UPI / debit / NEFT), and **de-duplicate** when the same
transaction arrives by both SMS and email.

---

## 2. How the Google connection is set up

We use **Google Cloud OAuth** for two distinct things — don't confuse them:

### (a) "Sign in with Google" (who you are)
- A **Google Cloud project** with an **OAuth client of type _Android_** (package
  `com.ledgernoir.tracker` + the app's SHA-1 signing fingerprint) and a **Web
  client** (its ID is what the server verifies tokens against).
- The phone uses Android **Credential Manager** (`GetSignInWithGoogleOption`) to
  get a Google **ID token**. The server (`POST /auth/verify-google`) verifies the
  token's signature, audience, nonce (one-time, anti-replay), and that the email
  matches the single allowed user — then issues a 30-day session token.

### (b) Gmail read access (your transaction emails)
- The **Gmail API** is enabled in the same Cloud project, with an OAuth consent
  screen and the `gmail.readonly` scope.
- A one-time browser consent (`scripts/gmail_auth.py`) produces **`token.json`**
  (an OAuth refresh token) stored on the backend host. `client_secret.json` and
  `token.json` are **gitignored** — never committed.
- The backend refreshes the access token automatically and polls Gmail
  incrementally. If `token.json` is absent, sync degrades gracefully (returns 0).

**Key point:** all of this runs on **your own server + your own Google project**.
Your data never goes to a third party — "verified by your own server."

---

## 3. The real ways to fetch bank / card / portfolio data (industry view)

People assume apps "log into your bank." Almost none do. Here are the **actual**
mechanisms, roughly from least to most privileged:

| Method | How it works | Who can use it | Can *we* use it? |
|---|---|---|---|
| **SMS / email parsing** | Read the alerts the bank already sends; parse them. | Anyone (with user consent on their own data). | ✅ **This is what we do.** |
| **Account Aggregator (AA)** | RBI's consent framework. A licensed AA (Finvu, Onemoney, CAMS) brokers read-only, consented access to bank/financial data via FIPs. | Requires being/partnering with an **RBI-regulated FIU**. | ❌ Needs licensing & integration. |
| **Direct bank / card-network APIs** | Banks expose private partner APIs; card networks (Visa/Mastercard) have data programs. | Licensed fintechs with commercial agreements. | ❌ Not available to a personal app. |
| **UPI ecosystem (NPCI/TPAP)** | A UPI app (PhonePe/GPay/CRED) sees the UPI txns *it* processes, as a Third-Party App Provider via a sponsor bank. | Requires NPCI/TPAP + sponsor-bank approval. | ❌ Only for txns routed through that app. |
| **Aggregator/scraping vendors** | Services like Plaid (US), or net-banking screen-scraping. | Vendor partnerships; scraping is fragile & often ToS-violating. | ⚠️ Not in India meaningfully; avoid. |
| **Statement/PDF import** | Parse account/CC/holding statements (often password-protected). | Anyone with the statements. | ✅ We do this for **portfolios** (Zerodha/INDmoney PDFs). |

### What CRED / Fi / Jupiter actually do
- **CRED**: registered as a **UPI TPAP** (sees UPI txns it processes) **and** reads
  **credit-card statement emails/SMS** to detect bills (exactly our bill mechanism)
  — that's why CRED is "credit cards first." For richer data it uses
  **Account Aggregator** consent.
- **Fi / Jupiter**: **neobanks** partnered with a sponsor bank (Federal Bank), so
  for *their own* account they have first-party data; for *external* accounts they
  use **Account Aggregator**.
- None of them magically "pull all your cards from your phone number." That
  capability requires being a regulated entity inside one of the frameworks above.

### Why you can't fetch transactions from just a card number / phone number
- **A card number + expiry + CVV is a _payment credential_, not a data key.** It
  lets you *charge* a card; it gives **no read access** to the card's transaction
  history. There is no API where "card number → list of transactions."
- Storing CVV is **prohibited by PCI-DSS** and pointless here — we never store it.
- A **phone number** alone unlocks nothing; it's just an identifier. The only
  phone-based data is the **SMS alerts on that phone**, which is precisely the
  source we already use (with your on-device permission).

### Where this app already gives "CRED-grade" results without the licenses
- **Credit cards**: per-card **unbilled → billed** tracking from statement
  SMS/email (the same signal CRED uses), shown on the Bills tab.
- **Spending across rails**: credit card vs UPI vs debit vs NEFT, from the alerts.
- **Portfolio**: holdings parsed from Zerodha/INDmoney **statement PDFs** (the one
  read-access channel available to an individual), decrypted locally with your PAN.

### If we ever wanted true live balances / full history
The only legitimate path is **Account Aggregator**: integrate with a licensed AA
(Finvu/Onemoney) as/through a regulated FIU. That gives RBI-consented, read-only,
real-time access to bank balances and transactions — without scraping or storing
credentials. It's a licensing + compliance project, not a code change.

---

## 4. Privacy summary

- **Read-only** everywhere (SMS read permission; Gmail `readonly`; statement PDFs).
- Runs on **your own server + your own Google Cloud project**; single allowed user.
- **No credentials stored**: no bank passwords, no CVV. PAN is used only to
  *decrypt statement PDFs locally* and is stored like any other secret.
- Secrets (`client_secret.json`, `token.json`, the DB) are **gitignored**.
- Sessions are opaque 30-day tokens; a 401 auto-logs-out and clears the token.
