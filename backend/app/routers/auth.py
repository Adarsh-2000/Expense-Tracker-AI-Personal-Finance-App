from __future__ import annotations

import time

from fastapi import APIRouter, HTTPException, Request

from app import security
from app.config import settings
from app.auth import verify_pin
from app.schemas import GoogleIdTokenIn, PinIn

router = APIRouter()


@router.post("/auth/unlock")
async def unlock(payload: PinIn, request: Request):
    now = time.time()
    client = request.client.host if request.client else "unknown"
    if not security.unlock_limiter.allowed(client, now):
        raise HTTPException(status_code=429, detail="too many attempts; try later")
    pin_hash = settings.app_pin_hash
    if not pin_hash or not verify_pin(payload.pin, settings.app_pin_salt, pin_hash):
        security.unlock_limiter.record_fail(client, now)
        raise HTTPException(status_code=401, detail="invalid pin")
    return {"token": security.sessions.issue(now)}


# NOTE: the former GET /auth/google (device-key → session token) endpoint was REMOVED.
# Minting a session from a pre-shared device header alone was a session-replay risk over
# the cleartext LAN. Sign-in now requires a Google ID token verified server-side (see
# /auth/verify-google), which is real proof of account possession bound to a one-time nonce.


@router.get("/auth/config")
async def auth_config():
    # The app reads the Web client ID from here so it isn't hardcoded in the APK.
    return {"google_web_client_id": settings.google_web_client_id}


@router.get("/auth/nonce")
async def auth_nonce():
    # Issue a one-time, short-TTL nonce. The app embeds it in the Google ID token
    # (via setNonce) so /auth/verify-google can bind the token to this fresh
    # challenge and reject replays of a previously captured token.
    return {"nonce": security.nonces.issue(time.time())}


@router.post("/auth/verify-google")
async def verify_google(payload: GoogleIdTokenIn):
    # Verify a Google ID token (from on-device Credential Manager). Issues a session
    # ONLY if the token is validly signed by Google, its audience == our web client id,
    # and (if configured) the email matches allowed_email. This is a real proof of
    # account possession (unlike the device-key path).
    import time
    if not settings.google_web_client_id:
        raise HTTPException(status_code=503, detail="google sign-in not configured")
    try:
        claims = security.verify_google_id_token(payload.id_token)
    except Exception:
        raise HTTPException(status_code=401, detail="invalid google token")
    email = claims.get("email")
    if not email or not claims.get("email_verified", False):
        raise HTTPException(status_code=401, detail="invalid google token")
    # Anti-replay: the request must carry a nonce that (a) matches the token's
    # `nonce` claim Google embedded at sign-in, and (b) is an issued, unexpired,
    # unconsumed server nonce. consume() is one-time, defeating token replay.
    token_nonce = claims.get("nonce")
    if (
        not payload.nonce
        or token_nonce != payload.nonce
        or not security.nonces.consume(payload.nonce, time.time())
    ):
        raise HTTPException(status_code=401, detail="nonce mismatch")
    if settings.allowed_email and email != settings.allowed_email:
        raise HTTPException(status_code=403, detail="account not allowed")
    return {"authorized": True, "email": email, "token": security.sessions.issue(time.time())}
