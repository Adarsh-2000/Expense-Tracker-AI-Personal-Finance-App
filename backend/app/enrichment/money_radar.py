from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import datetime, timedelta
from statistics import mean, median

from app.enrichment.limit_scan import _LIMIT_RE as _AVL_LIMIT_RE
from app.parsing.events.declines import parse_decline
from app.parsing.events.emi import parse_emi
from app.parsing.events.mandates import parse_mandate
from app.parsing.events.refunds import parse_refund
from app.parsing.types import normalize_merchant


@dataclass
class FindingDraft:
    type: str
    severity: str
    title: str
    detail: str
    occurred_at: datetime
    dedup_key: str
    amount: float | None = None
    currency: str = "INR"
    evidence_kind: str | None = None
    evidence_ref: str | None = None
    query_json: str | None = None


def detect_mandates(messages, today) -> list[FindingDraft]:
    """Surface ACTIVE auto-pay mandates only. A mandate keyed by (merchant, amount)
    is treated as cancelled — and hidden — when a 'mandate revoked' message for the
    same key arrives at/after its latest creation (e.g. SafeGold, set up Feb 2023 and
    revoked May 2023, must not show as a live commitment)."""
    latest_created: dict[tuple, object] = {}
    latest_revoked: dict[tuple, object] = {}
    created_msg: dict[tuple, tuple] = {}
    for m in messages:
        r = parse_mandate(m.sender, m.body)
        if not r:
            continue
        key = (normalize_merchant(r["merchant"]), round(r["amount"]))
        if r["action"] == "created":
            if key not in latest_created or m.received_at >= latest_created[key]:
                latest_created[key] = m.received_at
                created_msg[key] = (m, r["merchant"], r["amount"])
        elif r["action"] == "revoked":
            if key not in latest_revoked or m.received_at > latest_revoked[key]:
                latest_revoked[key] = m.received_at

    out: list[FindingDraft] = []
    for key, created_at in latest_created.items():
        revoked_at = latest_revoked.get(key)
        if revoked_at is not None and revoked_at >= created_at:
            continue  # cancelled — not an active commitment
        m, merchant, amount = created_msg[key]
        out.append(FindingDraft(
            type="mandate", severity="action",
            title=f"{merchant} auto-pay set up · ₹{amount:,.0f}",
            detail="A recurring auto-debit mandate is active — make sure you still want it.",
            amount=amount, occurred_at=m.received_at,
            dedup_key=f"mandate:{normalize_merchant(merchant)}:{amount:.0f}",
            evidence_kind="raw_message", evidence_ref=str(m.id),
            query_json=json.dumps({"merchant": merchant})))
    return out


def detect_declines(messages, today) -> list[FindingDraft]:
    out: list[FindingDraft] = []
    for m in messages:
        r = parse_decline(m.sender, m.body)
        if not r:
            continue
        last4 = r["last4"] or "?"
        amt = f"₹{r['amount']:,.0f} " if r["amount"] else ""
        out.append(FindingDraft(
            type="declined", severity="security",
            title=f"{r['bank']} ••{last4} declined",
            detail=f"{amt}declined: {r['reason']}.",
            amount=r["amount"], occurred_at=m.received_at,
            dedup_key=f"declined:{r['bank'].lower()}:{last4}:{m.received_at.date().isoformat()}",
            evidence_kind="raw_message", evidence_ref=str(m.id)))
    return out


def detect_refunds(messages, today) -> list[FindingDraft]:
    labels = {"refund": "Refund received", "reversal": "Charge reversed",
              "credit_balance": "Credit balance owed back"}
    out: list[FindingDraft] = []
    for m in messages:
        r = parse_refund(m.sender, m.body)
        if not r:
            continue
        last4 = r["last4"] or "?"
        out.append(FindingDraft(
            type="refund", severity="positive",
            title=f"{labels[r['kind']]} · ₹{r['amount']:,.0f}",
            detail=("A credit balance is sitting on your card — you can sweep it back."
                    if r["kind"] == "credit_balance" else "Money came back to you."),
            amount=r["amount"], occurred_at=m.received_at,
            dedup_key=f"refund:{r['kind']}:{last4}:{r['amount']:.0f}:{m.received_at.date().isoformat()}",
            evidence_kind="raw_message", evidence_ref=str(m.id)))
    return out


