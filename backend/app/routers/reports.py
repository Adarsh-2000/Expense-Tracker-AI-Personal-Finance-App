from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.deps import get_session
from app.repositories import TransactionRepository
from app.services.dates import month_range, months_span

router = APIRouter()


@router.get("/reports/summary")
async def category_summary(month: str | None = None, start: str | None = None,
                           end: str | None = None, session: AsyncSession = Depends(get_session)):
    repo = TransactionRepository(session)
    if start and end:
        totals = await repo.category_totals_in_range(
            datetime.fromisoformat(start), datetime.fromisoformat(end))
    elif month:
        a, b = month_range(month)
        totals = await repo.category_totals_in_month(a, b)
    else:
        totals = await repo.category_totals()
    return [{"category": name, "total": total} for name, total in totals]


@router.get("/reports/months")
async def report_months(session: AsyncSession = Depends(get_session)):
    return {"months": await TransactionRepository(session).months_with_data()}


@router.get("/reports/spent")
async def spent(month: str | None = None, start: str | None = None, end: str | None = None,
                session: AsyncSession = Depends(get_session)):
    repo = TransactionRepository(session)
    if start and end:
        s = await repo.spent_in_range(datetime.fromisoformat(start), datetime.fromisoformat(end))
        return {"start": start, "end": end, "spent": s}
    if month:
        a, b = month_range(month)
        s = await repo.spent_in_month(a, b)
        return {"month": month, "spent": s}
    s = await repo.spent_in_month(datetime(1970, 1, 1), datetime(2100, 1, 1))
    return {"month": None, "spent": s}


@router.get("/reports/breakdown")
async def report_breakdown(start: str, end: str, category_id: int | None = None,
                           session: AsyncSession = Depends(get_session)):
    """Debit-only spend breakdown for [start, end): grand total plus a
    category -> sub-category hierarchy (sub-categories derived from merchant).
    """
    from app.enrichment.subcategories import subcategorize

    rows = await TransactionRepository(session).breakdown_rows(
        datetime.fromisoformat(start), datetime.fromisoformat(end), category_id)

    cats: dict[str, dict] = {}
    grand = 0.0
    for cat_name, merchant, amount in rows:
        grand += amount
        c = cats.setdefault(cat_name, {"category": cat_name, "total": 0.0, "count": 0, "_subs": {}})
        c["total"] += amount
        c["count"] += 1
        sub = subcategorize(cat_name, merchant)
        s = c["_subs"].setdefault(sub, {"name": sub, "total": 0.0, "count": 0})
        s["total"] += amount
        s["count"] += 1

    out = []
    for c in cats.values():
        subs = sorted(c.pop("_subs").values(), key=lambda x: x["total"], reverse=True)
        c["subcategories"] = subs
        out.append(c)
    out.sort(key=lambda x: x["total"], reverse=True)
    return {"start": start, "end": end, "total": grand, "categories": out}


@router.get("/reports/rails")
async def report_rails(start: str, end: str, session: AsyncSession = Depends(get_session)):
    """Spend split by payment rail (Credit Card / UPI / Debit / Bank Transfer),
    each with a drill-down: per-card for credit cards, top merchants/VPAs otherwise."""
    from app.enrichment.rails import RAIL_LABELS, classify_rail

    rows = await TransactionRepository(session).analytics_rows(
        datetime.fromisoformat(start), datetime.fromisoformat(end))
    rails: dict[str, dict] = {}
    for r in rows:
        rail = classify_rail(account_type=r["account_type"], merchant=r["merchant"])
        g = rails.setdefault(rail, {"rail": rail, "label": RAIL_LABELS[rail],
                                    "total": 0.0, "count": 0, "_items": {}})
        g["total"] += r["amount"]; g["count"] += 1
        # Sub-item key: card last4 for credit cards, merchant otherwise.
        key = (f"{r['bank']} ••{r['last4']}" if rail == "credit_card" and r["last4"]
               else (r["merchant"] or "Unknown"))
        it = g["_items"].setdefault(key, {"name": key, "total": 0.0, "count": 0})
        it["total"] += r["amount"]; it["count"] += 1

    out = []
    for g in rails.values():
        items = sorted(g.pop("_items").values(), key=lambda x: x["total"], reverse=True)[:12]
        g["items"] = [{"name": i["name"], "total": round(i["total"], 2), "count": i["count"]} for i in items]
        g["total"] = round(g["total"], 2)
        out.append(g)
    out.sort(key=lambda x: x["total"], reverse=True)
    return {"start": start, "end": end, "rails": out,
            "total": round(sum(g["total"] for g in out), 2)}


