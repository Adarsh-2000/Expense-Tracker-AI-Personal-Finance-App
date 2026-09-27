"""One-time Gmail authorization. Run: python scripts/gmail_auth.py
Requires client_secret.json (downloaded from Google Cloud Console, OAuth client type 'Desktop app') in the backend/ dir.
Produces token.json used by the backend to read bank transaction emails (read-only)."""
from __future__ import annotations
import os
from google_auth_oauthlib.flow import InstalledAppFlow

SCOPES = ["https://www.googleapis.com/auth/gmail.readonly"]

def main():
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # backend/
    secret = os.path.join(here, "client_secret.json")
    token = os.path.join(here, "token.json")
    if not os.path.exists(secret):
        raise SystemExit("Missing client_secret.json in backend/. Download it from Google Cloud Console (OAuth client, Desktop app) and place it here.")
    flow = InstalledAppFlow.from_client_secrets_file(secret, SCOPES)
    creds = flow.run_local_server(port=0)  # opens browser for consent
    with open(token, "w") as f:
        f.write(creds.to_json())
    print(f"Saved {token}. You can now POST /sync/gmail to import bank emails.")

if __name__ == "__main__":
    main()
