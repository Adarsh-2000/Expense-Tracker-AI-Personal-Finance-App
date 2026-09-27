from __future__ import annotations

GMAIL_SCOPES = ["https://www.googleapis.com/auth/gmail.readonly"]
GMAIL_QUERY = (
    "from:(axisbank.com OR axis.bank.in OR alerts@axis.bank.in "
    "OR idfcfirstbank.com OR idfcbank.com "
    "OR bankofbaroda.co.in OR bankofbaroda.com OR bankofbaroda.in) "
    "newer_than:5y"
)


def fetch_gmail_fields() -> list[dict]:
    """Real impl builds the Gmail client from token.json and polls. Overridden in tests.

    Returns [] gracefully when token.json is missing so /sync/gmail can respond
    with a helpful message instead of crashing. All google imports stay inside
    this function so the test suite never needs network or real credentials.
    """
    import logging
    import os

    token = "token.json"
    if not os.path.exists(token):
        logging.getLogger(__name__).warning("Gmail token.json not found; run scripts/gmail_auth.py to authorize.")
        return []

    from google.auth.transport.requests import Request
    from google.oauth2.credentials import Credentials
    from googleapiclient.discovery import build

    from app.connectors.gmail_poller import poll_messages
    from app.connectors.gmail_state import (
        after_query_suffix, max_internal_date, read_last_sync, write_last_sync,
    )

    creds = Credentials.from_authorized_user_file(token, GMAIL_SCOPES)
    if creds and creds.expired and creds.refresh_token:
        creds.refresh(Request())
        with open(token, "w") as f:
            f.write(creds.to_json())

    service = build("gmail", "v1", credentials=creds)

    # INCREMENTAL: only pull mail newer than the last successful sync.
    last_ms = read_last_sync()
    query = GMAIL_QUERY + after_query_suffix(last_ms)
    fields = poll_messages(service, query=query, max_results=2000)

    newest = max_internal_date(fields)
    if newest > last_ms:
        write_last_sync(newest)
    return fields


def fetch_latest_holding_pdf_bytes() -> bytes | None:
    """Build the Gmail client from token.json and download the latest Zerodha
    monthly holding-statement PDF. Returns None when not authorized or no
    statement exists. All google imports stay local so importing this module needs
    no network/credentials (overridden in tests)."""
    import os

    token = "token.json"
    if not os.path.exists(token):
        return None
    try:
        from google.auth.transport.requests import Request
        from google.oauth2.credentials import Credentials
        from googleapiclient.discovery import build

        from app.connectors.gmail_poller import fetch_latest_holding_pdf

        creds = Credentials.from_authorized_user_file(token, GMAIL_SCOPES)
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
            with open(token, "w") as f:
                f.write(creds.to_json())
        service = build("gmail", "v1", credentials=creds)
        return fetch_latest_holding_pdf(service)
    except Exception:
        import logging
        logging.getLogger(__name__).exception("failed to fetch holding statement PDF")
        return None


def fetch_latest_statement_pdf_bytes(query: str) -> bytes | None:
    """Build the Gmail client from token.json and download the latest PDF attachment
    matching `query` (e.g. a credit-card statement). Returns None when not authorized
    or no such email exists. Mirrors fetch_latest_holding_pdf_bytes."""
    import os

    token = "token.json"
    if not os.path.exists(token):
        return None
    try:
        from google.auth.transport.requests import Request
        from google.oauth2.credentials import Credentials
        from googleapiclient.discovery import build

        from app.connectors.gmail_poller import fetch_latest_holding_pdf

        creds = Credentials.from_authorized_user_file(token, GMAIL_SCOPES)
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
            with open(token, "w") as f:
                f.write(creds.to_json())
        service = build("gmail", "v1", credentials=creds)
        return fetch_latest_holding_pdf(service, query=query)
    except Exception:
        import logging
        logging.getLogger(__name__).exception("failed to fetch statement PDF")
        return None


def fetch_latest_us_analytics_data() -> tuple[str, str] | None:
    """Build the Gmail client and fetch the latest INDmoney US-stocks Analytics
    Report (html, as_of). Returns None when unauthorized or none found."""
    import os

    token = "token.json"
    if not os.path.exists(token):
        return None
    try:
        from google.auth.transport.requests import Request
        from google.oauth2.credentials import Credentials
        from googleapiclient.discovery import build

        from app.connectors.gmail_poller import fetch_latest_us_analytics

        creds = Credentials.from_authorized_user_file(token, GMAIL_SCOPES)
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
            with open(token, "w") as f:
                f.write(creds.to_json())
        service = build("gmail", "v1", credentials=creds)
        return fetch_latest_us_analytics(service)
    except Exception:
        import logging
        logging.getLogger(__name__).exception("failed to fetch US analytics email")
        return None


def strip_html(html: str) -> str:
    import re
    t = re.sub(r"(?is)<(script|style).*?</\1>", " ", html)
    t = re.sub(r"(?is)<[^>]+>", " ", t)
    t = re.sub(r"&nbsp;|&[a-z]+;", " ", t)
    return re.sub(r"\s{2,}", " ", t).strip()
