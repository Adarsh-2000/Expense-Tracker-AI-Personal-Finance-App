from __future__ import annotations

import base64
import html
import re
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

_IST = ZoneInfo("Asia/Kolkata")

_SCRIPT_STYLE_RE = re.compile(r"<(script|style)\b[^>]*>.*?</\1>", re.IGNORECASE | re.DOTALL)
_TAG_RE = re.compile(r"<[^>]+>")
_WS_RE = re.compile(r"\s+")


def _decode(data: str) -> str:
    return base64.urlsafe_b64decode(data.encode()).decode(errors="replace")


def _html_to_text(raw: str) -> str:
    """Flatten an HTML email body to plain text so the SMS-style parsers have a
    chance: drop <script>/<style> blocks and tags, unescape entities, collapse
    whitespace."""
    text = _SCRIPT_STYLE_RE.sub(" ", raw)
    text = _TAG_RE.sub(" ", text)
    text = html.unescape(text)
    return _WS_RE.sub(" ", text).strip()


def _extract_body(payload: dict) -> str:
    if payload.get("body", {}).get("data"):
        body = _decode(payload["body"]["data"])
        if "<" in body:
            return _html_to_text(body)
        return body
    for part in payload.get("parts", []):
        text = _extract_body(part)
        if text:
            return text
    return ""


def gmail_message_to_fields(msg: dict) -> dict:
    headers = {h["name"].lower(): h["value"] for h in msg["payload"].get("headers", [])}
    # internalDate is a UTC ms epoch. Convert to Asia/Kolkata local wall-clock and
    # drop tzinfo so email occurred_at matches the SMS local-time convention; this
    # removes the ~5.5h skew that defeated cross-channel dedup.
    received = (
        datetime.fromtimestamp(int(msg["internalDate"]) / 1000, tz=timezone.utc)
        .astimezone(_IST)
        .replace(tzinfo=None)
    )
    return {
        "external_id": f"gmail-{msg['id']}" if not msg["id"].startswith("gmail-") else msg["id"],
        "sender": headers.get("from", "unknown"),
        "body": _extract_body(msg["payload"]),
        "received_at": received,
    }
