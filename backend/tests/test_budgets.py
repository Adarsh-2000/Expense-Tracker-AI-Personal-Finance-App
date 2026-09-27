from app.enrichment.budgets import evaluate_budget


def test_under_budget():
    s = evaluate_budget(limit=1000.0, spent=400.0)
    assert s.status == "ok"
    assert s.percent == 40.0


def test_near_budget_warns_at_80_percent():
    s = evaluate_budget(limit=1000.0, spent=850.0)
    assert s.status == "warning"


def test_over_budget():
    s = evaluate_budget(limit=1000.0, spent=1200.0)
    assert s.status == "exceeded"


async def test_pacing_at_risk_and_over():
    """Pace-based status: spending fast early in the month flags at_risk; over cap flags over."""
    from datetime import date
    from app.enrichment.zero_based import ZeroBasedBudgetService

    class _Assign:
        def __init__(self, cid, amt): self.category_id = cid; self.assigned_amount = amt
    class _AsgRepo:
        async def list_for_month(self, m):
            return [_Assign(1, 10000), _Assign(2, 10000), _Assign(3, 10000)]
        async def total_assigned(self, m): return 30000
    class _Txn:
        def __init__(self, cid, amt): self.id=0; self.direction="debit"; self.category_id=cid; self.amount=amt
    class _TxnRepo:
        async def list_in_month(self, a, b):
            # cat1: ₹8000 by day 10 (pace→24000, at_risk); cat2: ₹11000 (over); cat3: ₹1000 (on_track)
            return [_Txn(1, 8000), _Txn(2, 11000), _Txn(3, 1000)]
    class _SplitRepo:
        async def list_for(self, tid): return []
    class _IncRepo:
        async def get(self, m): return None

    svc = ZeroBasedBudgetService(income=_IncRepo(), assignments=_AsgRepo(),
                                 transactions=_TxnRepo(), splits=_SplitRepo())
    # day 10 of a 30-day month → elapsed 1/3
    res = await svc.category_status("2026-06", today=date(2026, 6, 10),
                                    names={1: "food", 2: "shopping", 3: "travel"})
    by_cat = {s.category: s for s in res}
    assert by_cat["food"].pace_status == "at_risk"      # projected 24000 > 10000
    assert by_cat["shopping"].pace_status == "over"     # already over cap
    assert by_cat["shopping"].status == "exceeded"
    assert by_cat["travel"].pace_status == "on_track"
    assert by_cat["food"].category == "food"
