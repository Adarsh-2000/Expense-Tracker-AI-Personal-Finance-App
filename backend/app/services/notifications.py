from __future__ import annotations

from datetime import date, datetime, timedelta

from sqlalchemy.ext.asyncio import AsyncSession

from app.services.networth import networth_components

# Goal-progress milestones (highest reached is surfaced).
_GOAL_MILESTONES = (100, 75, 50, 25)
# A bill reminder fires only for bills due within the next few days or recently
# overdue — old unpaid statements (stale historical data) are NOT notification-worthy.
_BILL_DUE_WINDOW_DAYS = 3
_BILL_OVERDUE_GRACE_DAYS = 14


def _bill_notifications(bills, today: date) -> list[dict]:
    items: list[dict] = []
    for b in bills:
        try:
            days = (date.fromisoformat(b.due_date) - today).days
        except (ValueError, TypeError):
            continue
        if days > _BILL_DUE_WINDOW_DAYS or days < -_BILL_OVERDUE_GRACE_DAYS:
            continue
        when = "overdue" if days < 0 else ("due today" if days == 0 else f"due in {days} day{'s' if days != 1 else ''}")
        card = f"{b.bank}{' ••' + b.last4 if b.last4 else ''}"
        items.append({
            "id": f"bill-{b.bank}-{b.last4 or ''}-{b.due_date}",
            "type": "bill", "severity": "warning", "route": "bills",
            "anchor": b.last4 or None,
            "title": f"{card} bill {when}",
            "body": f"₹{b.total_due:,.0f} due on {b.due_date}.",
        })
    return items


def _goal_notifications(goals, current: float) -> list[dict]:
    items: list[dict] = []
    for g in goals:
        if g.target_amount <= 0:
            continue
        pct = current / g.target_amount * 100.0
        milestone = next((m for m in _GOAL_MILESTONES if pct >= m), None)
        if milestone is not None:
            done = milestone == 100
            items.append({
                "id": f"goal-{g.id}-{milestone}",
                "type": "goal", "severity": "info", "route": "networth",
                "title": (f"🎉 Goal reached: {g.name}" if done
                          else f"{g.name}: {milestone}% there"),
                "body": (f"You've hit your ₹{g.target_amount:,.0f} target!" if done
                         else f"₹{current:,.0f} of ₹{g.target_amount:,.0f} ({pct:.0f}%)."),
            })
    return items


def _budget_notifications(statuses, month: str) -> list[dict]:
    items: list[dict] = []
    for s in statuses:
        if s.assigned <= 0:
            continue
        used = s.spent / s.assigned * 100.0
        if s.status == "exceeded":
            items.append({
                "id": f"budget-{s.category}-{month}-exceeded",
                "type": "budget", "severity": "warning", "route": "budget",
                "anchor": s.category,
                "title": f"{s.category.title()} budget exceeded",
                "body": f"₹{s.spent:,.0f} spent of your ₹{s.assigned:,.0f} cap.",
            })
        elif s.pace_status == "at_risk":
            items.append({
                "id": f"budget-{s.category}-{month}-at_risk",
                "type": "budget", "severity": "warning", "route": "budget",
                "anchor": s.category,
                "title": f"{s.category.title()} on pace to exceed",
                "body": f"Projected ₹{s.projected:,.0f} vs ₹{s.assigned:,.0f} cap.",
            })
        elif used >= 80:
            items.append({
                "id": f"budget-{s.category}-{month}-80",
                "type": "budget", "severity": "info", "route": "budget",
                "anchor": s.category,
                "title": f"{s.category.title()} budget {used:.0f}% used",
                "body": f"₹{s.spent:,.0f} of ₹{s.assigned:,.0f} so far this month.",
            })
    return items


def _money_radar_notifications(findings) -> list[dict]:
    """Each NEW finding → a notification with id == dedup_key (client dedups)."""
    items: list[dict] = []
    for f in findings:
        if f.status != "new":
            continue
        items.append({
            "id": f.dedup_key,
            "type": "money_radar",
            "severity": "warning" if f.severity in ("security", "action", "warning") else "info",
            "route": "money_radar",
            "anchor": f.dedup_key,
            "title": f.title,
            "body": f.detail,
        })
    return items


def _hidden_money_digest(findings, today: date) -> dict | None:
    new_items = [f for f in findings if f.status == "new"]
    if not new_items:
        return None
    mandates = sum(1 for f in new_items if f.type == "mandate")
    dupes = sum(1 for f in new_items if f.type == "duplicate")
    markup = next((f.amount for f in new_items if f.type == "forex_markup"), None)
    parts = []
    if mandates:
        parts.append(f"{mandates} new mandate{'s' if mandates != 1 else ''}")
    if markup:
        parts.append(f"₹{markup:,.0f} forex markup")
    if dupes:
        parts.append(f"{dupes} possible duplicate{'s' if dupes != 1 else ''}")
    if not parts:
        parts.append(f"{len(new_items)} new finding{'s' if len(new_items) != 1 else ''}")
    iso_year, iso_week, _ = today.isocalendar()
    return {
        "id": f"radar-digest-{iso_year}-W{iso_week}",
        "type": "digest", "severity": "info", "route": "money_radar",
        "title": "Money Radar — this week",
        "body": " · ".join(parts) + ".",
    }


