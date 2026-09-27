from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

import pytest
from sqlalchemy import select

from app.entities import Finding
from app.repositories import FindingRepository


@pytest.mark.asyncio
async def test_finding_persists_and_reads_back(session):
    session.add(Finding(
        type="mandate", severity="action", title="Spotify AutoPay set up",
        detail="₹119/mo", amount=119.0, occurred_at=datetime(2026, 4, 15),
        dedup_key="mandate:spotify:119"))
    await session.commit()
    row = (await session.execute(select(Finding))).scalar_one()
    assert row.dedup_key == "mandate:spotify:119"
    assert row.status == "new"          # default
    assert row.currency == "INR"        # default


@dataclass
class _Draft:
    type: str = "duplicate"
    severity: str = "warning"
    title: str = "ANTHROPIC ₹2,537 ×5"
    detail: str = "Charged 5 times in 48h"
    occurred_at: datetime = datetime(2026, 6, 1)
    dedup_key: str = "dup:anthropic:2537:2026-06-01"
    amount: float | None = 2537.0
    currency: str = "INR"
    evidence_kind: str | None = None
    evidence_ref: str | None = None
    query_json: str | None = None


@pytest.mark.asyncio
async def test_upsert_is_idempotent_and_preserves_dismissed(session):
    repo = FindingRepository(session)
    await repo.upsert_by_dedup_key(_Draft())
    await session.commit()
    assert await repo.count_new() == 1

    # Dismiss, then re-scan (upsert) the same finding → stays dismissed, not re-new.
    findings = await repo.list_active()
    await repo.dismiss(findings[0].id)
    await session.commit()
    await repo.upsert_by_dedup_key(_Draft(detail="updated copy"))
    await session.commit()

    assert await repo.count_new() == 0                      # not resurrected
    active = await repo.list_active()                       # dismissed excluded
    assert active == []
    all_rows = await repo.list_active(include_dismissed=True)
    assert all_rows[0].detail == "updated copy"             # display fields refreshed


@pytest.mark.asyncio
async def test_list_active_sorts_by_severity_then_amount(session):
    repo = FindingRepository(session)
    await repo.upsert_by_dedup_key(_Draft(dedup_key="a", severity="info", amount=10.0))
    await repo.upsert_by_dedup_key(_Draft(dedup_key="b", severity="security", amount=1.0))
    await session.commit()
    ordered = [f.dedup_key for f in await repo.list_active()]
    assert ordered == ["b", "a"]   # security ranks above info regardless of amount


from app.enrichment.money_radar import FindingDraft


def test_finding_draft_defaults():
    d = FindingDraft(type="mandate", severity="action", title="t", detail="d",
                     occurred_at=datetime(2026, 4, 15), dedup_key="k")
    assert d.amount is None
    assert d.currency == "INR"


from types import SimpleNamespace

from app.enrichment.money_radar import (
    detect_declines, detect_emi, detect_mandates, detect_refunds,
)


def _msg(body, mid=1, sender="AXISBK", at=datetime(2026, 4, 15)):
    return SimpleNamespace(id=mid, sender=sender, body=body, received_at=at)


def test_detect_mandates_creates_action_finding():
    msgs = [_msg("UPI mandate set up for Spotify India for INR 119.00. Not you?")]
    drafts = detect_mandates(msgs, datetime(2026, 6, 1))
    assert len(drafts) == 1
    assert drafts[0].type == "mandate"
    assert drafts[0].severity == "action"
    assert drafts[0].amount == 119.0
    assert drafts[0].dedup_key == "mandate:spotify india:119"


def test_detect_mandates_revoke_excluded():
    msgs = [_msg("Your UPI mandate has been successfully revoked towards Jiocinema for INR 29.00")]
    assert detect_mandates(msgs, datetime(2026, 6, 1)) == []


def test_detect_declines_is_security():
    msgs = [_msg("Txn on Axis Bank Credit Card XX9966 is declined as you have exhausted shared limit across all your cards.")]
    drafts = detect_declines(msgs, datetime(2026, 6, 1))
    assert drafts[0].severity == "security"
    assert "9966" in drafts[0].dedup_key


def test_detect_refunds_is_positive():
    msgs = [_msg("You have a credit balance of INR 20430.6/- on your Axis Bank Credit Card no. XX6978.")]
    drafts = detect_refunds(msgs, datetime(2026, 6, 1))
    assert drafts[0].severity == "positive"
    assert drafts[0].amount == 20430.6


def test_detect_emi():
    msgs = [_msg("Your purchase of INR 24000.00 on Card XX0684 has been converted to EMI of 6 months")]
    drafts = detect_emi(msgs, datetime(2026, 6, 1))
    assert drafts[0].type == "emi"
    assert drafts[0].amount == 24000.0


