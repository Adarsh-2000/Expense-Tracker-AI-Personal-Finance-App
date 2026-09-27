from app.repositories import CategoryRepository


async def test_category_get_or_create_is_idempotent(session):
    repo = CategoryRepository(session)
    c1 = await repo.get_or_create("food")
    c2 = await repo.get_or_create("food")
    await session.flush()
    assert c1.id == c2.id
