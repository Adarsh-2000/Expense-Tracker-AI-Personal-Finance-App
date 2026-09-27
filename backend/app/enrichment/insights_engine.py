from __future__ import annotations

from app.enrichment.taxonomy import NON_CONSUMPTION
from app.parsing.types import normalize_merchant

# NON_CONSUMPTION (money-movement / saving categories excluded from subscription,
# small-leak, and savings-rate "spend" calculations) is taxonomy-aware: it covers
# both the legacy 9 names and the new 16-category names. Imported above.


def _consumption(rows: list[dict]) -> list[dict]:
    return [r for r in rows if (r.get("category") or "") not in NON_CONSUMPTION]


def detect_subscriptions(rows: list[dict], months_span: float, *,
                         as_of=None, active_within_days: int = 45) -> list[dict]:
    """Find likely recurring charges: a merchant seen >= 3 times with a roughly
    stable amount. rows: debit dicts {merchant, amount, occurred_at, category}.

    Returns [{merchant, monthly_amount, count, last_seen, category}] sorted by
    monthly_amount desc. monthly_amount = average charge (per occurrence).

    When `as_of` (a datetime) is given, only ACTIVE subscriptions are returned —
    those whose most recent charge is within `active_within_days` of `as_of`. This
    drops ones the user has stopped paying (a monthly sub last billed >45 days ago
    has almost certainly been cancelled), mirroring Money Radar's active-only rule.
    """
    from datetime import timedelta
    cutoff = (as_of - timedelta(days=active_within_days)) if as_of is not None else None
    groups: dict[str, list[dict]] = {}
    for r in _consumption(rows):
        m = r["merchant"] or ""
        # ATM withdrawals / generic cash aren't subscriptions even if recurring.
        if "atm" in m.lower() or "cash" in m.lower():
            continue
        key = normalize_merchant(m)
        if key:
            groups.setdefault(key, []).append(r)

    subs = []
    for key, items in groups.items():
        if len(items) < 3:
            continue
        amounts = [i["amount"] for i in items]
        avg = sum(amounts) / len(amounts)
        if avg <= 0:
            continue
        # True subscriptions bill a near-identical amount each cycle (Netflix 649,
        # SIP fixed). A tight spread filters out variable spend (hospitals, hotels).
        spread = (max(amounts) - min(amounts)) / avg
        if spread > 0.35:  # allow FX-varying subs (Anthropic/Claude), reject hospitals
            continue
        last = max(items, key=lambda i: i["occurred_at"])
        if cutoff is not None and last["occurred_at"] < cutoff:
            continue  # last charge is stale — subscription no longer active
        subs.append({
            "merchant": last["merchant"],
            "monthly_amount": round(avg, 2),
            "count": len(items),
            "last_seen": last["occurred_at"].date().isoformat(),
            "category": last.get("category") or "uncategorized",
        })
    subs.sort(key=lambda s: s["monthly_amount"], reverse=True)
    return subs


def small_leaks(rows: list[dict], months_span: float, *, max_avg: float = 1000.0,
                min_count: int = 5) -> list[dict]:
    """Frequent SMALL spends that compound. A merchant with many low-value charges
    (avg <= max_avg, count >= min_count) — annualized to reframe the habit.

    Returns [{merchant, count, total, avg, annual_pace, category}] by annual_pace.
    """
    groups: dict[str, dict] = {}
    for r in _consumption(rows):
        key = r["merchant"] or "Unknown"
        g = groups.setdefault(key, {"merchant": key, "count": 0, "total": 0.0,
                                    "category": r.get("category") or "uncategorized"})
        g["count"] += 1; g["total"] += r["amount"]

    leaks = []
    span = months_span if months_span > 0 else 1.0
    for g in groups.values():
        avg = g["total"] / g["count"]
        if g["count"] < min_count or avg > max_avg:
            continue
        annual = g["total"] / span * 12.0
        leaks.append({
            "merchant": g["merchant"], "count": g["count"],
            "total": round(g["total"], 2), "avg": round(avg, 2),
            "annual_pace": round(annual, 2), "category": g["category"],
        })
    leaks.sort(key=lambda x: x["annual_pace"], reverse=True)
    return leaks