def _digest_notification(rows, today: date) -> dict | None:
    """A weekly spend digest, keyed by ISO week so it surfaces once per week."""
    week_start = today - timedelta(days=today.weekday())  # Monday
    week_rows = [r for r in rows if r["occurred_at"].date() >= week_start]
    if not week_rows:
        return None
    total = sum(r["amount"] for r in week_rows)
    by_cat: dict[str, float] = {}
    for r in week_rows:
        by_cat[r["category"] or "uncategorized"] = by_cat.get(r["category"] or "uncategorized", 0.0) + r["amount"]
    top_cat = max(by_cat.items(), key=lambda kv: kv[1])[0] if by_cat else None
    iso_year, iso_week, _ = today.isocalendar()
    return {
        "id": f"digest-{iso_year}-W{iso_week}",
        "type": "digest", "severity": "info", "route": "insights",
        "title": f"This week: ₹{total:,.0f} spent",
        "body": (f"Top category: {top_cat.title()}." if top_cat else "Your weekly spend summary."),
    }


_REWARD_EXPIRY_WINDOW_DAYS = 14


def _reward_notifications(cards, events, today: date) -> list[dict]:
    items: list[dict] = []
    horizon = today + timedelta(days=_REWARD_EXPIRY_WINDOW_DAYS)
    for c in cards:
        if c.expiring_points and c.expiring_date:
            try:
                exp = date.fromisoformat(c.expiring_date)
            except ValueError:
                continue
            if today <= exp <= horizon:
                days = (exp - today).days
                items.append({
                    "id": f"reward-expiry-{c.bank}-{c.last4}-{c.expiring_date}",
                    "type": "reward", "severity": "warning", "route": "rewards",
                    "anchor": c.last4 or None,
                    "title": f"{c.bank} points expiring soon",
                    "body": f"{c.expiring_points:,} points expire in {days} day"
                            f"{'' if days == 1 else 's'} ({c.expiring_date}).",
                })
    for e in events:
        if e.kind == "voucher" and e.voucher_expiry:
            try:
                exp = date.fromisoformat(e.voucher_expiry)
            except ValueError:
                continue
            if today <= exp <= horizon:
                items.append({
                    "id": f"reward-voucher-{e.bank}-{e.voucher_code}-{e.voucher_expiry}",
                    "type": "reward", "severity": "warning", "route": "rewards",
                    "anchor": e.last4 or None,
                    "title": "Voucher expiring soon",
                    "body": f"Voucher {e.voucher_code} expires {e.voucher_expiry}.",
                })
    return items


async def build_notifications(session: AsyncSession) -> list[dict]:
    """Assemble the current notification feed across bills, goals, budgets,
    spending nudges, and a weekly digest. Each item has a STABLE `id` so the
    client can post it once and dedupe on re-fetch. Stateless on the server."""
    from app.enrichment.zero_based import ZeroBasedBudgetService
    from app.repositories import (
        BudgetAssignmentRepository, CardBillRepository, CategoryRepository,
        FindingRepository, GoalRepository, MonthlyIncomeRepository,
        TransactionRepository, TransactionSplitRepository,
    )
    from app.routers.insights import insights_feed

    today = date.today()
    cur_month = today.strftime("%Y-%m")
    items: list[dict] = []

    # Bills due soon / overdue.
    bills = await CardBillRepository(session).list_unpaid()
    items += _bill_notifications(bills, today)

    # Goal milestones.
    current = sum((await networth_components(session)).values())
    goals = await GoalRepository(session).list_all()
    items += _goal_notifications(goals, current)

    # Budget pacing.
    try:
        bsvc = ZeroBasedBudgetService(
            income=MonthlyIncomeRepository(session),
            assignments=BudgetAssignmentRepository(session),
            transactions=TransactionRepository(session),
            splits=TransactionSplitRepository(session))
        names = {c.id: c.name for c in await CategoryRepository(session).list_all()}
        items += _budget_notifications(
            await bsvc.category_status(cur_month, names=names), cur_month)
    except Exception:
        pass

    # Spending nudges (reuse the insights feed) → notifications, keyed by month.
    feed = (await insights_feed(session=session)).get("insights", [])
    for card in feed:
        if card["type"] in ("big_ticket", "small_leak", "category_spike", "trend_up"):
            items.append({
                "id": f"nudge-{card['type']}-{cur_month}",
                "type": "spending", "severity": card["severity"], "route": "insights",
                "title": card["title"], "body": card["detail"],
            })

    # Weekly digest.
    start_30 = (today - timedelta(days=8)).isoformat()
    rows = await TransactionRepository(session).analytics_rows(
        datetime.fromisoformat(start_30),
        datetime.fromisoformat((today + timedelta(days=1)).isoformat()))
    digest = _digest_notification(rows, today)
    if digest:
        items.append(digest)

    # Money Radar findings → notifications + weekly hidden-money digest.
    radar = await FindingRepository(session).list_active()
    items += _money_radar_notifications(radar)
    radar_digest = _hidden_money_digest(radar, today)
    if radar_digest:
        items.append(radar_digest)

    # Reward points / voucher expiry.
    from app.repositories import CardRewardRepository, RewardEventRepository
    reward_cards = await CardRewardRepository(session).list_all()
    reward_events = await RewardEventRepository(session).list_recent(50)
    items += _reward_notifications(reward_cards, reward_events, today)

    return items
