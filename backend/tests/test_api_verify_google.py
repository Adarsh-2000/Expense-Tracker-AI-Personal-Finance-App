from __future__ import annotations

import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app import security
from app.config import settings
from app.db import Base, new_session_factory
from app.deps import get_session
from app.main import app


@pytest_asyncio.fixture
async def client(monkeypatch):
    # No PIN, no device key by default -> lock inactive; individual tests opt in.
    monkeypatch.setattr(settings, "app_pin_hash", None)
    monkeypatch.setattr(settings, "device_key", "")
    monkeypatch.setattr(settings, "allowed_email", "")
    monkeypatch.setattr(settings, "google_web_client_id", "")
    security.sessions.revoke_all()
    security.unlock_limiter.reset()
    security.nonces.reset()
    engine, Session = new_session_factory("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async def override():
        async with Session() as s:
            yield s

    app.dependency_overrides[get_session] = override
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c
    app.dependency_overrides.clear()
    await engine.dispose()


async def _issue_nonce(client) -> str:
    resp = await client.get("/auth/nonce")
    assert resp.status_code == 200
    nonce = resp.json()["nonce"]
    assert nonce
    return nonce


def _claims(email="owner@example.com", verified=True, nonce=None):
    info = {"email": email, "email_verified": verified}
    if nonce is not None:
        info["nonce"] = nonce
    return info


async def test_verify_google_not_configured_is_503(client):
    # google_web_client_id == "" -> sign-in not configured.
    resp = await client.post("/auth/verify-google", json={"id_token": "anything"})
    assert resp.status_code == 503


async def test_auth_nonce_returns_nonce(client):
    resp = await client.get("/auth/nonce")
    assert resp.status_code == 200
    assert resp.json()["nonce"]


async def test_verify_google_success_issues_token(client, monkeypatch):
    monkeypatch.setattr(settings, "google_web_client_id", "web-client-id.apps.googleusercontent.com")
    nonce = await _issue_nonce(client)
    monkeypatch.setattr(security, "verify_google_id_token", lambda tok: _claims(nonce=nonce))
    resp = await client.post("/auth/verify-google", json={"id_token": "valid-token", "nonce": nonce})
    assert resp.status_code == 200
    body = resp.json()
    assert body["authorized"] is True
    assert body["email"] == "owner@example.com"
    assert body["token"]


async def test_verify_google_replayed_nonce_is_401(client, monkeypatch):
    # A second sign-in reusing the same (already consumed) nonce must be rejected.
    monkeypatch.setattr(settings, "google_web_client_id", "web-client-id.apps.googleusercontent.com")
    nonce = await _issue_nonce(client)
    monkeypatch.setattr(security, "verify_google_id_token", lambda tok: _claims(nonce=nonce))
    first = await client.post("/auth/verify-google", json={"id_token": "valid-token", "nonce": nonce})
    assert first.status_code == 200
    replay = await client.post("/auth/verify-google", json={"id_token": "valid-token", "nonce": nonce})
    assert replay.status_code == 401


async def test_verify_google_missing_nonce_is_401(client, monkeypatch):
    monkeypatch.setattr(settings, "google_web_client_id", "web-client-id.apps.googleusercontent.com")
    nonce = await _issue_nonce(client)
    monkeypatch.setattr(security, "verify_google_id_token", lambda tok: _claims(nonce=nonce))
    # client omits the nonce in the request body
    resp = await client.post("/auth/verify-google", json={"id_token": "valid-token"})
    assert resp.status_code == 401


async def test_verify_google_token_nonce_mismatch_is_401(client, monkeypatch):
    # Token's nonce claim differs from the nonce the client claims to have used.
    monkeypatch.setattr(settings, "google_web_client_id", "web-client-id.apps.googleusercontent.com")
    nonce = await _issue_nonce(client)
    monkeypatch.setattr(security, "verify_google_id_token", lambda tok: _claims(nonce="some-other-nonce"))
    resp = await client.post("/auth/verify-google", json={"id_token": "valid-token", "nonce": nonce})
    assert resp.status_code == 401


async def test_verify_google_email_mismatch_is_403(client, monkeypatch):
    monkeypatch.setattr(settings, "google_web_client_id", "web-client-id.apps.googleusercontent.com")
    monkeypatch.setattr(settings, "allowed_email", "owner@example.com")
    nonce = await _issue_nonce(client)
    monkeypatch.setattr(
        security, "verify_google_id_token", lambda tok: _claims(email="intruder@example.com", nonce=nonce)
    )
    resp = await client.post("/auth/verify-google", json={"id_token": "valid-token", "nonce": nonce})
    assert resp.status_code == 403


async def test_verify_google_invalid_token_is_401(client, monkeypatch):
    monkeypatch.setattr(settings, "google_web_client_id", "web-client-id.apps.googleusercontent.com")
    nonce = await _issue_nonce(client)

    def _raise(tok):
        raise ValueError("bad token")

    monkeypatch.setattr(security, "verify_google_id_token", _raise)
    resp = await client.post("/auth/verify-google", json={"id_token": "bogus", "nonce": nonce})
    assert resp.status_code == 401


async def test_auth_config_returns_client_id(client, monkeypatch):
    monkeypatch.setattr(settings, "google_web_client_id", "web-client-id.apps.googleusercontent.com")
    resp = await client.get("/auth/config")
    assert resp.status_code == 200
    assert resp.json() == {"google_web_client_id": "web-client-id.apps.googleusercontent.com"}


async def test_open_paths_reachable_while_lock_active(client, monkeypatch):
    # With a device key set the lock is active; these are open paths so the
    # middleware must NOT 401 them.
    monkeypatch.setattr(settings, "device_key", "testkey")
    monkeypatch.setattr(settings, "google_web_client_id", "web-client-id.apps.googleusercontent.com")

    config_resp = await client.get("/auth/config")
    assert config_resp.status_code != 401
    assert config_resp.status_code == 200

    # /auth/nonce must also be reachable while the lock is active.
    nonce_resp = await client.get("/auth/nonce")
    assert nonce_resp.status_code != 401
    assert nonce_resp.status_code == 200
    nonce = nonce_resp.json()["nonce"]

    monkeypatch.setattr(security, "verify_google_id_token", lambda tok: _claims(nonce=nonce))
    verify_resp = await client.post("/auth/verify-google", json={"id_token": "valid-token", "nonce": nonce})
    assert verify_resp.status_code != 401
    assert verify_resp.status_code == 200
