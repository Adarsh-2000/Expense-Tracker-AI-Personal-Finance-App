from app.repositories import InsightSummaryRepository


async def test_upsert_then_get_single_row(session):
    repo = InsightSummaryRepository(session)
    row = await repo.upsert("2026-05", "hash1", "m1",
                            spending_patterns="A", how_to_save="B", money_radar="C")
    assert row.period == "2026-05" and row.payload_hash == "hash1"
    assert row.generated_at is not None

    got = await repo.get("2026-05")
    assert got.spending_patterns == "A" and got.money_radar == "C"


async def test_upsert_updates_in_place(session):
    repo = InsightSummaryRepository(session)
    await repo.upsert("2026-05", "hash1", "m1", spending_patterns="A",
                      how_to_save="B", money_radar="C")
    await repo.upsert("2026-05", "hash2", "m2", spending_patterns="X",
                      how_to_save="Y", money_radar="Z")
    got = await repo.get("2026-05")
    assert got.payload_hash == "hash2" and got.model == "m2" and got.spending_patterns == "X"


async def test_get_missing_returns_none(session):
    assert await InsightSummaryRepository(session).get("1999-01") is None