def detect_emi(messages, today) -> list[FindingDraft]:
    out: list[FindingDraft] = []
    for m in messages:
        r = parse_emi(m.sender, m.body)
        if not r:
            continue
        tenure = f" over {r['tenure_months']} months" if r["tenure_months"] else ""
        out.append(FindingDraft(
            type="emi", severity="info",
            title=f"Converted to EMI · ₹{r['amount']:,.0f}",
            detail=f"A purchase was put on EMI{tenure} — EMIs carry interest you don't see up front.",
            amount=r["amount"], occurred_at=m.received_at,
            dedup_key=f"emi:{r['amount']:.0f}:{m.received_at.date().isoformat()}",
            evidence_kind="raw_message", evidence_ref=str(m.id)))
    return out


_MOVEMENT = ("upi transfer", "atm", "cash", "neft", "imps", "rtgs", "cred",
             "credit card payment", "card payment", "bill pay")


def _is_movement(merchant: str) -> bool:
    m = (merchant or "").lower()
    return any(k in m for k in _MOVEMENT)


from app.enrichment.taxonomy import NON_CONSUMPTION  # legacy + 16-cat aware


def _is_consumption(r: dict) -> bool:
    return (r.get("category") or "") not in NON_CONSUMPTION


def detect_price_hikes(rows, today) -> list[FindingDraft]:
    groups: dict[str, list[dict]] = {}
    for r in rows:
        if _is_movement(r["merchant"]):
            continue
        if not _is_consumption(r):
            continue
        key = normalize_merchant(r["merchant"] or "")
        if key:
            groups.setdefault(key, []).append(r)
    out: list[FindingDraft] = []
    for items in groups.values():
        if len(items) < 4:
            continue
        items.sort(key=lambda r: r["occurred_at"])
        latest = items[-1]
        prior = items[:-1]
        prior_amts = [r["amount"] for r in prior]
        base = median(prior_amts)
        if base <= 0:
            continue
        spread = (max(prior_amts) - min(prior_amts)) / base
        if spread > 0.35:          # variable-amount payee, not a subscription
            continue
        if latest["amount"] <= base * 1.15:
            continue
        pct = round((latest["amount"] / base - 1) * 100)
        m = latest["merchant"]
        out.append(FindingDraft(
            type="price_hike", severity="warning",
            title=f"{m} price went up {pct}%",
            detail=f"Now ₹{latest['amount']:,.0f} vs ₹{base:,.0f} before.",
            amount=latest["amount"], occurred_at=latest["occurred_at"],
            dedup_key=f"pricehike:{normalize_merchant(m)}:{latest['occurred_at'].strftime('%Y-%m')}",
            query_json=json.dumps({"merchant": m})))
    return out


def detect_duplicates(rows, today, *, window_hours: int = 12) -> list[FindingDraft]:
    groups: dict[tuple, list[dict]] = {}
    for r in rows:
        if _is_movement(r["merchant"]):
            continue
        if not _is_consumption(r):
            continue
        key = (normalize_merchant(r["merchant"] or ""), round(r["amount"]))
        groups.setdefault(key, []).append(r)
    out: list[FindingDraft] = []
    for (mkey, amt), items in groups.items():
        if not mkey or len(items) < 2:
            continue
        items.sort(key=lambda r: r["occurred_at"])
        clusters: list[list[dict]] = []
        current = [items[0]]
        for r in items[1:]:
            if (r["occurred_at"] - current[-1]["occurred_at"]) <= timedelta(hours=window_hours):
                current.append(r)
            else:
                clusters.append(current)
                current = [r]
        clusters.append(current)
        for cluster in clusters:
            if len(cluster) < 2:
                continue
            first = cluster[0]
            out.append(FindingDraft(
                type="duplicate", severity="warning",
                title=f"{first['merchant']} ₹{first['amount']:,.0f} ×{len(cluster)}",
                detail=f"Charged {len(cluster)} times within {window_hours}h — check for a double-charge.",
                amount=first["amount"], occurred_at=first["occurred_at"],
                dedup_key=f"dup:{mkey}:{amt}:{first['occurred_at'].date().isoformat()}",
                query_json=json.dumps({"merchant": first["merchant"]})))
    return out