@router.get("/reports/spending-patterns")
async def report_spending_patterns(start: str, end: str,
                                   session: AsyncSession = Depends(get_session)):
    """When + how often you spend: a day-of-week × time-slot heatmap and the top
    merchants ranked by VISIT COUNT (frequency), with average ticket size."""
    rows = await TransactionRepository(session).analytics_rows(
        datetime.fromisoformat(start), datetime.fromisoformat(end))

    # Heatmap: 7 days × 4 slots (0 night 0-6, 1 morning 6-12, 2 afternoon 12-18, 3 evening 18-24).
    heat: dict[tuple[int, int], dict] = {}
    by_dow = [0.0] * 7
    for r in rows:
        ts = r["occurred_at"]
        dow = ts.weekday()
        slot = ts.hour // 6
        by_dow[dow] += r["amount"]
        cell = heat.setdefault((dow, slot), {"dow": dow, "slot": slot, "total": 0.0, "count": 0})
        cell["total"] += r["amount"]; cell["count"] += 1

    # Frequency: group by merchant, rank by count.
    freq: dict[str, dict] = {}
    for r in rows:
        name = r["merchant"] or "Unknown"
        f = freq.setdefault(name, {"merchant": name, "count": 0, "total": 0.0})
        f["count"] += 1; f["total"] += r["amount"]
    frequency = sorted(freq.values(), key=lambda x: x["count"], reverse=True)[:15]
    for f in frequency:
        f["avg"] = round(f["total"] / f["count"], 2) if f["count"] else 0.0
        f["total"] = round(f["total"], 2)

    return {
        "start": start, "end": end,
        "heatmap": [{"dow": c["dow"], "slot": c["slot"],
                     "total": round(c["total"], 2), "count": c["count"]}
                    for c in heat.values()],
        "by_dow": [round(v, 2) for v in by_dow],
        "frequency": frequency,
    }


@router.get("/reports/subscriptions")
async def report_subscriptions(start: str, end: str,
                               session: AsyncSession = Depends(get_session)):
    from app.enrichment.insights_engine import detect_subscriptions
    rows = await TransactionRepository(session).analytics_rows(
        datetime.fromisoformat(start), datetime.fromisoformat(end))
    # Active-only: drop subscriptions whose last charge is stale relative to the
    # window end (cancelled subs the user no longer pays).
    subs = detect_subscriptions(rows, months_span(start, end),
                                as_of=datetime.fromisoformat(end))
    return {"subscriptions": subs,
            "monthly_total": round(sum(s["monthly_amount"] for s in subs), 2),
            "count": len(subs)}


@router.get("/reports/small-leaks")
async def report_small_leaks(start: str, end: str,
                             session: AsyncSession = Depends(get_session)):
    from app.enrichment.insights_engine import small_leaks
    rows = await TransactionRepository(session).analytics_rows(
        datetime.fromisoformat(start), datetime.fromisoformat(end))
    leaks = small_leaks(rows, months_span(start, end))
    return {"leaks": leaks[:12],
            "annual_total": round(sum(x["annual_pace"] for x in leaks), 2)}


@router.get("/reports/savings-rate")
async def report_savings_rate(months: int = 6, session: AsyncSession = Depends(get_session)):
    """Per-month income (credits) vs spend (debits) → savings rate series."""
    from sqlalchemy import func, select as sa_select

    from app.enrichment.insights_engine import NON_CONSUMPTION, savings_rate_series
    from app.entities import Category, Transaction

    mon = func.substr(Transaction.occurred_at, 1, 7)
    rows = (await session.execute(
        sa_select(mon, Transaction.direction, Category.name, func.sum(Transaction.amount))
        .outerjoin(Category, Transaction.category_id == Category.id)
        .group_by(mon, Transaction.direction, Category.name)
    )).all()
    by_month: dict[str, dict] = {}
    for m, direction, cat, total in rows:
        d = by_month.setdefault(m, {"month": m, "income": 0.0, "spend": 0.0})
        if direction == "credit":
            # Income = all credits (salary often arrives as NEFT → 'transfers',
            # so don't exclude that category here).
            d["income"] += float(total)
        else:
            # Spend = consumption only. Investing / transfers are saving, not spending.
            if (cat or "") not in NON_CONSUMPTION:
                d["spend"] += float(total)
    # Only months with meaningful income (a salary landed) give a sensible rate.
    ordered = [m for m in sorted(by_month.values(), key=lambda x: x["month"])
               if m["income"] >= 20000][-months:]
    series = savings_rate_series(ordered)
    rates = [s["rate"] for s in series if s["rate"] is not None]
    return {"series": series,
            "avg_rate": round(sum(rates) / len(rates), 1) if rates else None}


@router.get("/reports/trend")
async def report_trend(session: AsyncSession = Depends(get_session)):
    series = await TransactionRepository(session).monthly_spend_series()
    return {"series": [{"month": m, "spent": t} for m, t in series]}


@router.get("/reports/top-merchants")
async def report_top_merchants(month: str | None = None, limit: int = 10,
                               session: AsyncSession = Depends(get_session)):
    repo = TransactionRepository(session)
    if month:
        a, b = month_range(month)
        rows = await repo.top_merchants(start=a, end=b, limit=limit)
    else:
        rows = await repo.top_merchants(limit=limit)
    return {"merchants": [{"merchant": m, "total": t, "count": n} for m, t, n in rows]}


@router.get("/reports/daily")
async def report_daily(month: str | None = None, session: AsyncSession = Depends(get_session)):
    if not month:
        month = datetime.now().strftime("%Y-%m")
    a, b = month_range(month)
    rows = await TransactionRepository(session).daily_spend_in_month(a, b)
    return {"month": month, "days": [{"date": d, "spent": t} for d, t in rows]}