from app.enrichment.money_radar import (
    detect_duplicates, detect_forgotten_recurring, detect_large_charges,
    detect_price_hikes,
)


def _row(merchant, amount, when, category="subscriptions", at="card"):
    return {"merchant": merchant, "amount": float(amount), "occurred_at": when,
            "account_type": at, "last4": "0684", "bank": "IDFC", "category": category}


def test_price_hike_detects_step_up():
    rows = [_row("SPOTIFY SI", 59, datetime(2026, 1, 10)),
            _row("SPOTIFY SI", 59, datetime(2026, 2, 10)),
            _row("SPOTIFY SI", 59, datetime(2026, 3, 10)),
            _row("SPOTIFY SI", 119, datetime(2026, 4, 10))]
    drafts = detect_price_hikes(rows, datetime(2026, 6, 1))
    assert len(drafts) == 1
    assert drafts[0].type == "price_hike"
    assert "59" in drafts[0].detail and "119" in drafts[0].detail


def test_duplicate_same_amount_within_window():
    rows = [_row("ANTHROPIC", 2537, datetime(2026, 6, 1, 9, 0)),
            _row("ANTHROPIC", 2537, datetime(2026, 6, 1, 18, 0))]
    drafts = detect_duplicates(rows, datetime(2026, 6, 3), window_hours=24)
    assert len(drafts) == 1
    assert drafts[0].type == "duplicate"


def test_duplicate_outside_window_ignored():
    rows = [_row("ANTHROPIC", 2537, datetime(2026, 6, 1)),
            _row("ANTHROPIC", 2537, datetime(2026, 6, 10))]
    assert detect_duplicates(rows, datetime(2026, 6, 11), window_hours=24) == []


def test_large_charge_above_floor():
    rows = [_row("CROMA", 95597, datetime(2026, 4, 11), category="shopping")]
    drafts = detect_large_charges(rows, datetime(2026, 6, 1), floor=50000)
    assert drafts[0].type == "large_charge"
    assert drafts[0].amount == 95597


def test_large_charge_below_floor_ignored():
    rows = [_row("CROMA", 4000, datetime(2026, 4, 11), category="shopping")]
    assert detect_large_charges(rows, datetime(2026, 6, 1), floor=50000) == []


def test_forgotten_recurring_annual_cadence():
    rows = [_row("LIC PREMIUM", 16844, datetime(2024, 6, 14), category="bills"),
            _row("LIC PREMIUM", 16844, datetime(2025, 6, 14), category="bills"),
            _row("LIC PREMIUM", 16844, datetime(2026, 6, 14), category="bills")]
    drafts = detect_forgotten_recurring(rows, datetime(2026, 6, 20))
    assert drafts[0].type == "forgotten_recurring"


from app.enrichment.money_radar import detect_card_costs, detect_forex_markup


def test_forex_markup_aggregates_and_estimates():
    foreign = [
        {"merchant": "ANTHROPIC", "amount": 2537.0, "original_amount": 29.5,
         "currency": "USD", "occurred_at": datetime(2026, 5, 26)},
        {"merchant": "CLAUDE AI", "amount": 2030.0, "original_amount": 23.6,
         "currency": "USD", "occurred_at": datetime(2026, 5, 31)},
    ]
    drafts = detect_forex_markup(foreign, datetime(2026, 6, 1))
    assert len(drafts) == 1
    d = drafts[0]
    assert d.type == "forex_markup"
    # markup ≈ 3.5% of 4567 + 18% GST on that markup
    assert 180 <= d.amount <= 210
    assert "4,567" in d.detail or "4567" in d.detail


def test_forex_markup_empty_when_no_foreign():
    assert detect_forex_markup([], datetime(2026, 6, 1)) == []


def test_card_costs_flags_gap_between_bill_and_purchases():
    # Statement says 10000 due; we only captured 9000 of purchases → ~1000 hidden.
    bills = [SimpleNamespace(bank="AXIS", last4="9966", total_due=10000.0,
                             received_at=datetime(2026, 5, 20))]
    rows = [{"merchant": "STORE", "amount": 9000.0, "occurred_at": datetime(2026, 5, 10),
             "account_type": "card", "last4": "9966", "bank": "AXIS", "category": "shopping"}]
    drafts = detect_card_costs(bills, rows, datetime(2026, 6, 1))
    assert drafts[0].type == "card_costs"
    assert 950 <= drafts[0].amount <= 1050


