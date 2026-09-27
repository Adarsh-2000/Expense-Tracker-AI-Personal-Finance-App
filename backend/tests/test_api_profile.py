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


async def test_put_valid_pan_then_get_masked(client):
    # lowercase PAN is uppercased before validation/storage.
    resp = await client.put("/profile", json={"pan": "dxwpk1177g", "dob": "01-01-1990"})
    assert resp.status_code == 200
    assert resp.json() == {"ok": True}

    got = await client.get("/profile")
    assert got.status_code == 200
    body = got.json()
    assert body["has_pan"] is True
    assert body["pan_masked"] == "XXXXXX177G"
    assert body["dob"] == "01-01-1990"


async def test_invalid_pan_rejected(client):
    resp = await client.put("/profile", json={"pan": "NOTAPAN"})
    assert resp.status_code == 400


async def test_get_profile_empty(client):
    got = await client.get("/profile")
    assert got.status_code == 200
    body = got.json()
    assert body["has_pan"] is False
    assert body["pan_masked"] is None
    assert body["dob"] is None


async def test_put_profile_without_pan_ok(client):
    resp = await client.put("/profile", json={"dob": "02-02-1992"})
    assert resp.status_code == 200
    got = (await client.get("/profile")).json()
    assert got["has_pan"] is False
    assert got["dob"] == "02-02-1992"
