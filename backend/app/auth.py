from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
import tempfile


def hash_pin(pin: str, salt: str = "") -> str:
    # NOTE: sha256 with a per-deployment salt. For a low-entropy PIN the PRIMARY
    # online defense is the rate limiter on /auth/unlock; a memory-hard KDF
    # (argon2id) is the recommended future upgrade (deferred: no Py3.14 wheel yet).
    return hashlib.sha256((salt + ":" + pin).encode()).hexdigest()


def verify_pin(pin: str, salt: str, pin_hash: str) -> bool:
    return hmac.compare_digest(hash_pin(pin, salt), pin_hash)


class SessionStore:
    """Opaque random bearer tokens with expiry; server-side, revocable.

    Optionally file-backed (`path`): tokens are persisted so the user stays signed
    in across backend restarts. Without a path it behaves as a pure in-memory store
    (used by tests). The file holds `{token: expiry_epoch}` as JSON.
    """
    def __init__(self, ttl_seconds: int = 3600, path: str | None = None):
        self._ttl = ttl_seconds
        self._path = path
        self._tokens: dict[str, float] = self._load()

    def _load(self) -> dict[str, float]:
        if not self._path or not os.path.exists(self._path):
            return {}
        try:
            with open(self._path) as f:
                return {str(k): float(v) for k, v in json.load(f).items()}
        except (OSError, ValueError):
            return {}

    def _save(self) -> None:
        if not self._path:
            return
        try:
            d = os.path.dirname(self._path) or "."
            with tempfile.NamedTemporaryFile("w", dir=d, delete=False) as tmp:
                json.dump(self._tokens, tmp)
                tmp_path = tmp.name
            os.replace(tmp_path, self._path)   # atomic
        except OSError:
            pass

    def issue(self, now: float) -> str:
        token = secrets.token_urlsafe(32)
        self._tokens[token] = now + self._ttl
        self._save()
        return token

    def add(self, token: str, exp: float) -> None:
        """Register an externally-minted token (e.g. recovering a device token)."""
        self._tokens[token] = exp
        self._save()

    def valid(self, token: str, now: float) -> bool:
        if not token:
            return False
        exp = self._tokens.get(token)
        if exp is None:
            return False
        if now > exp:
            self._tokens.pop(token, None)
            self._save()
            return False
        return True

    def revoke_all(self) -> None:
        self._tokens.clear()
        self._save()


class NonceStore:
    """One-time, short-TTL random nonces that bind a sign-in to a fresh server challenge.

    The server issues a nonce; the client embeds it in the Google ID token (via
    setNonce). On verify, consume() succeeds at most once per issued, unexpired
    nonce — defeating replay of a previously captured ID token.
    """
    def __init__(self, ttl_seconds: int = 300):
        self._nonces: dict[str, float] = {}
        self._ttl = ttl_seconds

    def issue(self, now: float) -> str:
        n = secrets.token_urlsafe(24)
        self._nonces[n] = now + self._ttl
        return n

    def consume(self, nonce: str, now: float) -> bool:
        # valid if present and unexpired; consume (one-time) on success
        exp = self._nonces.pop(nonce, None)
        if exp is None:
            return False
        return now <= exp

    def reset(self) -> None:
        self._nonces.clear()


class RateLimiter:
    """Sliding-window per-key attempt limiter."""
    def __init__(self, max_attempts: int = 5, window_seconds: int = 60):
        self._fails: dict[str, list[float]] = {}
        self.max = max_attempts
        self.window = window_seconds

    def allowed(self, key: str, now: float) -> bool:
        recent = [t for t in self._fails.get(key, []) if now - t < self.window]
        self._fails[key] = recent
        return len(recent) < self.max

    def record_fail(self, key: str, now: float) -> None:
        self._fails.setdefault(key, []).append(now)

    def reset(self) -> None:
        self._fails.clear()
