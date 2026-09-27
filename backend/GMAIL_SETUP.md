# Gmail Email Ingestion Setup

The expense tracker can import bank transaction emails from your Gmail account
using **read-only** access. Setup is a one-time process. Most of it is a manual
Google Cloud Console flow that only you can do; the backend handles everything
after `token.json` exists.

## What you must do (manual, one-time)

1. **Create a Google Cloud project**
   - Go to https://console.cloud.google.com and create a new project (or pick an
     existing one).

2. **Enable the Gmail API**
   - In the project, open **APIs & Services -> Library**, search for
     **"Gmail API"**, and click **Enable**.

3. **Configure the OAuth consent screen**
   - Go to **APIs & Services -> OAuth consent screen**.
   - Choose **External** user type.
   - Fill in the required app name / support email fields.
   - Under **Test users**, add **your own Gmail address** (the account whose
     bank emails you want to import). While the app is in "Testing", only test
     users can authorize it — that's fine for personal use.

4. **Create OAuth client credentials**
   - Go to **APIs & Services -> Credentials -> Create credentials ->
     OAuth client ID**.
   - Application type: **Desktop app**.
   - Download the JSON file and save it as **`backend/client_secret.json`**.

5. **Run the one-time authorization script**
   ```
   cd backend && .venv/bin/python scripts/gmail_auth.py
   ```
   - A browser window opens asking for consent to read your Gmail.
   - After you approve, the script writes **`backend/token.json`**.
   - You only need to do this once; the backend refreshes the token
     automatically afterward.

## What the backend does (automated)

- On `POST /sync/gmail` it loads `token.json`, refreshes it if expired (using
  the saved refresh token), and queries Gmail **read-only** for recent emails
  from your banks (Axis, IDFC First, Bank of Baroda) within the last 2 years.
- Each email is run through the existing parse + dedup pipeline. Successfully
  parsed emails become transactions; anything unparseable is routed to the
  **review queue** (safe — nothing is dropped).

## Triggering an import

- Once `token.json` exists, trigger an import with:
  ```
  POST /sync/gmail
  ```
  (or use the app's Gmail sync action).
- Response: `{"ingested": N, "fetched": M}`.
- If you haven't authorized yet, you'll instead get a helpful (HTTP 200)
  response: `{"ingested": 0, "fetched": 0, "detail": "Gmail not authorized; run scripts/gmail_auth.py"}`.

## Notes & caveats

- **Read-only scope** (`gmail.readonly`): the app can only read emails, never
  send, modify, or delete.
- **Only bank emails are queried** — the Gmail query filters to your banks'
  domains, so unrelated mail is never fetched.
- **Data stays on your server.** `client_secret.json` and `token.json` are
  local files (git-ignored) and never leave your machine.
- **Email formats differ from SMS.** The parsers were calibrated for bank SMS;
  email layouts vary, so some transactions may land in the review queue first
  and parsing rules may need calibration over time.