def behavioral_nudges(rows: list[dict], *, current_month: str,
                      month_start: str | None = None, month_end: str | None = None) -> list[dict]:
    """Generate save-more / spend-less nudges from consumption rows (90d window).
    `current_month` = 'YYYY-MM'. month_start/month_end (ISO) scope the drill-down
    `query` attached to merchant/category nudges so the UI can expand them into the
    underlying transactions. Returns insight-card dicts {type,severity,title,detail,query?}.
    """
    month_start = month_start or f"{current_month}-01"
    cons = _consumption(rows)
    nudges: list[dict] = []
    if not cons:
        return nudges

    cur = [r for r in cons if r["occurred_at"].strftime("%Y-%m") == current_month]

    # 1) Biggest single spend this month — real purchases only (money-movement
    # merchants that slipped categorization are filtered out).
    _MOVEMENT = ("cred", "zerodha", "iccl", "groww", "neft", "imps", "rtgs",
                 "card payment", "bill payment", "atm")
    purchases = [r for r in cur
                 if not any(k in (r["merchant"] or "").lower() for k in _MOVEMENT)]
    if purchases:
        big = max(purchases, key=lambda r: r["amount"])
        if big["amount"] >= 5000:
            nudges.append({"type": "big_ticket", "severity": "info",
                "title": f"Biggest spend: ₹{big['amount']:,.0f} at {big['merchant']}",
                "detail": "Your largest single purchase this month.",
                "query": {"merchant": big["merchant"], "start": month_start, "end": month_end}})

    # 2) Weekend vs weekday skew (Sat/Sun = weekday()>=5).
    wk = sum(r["amount"] for r in cur if r["occurred_at"].weekday() >= 5)
    wd = sum(r["amount"] for r in cur if r["occurred_at"].weekday() < 5)
    if wd > 0 and wk > wd * 0.6 and (wk + wd) > 10000:
        nudges.append({"type": "weekend", "severity": "info",
            "title": f"₹{wk:,.0f} of your spend is on weekends",
            "detail": "Weekend outings are a big share — a cap there saves fast."})

    # 3) Top category this month.
    by_cat: dict[str, float] = {}
    for r in cur:
        by_cat[r.get("category") or "uncategorized"] = by_cat.get(r.get("category") or "uncategorized", 0.0) + r["amount"]
    by_cat.pop("uncategorized", None)  # not an actionable "category"
    if by_cat:
        top_cat, top_amt = max(by_cat.items(), key=lambda kv: kv[1])
        if top_amt >= 5000:
            nudges.append({"type": "top_category", "severity": "info",
                "title": f"{top_cat.title()} is your top category — ₹{top_amt:,.0f}",
                "detail": "Where most of your money went this month.",
                "query": {"category": top_cat, "start": month_start, "end": month_end}})

    # 4) Month-over-month total consumption change.
    prior_months = {r["occurred_at"].strftime("%Y-%m") for r in cons} - {current_month}
    if prior_months:
        prior_total = sum(r["amount"] for r in cons if r["occurred_at"].strftime("%Y-%m") != current_month)
        avg_prior = prior_total / len(prior_months)
        cur_total = sum(r["amount"] for r in cur)
        if avg_prior > 5000 and cur_total > avg_prior * 1.2:
            nudges.append({"type": "trend_up", "severity": "warning",
                "title": f"Spending {round((cur_total/avg_prior - 1) * 100)}% above your monthly average",
                "detail": f"₹{cur_total:,.0f} this month vs ₹{avg_prior:,.0f} typical."})
        elif avg_prior > 5000 and cur_total < avg_prior * 0.8:
            nudges.append({"type": "trend_down", "severity": "positive",
                "title": f"Spending down {round((1 - cur_total/avg_prior) * 100)}% this month 🎉",
                "detail": f"₹{cur_total:,.0f} vs ₹{avg_prior:,.0f} average — keep it up."})

    return nudges


def savings_rate_series(monthly: list[dict]) -> list[dict]:
    """Per-month savings rate from {month, income, spend}.
    rate = (income - spend) / income, clamped; 0 income -> rate None."""
    out = []
    for m in monthly:
        income = m["income"]; spend = m["spend"]
        rate = round((income - spend) / income * 100.0, 1) if income > 0 else None
        out.append({"month": m["month"], "income": round(income, 2),
                    "spend": round(spend, 2), "saved": round(income - spend, 2),
                    "rate": rate})
    return out
