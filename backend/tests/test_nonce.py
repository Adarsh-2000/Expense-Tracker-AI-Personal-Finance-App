from __future__ import annotations

from app.auth import NonceStore


def test_issue_returns_nonempty_unique_nonces():
    store = NonceStore()
    a = store.issue(1000.0)
    b = store.issue(1000.0)
    assert a and b
    assert a != b


def test_consume_valid_unexpired_nonce_returns_true():
    store = NonceStore(ttl_seconds=300)
    n = store.issue(1000.0)
    assert store.consume(n, 1000.0) is True


def test_consume_is_one_time_only():
    store = NonceStore(ttl_seconds=300)
    n = store.issue(1000.0)
    assert store.consume(n, 1000.0) is True
    # second use must fail (consumed)
    assert store.consume(n, 1000.0) is False


def test_consume_expired_nonce_returns_false():
    store = NonceStore(ttl_seconds=300)
    n = store.issue(1000.0)
    # now is past expiry (1000 + 300 = 1300)
    assert store.consume(n, 1300.1) is False


def test_consume_at_exact_expiry_boundary_is_valid():
    store = NonceStore(ttl_seconds=300)
    n = store.issue(1000.0)
    assert store.consume(n, 1300.0) is True


def test_consume_unknown_nonce_returns_false():
    store = NonceStore()
    assert store.consume("never-issued", 1000.0) is False


def test_expired_nonce_cannot_be_reused_after_expiry():
    store = NonceStore(ttl_seconds=300)
    n = store.issue(1000.0)
    # expired consume fails and removes it; subsequent attempts also fail
    assert store.consume(n, 1300.1) is False
    assert store.consume(n, 1000.0) is False


def test_reset_clears_all_nonces():
    store = NonceStore(ttl_seconds=300)
    n = store.issue(1000.0)
    store.reset()
    assert store.consume(n, 1000.0) is False
