from __future__ import annotations

import os
import re

from app.auth import NonceStore, RateLimiter, SessionStore
from app.config import settings

PAN_RE = re.compile(r"^[A-Z]{5}[0-9]{4}[A-Z]$")

# File-backed so a backend restart doesn't sign the user out (30-day TTL).
_SESSIONS_PATH = os.environ.get("ET_SESSIONS_PATH", "sessions.json")
sessions = SessionStore(ttl_seconds=60 * 60 * 24 * 30, path=_SESSIONS_PATH)
unlock_limiter = RateLimiter()
nonces = NonceStore()

OPEN_PATHS = {"/auth/unlock", "/auth/config", "/auth/verify-google", "/auth/nonce"}


def verify_google_id_token(id_tok: str) -> dict:
    """Verify a Google ID token and return its verified claims dict. Raises on invalid.

    Returns the full claims so callers can read email, email_verified AND the
    `nonce` claim (used for anti-replay binding). Extracted for testability.
    """
    from google.auth.transport import requests as g_requests
    from google.oauth2 import id_token as g_id_token
    info = g_id_token.verify_oauth2_token(id_tok, g_requests.Request(), settings.google_web_client_id)
    return info