def detect_large_charges(rows, today, *, floor: float = 50000.0) -> list[FindingDraft]:
    by_merchant: dict[str, list[float]] = {}
    for r in rows:
        if _is_movement(r["merchant"]): continue
        if not _is_consumption(r): continue
        by_merchant.setdefault(normalize_merchant(r["merchant"] or ""), []).append(r["amount"])
    out: list[FindingDraft] = []
    for r in rows:
        if _is_movement(r["merchant"]):
            continue
        if not _is_consumption(r):
            continue
        amounts = by_merchant.get(normalize_merchant(r["merchant"] or ""), [r["amount"]])
        med = median(amounts)
        # Flag if it crosses the absolute floor, OR if it is an outlier vs the
        # merchant's own history (4× the median).  When there is only one data
        # point the median equals the charge itself, so we fall back to the
        # floor-only check to avoid the condition never triggering.
        threshold = floor if len(amounts) == 1 else max(floor, 4 * med)
        if r["amount"] >= threshold:
            out.append(FindingDraft(
                type="large_charge", severity="info",
                title=f"Large charge · ₹{r['amount']:,.0f} at {r['merchant']}",
                detail="One of your biggest single charges — make sure it's expected.",
                amount=r["amount"], occurred_at=r["occurred_at"],
                dedup_key=f"large:{normalize_merchant(r['merchant'] or '')}:{r['amount']:.0f}:{r['occurred_at'].date().isoformat()}",
                query_json=json.dumps({"merchant": r["merchant"]})))
    return out


def detect_forgotten_recurring(rows, today) -> list[FindingDraft]:
    groups: dict[str, list[dict]] = {}
    for r in rows:
        if _is_movement(r["merchant"]):
            continue
        if not _is_consumption(r):
            continue
        key = normalize_merchant(r["merchant"] or "")
        if key:
            groups.setdefault(key, []).append(r)
    out: list[FindingDraft] = []
    for items in groups.values():
        if len(items) < 2:
            continue
        items.sort(key=lambda r: r["occurred_at"])
        gaps = [(items[i]["occurred_at"] - items[i - 1]["occurred_at"]).days
                for i in range(1, len(items))]
        cadence = mean(gaps)
        if not (300 <= cadence <= 400):     # ~annual; the 30-day detector can't see these
            continue
        last = items[-1]
        if last["amount"] < 500:
            continue
        out.append(FindingDraft(
            type="forgotten_recurring", severity="info",
            title=f"Yearly charge: {last['merchant']} · ₹{last['amount']:,.0f}",
            detail=f"Recurs about once a year ({round(cadence)}d) — easy to forget.",
            amount=last["amount"], occurred_at=last["occurred_at"],
            dedup_key=f"forgotten:{normalize_merchant(last['merchant'] or '')}",
            query_json=json.dumps({"merchant": last["merchant"]})))
    return out


_FOREX_MARKUP_RATE = 0.035   # typical bank forex markup
_GST_RATE = 0.18             # GST charged on the markup


def detect_forex_markup(foreign_charges, today) -> list[FindingDraft]:
    if not foreign_charges:
        return []
    latest = max(r["occurred_at"] for r in foreign_charges)
    total_inr = sum(r["amount"] for r in foreign_charges)
    markup = total_inr * _FOREX_MARKUP_RATE
    markup_with_gst = markup * (1 + _GST_RATE)
    year = today.strftime("%Y")
    return [FindingDraft(
        type="forex_markup", severity="info",
        title=f"~₹{markup_with_gst:,.0f} hidden forex markup",
        detail=(f"₹{total_inr:,.0f} of foreign charges this year carry an estimated "
                f"~3.5% markup + GST your bank never itemises."),
        amount=round(markup_with_gst, 2), occurred_at=latest,
        dedup_key=f"forex_markup:{year}")]


def detect_card_costs(bills, card_rows, today, *, cycle_days: int = 38) -> list[FindingDraft]:
    out: list[FindingDraft] = []
    for b in bills:
        cycle_start = b.received_at - timedelta(days=cycle_days)
        captured = sum(
            r["amount"] for r in card_rows
            if r.get("account_type") == "card" and r.get("bank") == b.bank
            and r.get("last4") == b.last4
            and cycle_start <= r["occurred_at"] <= b.received_at)
        gap = b.total_due - captured
        # Only flag a material gap (fees/interest/GST), not rounding/timing noise.
        if gap < 200 or gap < b.total_due * 0.02 or gap > b.total_due * 0.4:
            continue
        out.append(FindingDraft(
            type="card_costs", severity="info",
            title=f"~₹{gap:,.0f} fees/interest on {b.bank} ••{b.last4}",
            detail=(f"Your statement was ₹{b.total_due:,.0f} but we only see "
                    f"₹{captured:,.0f} of purchases — the rest is likely fees, "
                    f"interest or GST (estimate)."),
            amount=round(gap, 2), occurred_at=b.received_at,
            dedup_key=f"card_costs:{b.bank.lower()}:{b.last4}:{b.received_at.strftime('%Y-%m')}"))
    return out


