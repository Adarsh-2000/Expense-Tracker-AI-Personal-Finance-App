import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.db import Base, new_session_factory
from app.deps import get_session
from app.main import app


@pytest_asyncio.fixture
async def client():
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


async def test_register_is_idempotent(client):
    for _ in range(2):
        r = await client.post("/push/register", json={"token": "tok-abc"})
        assert r.status_code == 200
    # A second registration of the same token must not create a duplicate.
    r2 = await client.post("/push/register", json={"token": "tok-xyz"})
    assert r2.json()["ok"] is True


async def test_test_push_noop_without_credentials(client, monkeypatch):
    # Force FCM "disabled" regardless of whether a real service-account file happens
    # to be present in the dev checkout: reset the cached app + point at a missing
    # path. The endpoint must still respond cleanly (sent=0), never raising.
    from app.services import push as push_service

    monkeypatch.setattr(push_service, "_app", None)
    monkeypatch.setattr(push_service, "_init_attempted", False)
    monkeypatch.setattr(push_service.settings, "firebase_credentials_path",
                        "definitely-no-such-file.json")

    await client.post("/push/register", json={"token": "tok-1"})
    r = await client.post("/push/test")
    body = r.json()
    assert body["fcm_enabled"] is False
    assert body["devices"] == 1
    assert body["sent"] == 0


async def test_dispatch_pushes_to_registered_devices(client, monkeypatch):
    # Force FCM "enabled" and capture sends without any network.
    sent = []

    from app.services import push as push_service

    monkeypatch.setattr(push_service, "is_enabled", lambda: True)

    def fake_send(tokens, *, title, body, data=None):
        sent.append({"tokens": list(tokens), "title": title, "data": data})
        return push_service.PushResult(sent=len(tokens), failed=0, invalid_tokens=[])

    monkeypatch.setattr(push_service, "send_push", fake_send)

    await client.post("/push/register", json={"token": "device-1"})
    # Seed a bill so build_notifications yields at least one item to push.
    await client.post("/transactions/manual", json={
        "amount": 100, "merchant": "X", "occurred_at": "2026-06-01T09:00:00"})

    r1 = await client.post("/push/dispatch")
    first = r1.json()["pushed"]

    # Second dispatch must push nothing new (server-side dedup by stable id).
    r2 = await client.post("/push/dispatch")
    assert r2.json()["pushed"] == 0
    assert len(sent) == first  # only the first dispatch actually sent


async def test_invalid_token_is_pruned(client, monkeypatch):
    from app.services import push as push_service

    monkeypatch.setattr(push_service, "is_enabled", lambda: True)

    def fake_send(tokens, *, title, body, data=None):
        # FCM rejects this token as unregistered → should be pruned.
        return push_service.PushResult(sent=0, failed=1, invalid_tokens=list(tokens))

    monkeypatch.setattr(push_service, "send_push", fake_send)

    await client.post("/push/register", json={"token": "dead-token"})
    await client.post("/transactions/manual", json={
        "amount": 50, "merchant": "Y", "occurred_at": "2026-06-02T09:00:00"})
    await client.post("/push/dispatch")

    # The dead token was deleted, so a test push now sees zero devices.
    r = await client.post("/push/test")
    assert r.json()["devices"] == 0
