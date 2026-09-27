from app.db import Base, new_session_factory


async def test_session_factory_creates_schema():
    engine, Session = new_session_factory("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    async with Session() as session:
        assert session is not None
    await engine.dispose()
