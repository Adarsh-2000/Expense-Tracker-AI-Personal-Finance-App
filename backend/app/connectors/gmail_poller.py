from __future__ import annotations

from app.connectors.gmail import gmail_message_to_fields

_BATCH_SIZE = 50


def _fields_with_date(msg: dict) -> dict:
    """gmail_message_to_fields plus the raw internalDate (ms) for incremental state."""
    fields = gmail_message_to_fields(msg)
    try:
        fields["internal_date_ms"] = int(msg.get("internalDate", 0) or 0)
    except (TypeError, ValueError):
        fields["internal_date_ms"] = 0
    return fields


def poll_messages(
    gmail_service, *, query: str, max_results: int = 2000, page_size: int = 200
) -> list[dict]:
    # Page through users().messages().list following nextPageToken until we either
    # hit the cap or run out of pages, accumulating message refs across pages.
    refs: list[dict] = []
    token = None
    while len(refs) < max_results:
        list_kwargs = {"userId": "me", "q": query, "maxResults": page_size}
        if token is not None:
            list_kwargs["pageToken"] = token
        listing = gmail_service.users().messages().list(**list_kwargs).execute()
        refs.extend(listing.get("messages", []))
        token = listing.get("nextPageToken")
        if not token:
            break

    ids = [ref["id"] for ref in refs[:max_results]]

    if hasattr(gmail_service, "new_batch_http_request"):
        return _fetch_batched(gmail_service, ids)
    return _fetch_per_message(gmail_service, ids)


HOLDING_STATEMENT_QUERY = (
    'from:zerodha subject:"Monthly Demat Transaction with Holding" has:attachment'
)

AXIS_CARD_STATEMENT_QUERY = "from:cc.statements@axisbank.com has:attachment"
IDFC_CARD_STATEMENT_QUERY = "from:statement@idfcfirstbank.com has:attachment"


def _find_pdf_part(payload: dict) -> dict | None:
    """Walk a Gmail message payload tree for the first part whose filename ends
    in .pdf and which carries an attachmentId."""
    filename = payload.get("filename", "") or ""
    body = payload.get("body", {}) or {}
    if filename.lower().endswith(".pdf") and body.get("attachmentId"):
        return payload
    for part in payload.get("parts", []) or []:
        found = _find_pdf_part(part)
        if found is not None:
            return found
    return None


def fetch_latest_holding_pdf(gmail_service, *, query: str = HOLDING_STATEMENT_QUERY) -> bytes | None:
    """Return the bytes of the first PDF attachment on the most recent email
    matching `query`, or None if there is no such email/attachment.

    Gmail returns list results newest-first, so the first id is the latest
    statement. Decodes the base64url attachment body.
    """
    import base64

    listing = (
        gmail_service.users().messages()
        .list(userId="me", q=query, maxResults=1)
        .execute()
    )
    messages = listing.get("messages", [])
    if not messages:
        return None
    msg_id = messages[0]["id"]
    msg = (
        gmail_service.users().messages()
        .get(userId="me", id=msg_id, format="full")
        .execute()
    )
    part = _find_pdf_part(msg.get("payload", {}))
    if part is None:
        return None
    attachment_id = part["body"]["attachmentId"]
    att = (
        gmail_service.users().messages().attachments()
        .get(userId="me", messageId=msg_id, id=attachment_id)
        .execute()
    )
    data = att.get("data")
    if not data:
        return None
    return base64.urlsafe_b64decode(data.encode())


US_ANALYTICS_QUERY = 'from:indmoney subject:"US Stocks Analytics Report"'


def _collect_html(payload: dict) -> str:
    """Concatenate the decoded text/html bodies in a message payload tree."""
    import base64

    out: list[str] = []

    def walk(part: dict) -> None:
        body = part.get("body", {}) or {}
        if part.get("mimeType") == "text/html" and body.get("data"):
            out.append(base64.urlsafe_b64decode(body["data"].encode()).decode("utf-8", "ignore"))
        for sub in part.get("parts", []) or []:
            walk(sub)

    walk(payload)
    return "\n".join(out)


def fetch_latest_us_analytics(gmail_service, *, query: str = US_ANALYTICS_QUERY) -> tuple[str, str] | None:
    """Return (html_body, as_of_date_iso) of the most recent INDmoney US-stocks
    Analytics Report email, or None when there is none. as_of is the email's
    internal date (YYYY-MM-DD)."""
    from datetime import datetime, timezone

    listing = (
        gmail_service.users().messages()
        .list(userId="me", q=query, maxResults=1)
        .execute()
    )
    messages = listing.get("messages", [])
    if not messages:
        return None
    msg_id = messages[0]["id"]
    msg = (
        gmail_service.users().messages()
        .get(userId="me", id=msg_id, format="full")
        .execute()
    )
    html = _collect_html(msg.get("payload", {}))
    if not html:
        return None
    ms = msg.get("internalDate")
    as_of = (
        datetime.fromtimestamp(int(ms) / 1000, tz=timezone.utc).strftime("%Y-%m-%d")
        if ms else ""
    )
    return html, as_of


def _fetch_per_message(gmail_service, ids: list[str]) -> list[dict]:
    fields = []
    for msg_id in ids:
        msg = (
            gmail_service.users().messages()
            .get(userId="me", id=msg_id, format="full")
            .execute()
        )
        fields.append(_fields_with_date(msg))
    return fields


def _fetch_batched(gmail_service, ids: list[str]) -> list[dict]:
    # Fetch message bodies via the Gmail batch endpoint in chunks of 50 GETs each.
    fields: list[dict] = []
    for start in range(0, len(ids), _BATCH_SIZE):
        chunk = ids[start:start + _BATCH_SIZE]
        collected: list[dict] = []

        def _callback(request_id, response, exception, _sink=collected):
            if exception is not None or response is None:
                return
            _sink.append(_fields_with_date(response))

        batch = gmail_service.new_batch_http_request(callback=_callback)
        for msg_id in chunk:
            batch.add(
                gmail_service.users().messages().get(userId="me", id=msg_id, format="full")
            )
        batch.execute()
        fields.extend(collected)
    return fields
