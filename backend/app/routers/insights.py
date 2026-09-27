from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.deps import get_session
from app.enrichment.taxonomy import NON_CONSUMPTION
from app.repositories import TransactionRepository
from app.services.dates import months_span

router = APIRouter()


@router.get("/insights")
async def insights_feed(session: AsyncSession = Depends(get_session)):
    """A ranked feed of actionable insight cards (nudges) auto-generated from the
    user's data: subscriptions, savings rate, small leaks, MoM category spikes,
    and unbilled card totals."""
    from datetime import date, timedelta

    from app.enrichment.insights_engine import (
        behavioral_nudges, detect_subscriptions, small_leaks,
    )

    today = date.today()
    start_90 = (today - timedelta(days=90)).isoformat()
    end = (today + timedelta(days=1)).isoformat()
    month_start = today.replace(day=1).isoformat()
    repo = TransactionRepository(session)
    rows = await repo.analytics_rows(datetime.fromisoformat(start_90), datetime.fromisoformat(end))
    span = months_span(start_90, end)

    feed: list[dict] = []

    subs = detect_subscriptions(rows, span)
    if subs:
        feed.append({
            "type": "subscriptions", "severity": "info",
            "title": f"₹{sum(s['monthly_amount'] for s in subs):,.0f}/mo across {len(subs)} subscriptions",
            "detail": "Review recurring charges — cancel what you don't use.",
        })

    leaks = small_leaks(rows, span)
    if leaks:
        top = leaks[0]
        feed.append({
            "type": "small_leak", "severity": "warning",
            "title": f"{top['merchant']}: ₹{top['annual_pace']:,.0f}/yr pace",
            "detail": f"{top['count']} small charges (avg ₹{top['avg']:,.0f}) add up fast.",
            "query": {"merchant": top["merchant"], "start": start_90, "end": end},
        })

    # MoM spike: compare this month's category spend vs trailing avg.
    cur_month = today.strftime("%Y-%m")
    cur = [r for r in rows if r["occurred_at"].strftime("%Y-%m") == cur_month]
    cur_by_cat: dict[str, float] = {}
    for r in cur:
        cur_by_cat[r["category"] or "uncategorized"] = cur_by_cat.get(r["category"] or "uncategorized", 0.0) + r["amount"]
    prior_by_cat: dict[str, list] = {}
    for r in rows:
        if r["occurred_at"].strftime("%Y-%m") != cur_month:
            prior_by_cat.setdefault(r["category"] or "uncategorized", []).append(r)
    for cat, amt in cur_by_cat.items():
        prior = prior_by_cat.get(cat, [])
        if not prior:
            continue
        months_prior = len({r["occurred_at"].strftime("%Y-%m") for r in prior}) or 1
        avg = sum(r["amount"] for r in prior) / months_prior
        if avg > 1000 and amt > avg * 1.4 and cat not in NON_CONSUMPTION:
            feed.append({
                "type": "category_spike", "severity": "warning",
                "title": f"{cat.title()} {round((amt/avg - 1) * 100)}% above your average",
                "detail": f"₹{amt:,.0f} this month vs ₹{avg:,.0f}/mo typical.",
                "query": {"category": cat, "start": month_start, "end": end},
            })

    # Behavioral nudges (biggest spend, weekend skew, top category, MoM trend).
    feed.extend(behavioral_nudges(rows, current_month=cur_month,
                                  month_start=month_start, month_end=end))

    # Budget pacing: warn on categories over cap or on pace to exceed this month.
    try:
        from app.enrichment.zero_based import ZeroBasedBudgetService
        from app.repositories import (
            BudgetAssignmentRepository, CategoryRepository, MonthlyIncomeRepository,
            TransactionSplitRepository,
        )
        bsvc = ZeroBasedBudgetService(
            income=MonthlyIncomeRepository(session),
            assignments=BudgetAssignmentRepository(session),
            transactions=repo, splits=TransactionSplitRepository(session))
        names = {c.id: c.name for c in await CategoryRepository(session).list_all()}
        for s in await bsvc.category_status(cur_month, names=names):
            q = {"category": s.category, "start": month_start, "end": end}
            if s.status == "exceeded":
                feed.append({"type": "budget", "severity": "warning",
                    "title": f"{s.category.title()} budget exceeded",
                    "detail": f"₹{s.spent:,.0f} spent of ₹{s.assigned:,.0f} cap.", "query": q})
            elif s.pace_status == "at_risk":
                feed.append({"type": "budget", "severity": "warning",
                    "title": f"{s.category.title()} on pace to exceed",
                    "detail": f"₹{s.spent:,.0f} of ₹{s.assigned:,.0f} so far · projected ₹{s.projected:,.0f}.",
                    "query": q})
    except Exception:
        pass

    # Resolve each card's query.category (name) → category_id so the UI can fetch
    # the underlying transactions via /transactions/search. Drop the name key.
    from app.repositories import CategoryRepository as _CatRepo
    id_by_name = {c.name: c.id for c in await _CatRepo(session).list_all()}
    for card in feed:
        q = card.get("query")
        if q and "category" in q:
            q["category_id"] = id_by_name.get(q.pop("category"))

    # Optional: rewrite each card into friendlier prose via a local LLM. No-op when
    # ET_INSIGHTS_NARRATE is unset; numbers are re-verified so a card never shows a
    # figure the engine didn't compute.
    from app.enrichment.insight_narrator import narrate_cards
    feed = await narrate_cards(feed)

    return {"insights": feed, "count": len(feed)}


@router.get("/insights/summary")
async def insights_summary(
    month: str | None = Query(default=None, pattern=r"^\d{4}-\d{2}$"),
    session: AsyncSession = Depends(get_session),
):
    """Per-month LLM insight summary (Spending Patterns / How To Save More / Money
    Radar). No-op (enabled=false) unless ET_INSIGHTS_NARRATE is set. Never 5xx on
    LLM failure - the screen falls back to the deterministic /insights cards."""
    from datetime import date

    from app.services.insight_summary_service import get_summary
    period = month or date.today().strftime("%Y-%m")
    return await get_summary(session, period)
