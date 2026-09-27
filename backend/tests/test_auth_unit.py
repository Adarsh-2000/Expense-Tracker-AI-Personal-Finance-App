from app.auth import SessionStore, RateLimiter


def test_session_token_expires():
    store = SessionStore(ttl_seconds=100)
    tok = store.issue(now=1000.0)
    assert store.valid(tok, now=1050.0) is True
    assert store.valid(tok, now=1101.0) is False


def test_session_revoke_all():
    store = SessionStore(ttl_seconds=100)
    tok = store.issue(now=0.0)
    store.revoke_all()
    assert store.valid(tok, now=1.0) is False


def test_rate_limiter_window():
    rl = RateLimiter(max_attempts=2, window_seconds=60)
    assert rl.allowed("ip", now=0.0) is True
    rl.record_fail("ip", now=0.0)
    rl.record_fail("ip", now=1.0)
    assert rl.allowed("ip", now=2.0) is False
    assert rl.allowed("ip", now=120.0) is True  # window passed