def detect_high_utilization(messages, accounts, today) -> list[FindingDraft]:
    """High credit-utilization findings per card. Sanctioned limit = account
    credit_limit override else max observed Avl Limit; available = latest Avl Limit."""
    drafts: list[FindingDraft] = []
    overrides = {(a.last4 or ""): a.credit_limit for a in accounts
                 if a.type == "card" and a.last4}
    latest: dict[str, float] = {}
    maxima: dict[str, float] = {}
    for m in sorted(messages, key=lambda mm: mm.received_at or today, reverse=True):
        body = m.body or ""
        lm = _AVL_LIMIT_RE.search(body)
        if not lm:
            continue
        cm = re.search(r"X{2,}(\d{4})", body)
        if not cm:
            continue
        last4 = cm.group(1)
        val = float(lm.group(1).replace(",", ""))
        latest.setdefault(last4, val)
        maxima[last4] = max(maxima.get(last4, 0.0), val)
    for last4, available in latest.items():
        total = overrides.get(last4) or maxima.get(last4)
        if not total or total <= 0:
            continue
        pct = (total - available) / total * 100.0
        bucket = 50 if pct >= 50 else (30 if pct >= 30 else 0)
        if bucket == 0:
            continue
        severity = "action" if bucket == 50 else "warning"
        used = total - available
        drafts.append(FindingDraft(
            type="utilization", severity=severity,
            title=f"Card ••{last4} is {pct:.0f}% utilized",
            detail=(f"₹{used:,.0f} of ₹{total:,.0f} used — high utilization can hurt "
                    "your credit score. Aim to stay under 30%."),
            occurred_at=today,
            dedup_key=f"util:{last4}:{bucket}",
        ))
    return drafts


from sqlalchemy.ext.asyncio import AsyncSession


class MoneyRadarService:
    """Runs every detector over the user's data and upserts findings (idempotent)."""

    def __init__(self, session: AsyncSession):
        self.session = session

    async def scan(self) -> int:
        from app.config import settings
        from app.repositories import (
            CardBillRepository, FindingRepository, RawMessageRepository,
            TransactionRepository,
        )

        today = datetime.now()
        start = datetime(2000, 1, 1)
        end = today + timedelta(days=1)

        txns = TransactionRepository(self.session)
        rows = await txns.analytics_rows(start, end)
        foreign = await txns.foreign_charges(start, end)
        bills = await CardBillRepository(self.session).list_all()

        msg_repo = RawMessageRepository(self.session)
        messages = (await msg_repo.list_by_status("needs_review")
                    + await msg_repo.list_by_status("parsed"))

        drafts: list[FindingDraft] = []
        drafts += detect_mandates(messages, today)
        drafts += detect_declines(messages, today)
        drafts += detect_refunds(messages, today)
        drafts += detect_emi(messages, today)
        drafts += detect_price_hikes(rows, today)
        drafts += detect_duplicates(rows, today, window_hours=settings.dedup_amount_window_hours)
        drafts += detect_large_charges(rows, today)
        drafts += detect_forgotten_recurring(rows, today)
        drafts += detect_forex_markup(foreign, today)
        drafts += detect_card_costs(bills, rows, today)
        from app.repositories import AccountRepository as _AcctRepo
        _accounts = await _AcctRepo(self.session).list_all()
        drafts += detect_high_utilization(messages, _accounts, today)

        repo = FindingRepository(self.session)
        for d in drafts:
            await repo.upsert_by_dedup_key(d)

        # Cross-link mandates → recurring series so they also show in Save More.
        from app.repositories import AccountRepository, RecurringSeriesRepository
        accounts = await AccountRepository(self.session).list_all()
        default_account_id = accounts[0].id if accounts else None
        if default_account_id is not None:
            series_repo = RecurringSeriesRepository(self.session)
            for d in drafts:
                if d.type != "mandate":
                    continue
                if not d.query_json:
                    continue
                merchant = json.loads(d.query_json)["merchant"]
                await series_repo.upsert(
                    merchant=merchant, account_id=default_account_id,
                    expected_amount=d.amount or 0.0, last_seen_at=d.occurred_at)

        return len(drafts)