def test_card_costs_no_gap_no_finding():
    bills = [SimpleNamespace(bank="AXIS", last4="9966", total_due=9000.0,
                             received_at=datetime(2026, 5, 20))]
    rows = [{"merchant": "STORE", "amount": 9000.0, "occurred_at": datetime(2026, 5, 10),
             "account_type": "card", "last4": "9966", "bank": "AXIS", "category": "shopping"}]
    assert detect_card_costs(bills, rows, datetime(2026, 6, 1)) == []


def test_price_hike_ignores_variable_payee():
    rows = [_row("RANDOM PERSON", 100, datetime(2026, 1, 1), category="transfers"),
            _row("RANDOM PERSON", 5000, datetime(2026, 2, 1), category="transfers"),
            _row("RANDOM PERSON", 200, datetime(2026, 3, 1), category="transfers"),
            _row("RANDOM PERSON", 9000, datetime(2026, 4, 1), category="transfers")]
    assert detect_price_hikes(rows, datetime(2026, 6, 1)) == []


def test_duplicate_ignores_transfers():
    rows = [_row("FRIEND", 20000, datetime(2026, 6, 1, 9), category="transfers"),
            _row("FRIEND", 20000, datetime(2026, 6, 1, 18), category="transfers")]
    assert detect_duplicates(rows, datetime(2026, 6, 3), window_hours=24) == []


def test_large_charge_ignores_transfers():
    rows = [_row("UPI 12345", 98000, datetime(2026, 4, 11), category="transfers")]
    assert detect_large_charges(rows, datetime(2026, 6, 1), floor=50000) == []


from app.entities import Account, RawMessage, Transaction
from app.enrichment.money_radar import MoneyRadarService


@pytest.mark.asyncio
async def test_scan_creates_findings_and_is_idempotent(session):
    acc = Account(bank="AXIS", type="bank", last4="4400")
    session.add(acc)
    await session.flush()
    session.add(RawMessage(
        channel="sms", sender="AXISBK", parsed_status="needs_review",
        body="UPI mandate set up for Spotify India for INR 119.00. Not you?",
        received_at=datetime(2026, 4, 15)))
    await session.commit()

    svc = MoneyRadarService(session)
    await svc.scan()
    await session.commit()
    repo = FindingRepository(session)
    first = len(await repo.list_active())
    assert first >= 1
    assert any(f.type == "mandate" for f in await repo.list_active())

    await svc.scan()                 # second pass must not duplicate
    await session.commit()
    assert len(await repo.list_active()) == first


from app.entities import RecurringSeries
from app.repositories import RecurringSeriesRepository


@pytest.mark.asyncio
async def test_mandate_scan_creates_recurring_series(session):
    acc = Account(bank="AXIS", type="bank", last4="4400")
    session.add(acc)
    await session.flush()
    session.add(RawMessage(
        channel="sms", sender="AXISBK", parsed_status="needs_review",
        body="UPI mandate set up for Spotify India for INR 119.00. Not you?",
        received_at=datetime(2026, 4, 15)))
    await session.commit()

    await MoneyRadarService(session).scan()
    await session.commit()

    series = await RecurringSeriesRepository(session).list_all()
    assert any("spotify" in s.merchant.lower() for s in series)

    # Idempotent: a second scan does not add a duplicate series.
    before = len(series)
    await MoneyRadarService(session).scan()
    await session.commit()
    assert len(await RecurringSeriesRepository(session).list_all()) == before


def test_detect_mandates_hides_revoked():
    # SafeGold: created, then revoked later → not an active commitment.
    msgs = [
        _msg("You have successfully created a mandate on SAFE GOLD for a frequency of "
             "ASPRESENTED starting from 2023-02-28 for amount 1000.00 -BOB",
             mid=1, at=datetime(2023, 2, 28)),
        _msg("You have successfully revoked a mandate on SAFE GOLD for a frequency of "
             "ASPRESENTED starting from 2023-02-28 for amount 1000.00 -BOB",
             mid=2, at=datetime(2023, 5, 14)),
    ]
    assert detect_mandates(msgs, datetime(2026, 6, 1)) == []


def test_detect_mandates_keeps_resubscribed_after_revoke():
    # Revoked, then created again later → active again, should show.
    msgs = [
        _msg("mandate set up for Spotify India for INR 119.00", mid=1, at=datetime(2025, 1, 1)),
        _msg("Your UPI mandate has been successfully revoked towards Spotify India for INR 119.00",
             mid=2, at=datetime(2025, 2, 1)),
        _msg("mandate set up for Spotify India for INR 119.00", mid=3, at=datetime(2025, 3, 1)),
    ]
    drafts = detect_mandates(msgs, datetime(2026, 6, 1))
    assert len(drafts) == 1 and drafts[0].amount == 119.0
