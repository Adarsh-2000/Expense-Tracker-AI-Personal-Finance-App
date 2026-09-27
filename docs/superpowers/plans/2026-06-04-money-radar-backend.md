# Money Radar — Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect "hidden money" (auto-pay mandates, forex markup, hidden card costs, duplicates, refunds, declines, EMI, price hikes, large charges, forgotten recurring) from already-parsed SMS/email data and expose it through a stateful, idempotent REST API + the existing notification feed.

**Architecture:** A persisted `Finding` entity (upserted by stable `dedup_key`) backs a `MoneyRadarService.scan()` detector engine. Event parsers turn previously-discarded `needs_review` messages into findings; statistical/aggregate detectors run over transactions and card bills. The notification feed emits each finding as a stable-id item so the existing client-dedup pipeline alerts once.

**Tech Stack:** FastAPI (async), SQLAlchemy 2 async, Pydantic v2, pytest + pytest-asyncio + httpx, SQLite in-memory for tests.

**Spec:** `docs/superpowers/specs/2026-06-04-money-radar-design.md`

---

## File Structure

**Create:**
- `app/parsing/events/__init__.py` — package marker
- `app/parsing/events/mandates.py` — parse UPI-mandate created/revoked messages
- `app/parsing/events/declines.py` — parse declined/blocked/limit-exhausted messages
- `app/parsing/events/refunds.py` — parse refund/reversal/credit-balance messages
- `app/parsing/events/emi.py` — parse EMI-conversion confirmations
- `app/enrichment/money_radar.py` — `FindingDraft`, the 10 detectors, `MoneyRadarService`
- `app/routers/money_radar.py` — REST endpoints
- `tests/test_parse_events.py` — parser unit tests
- `tests/test_money_radar.py` — detector + service unit tests
- `tests/test_api_money_radar.py` — API tests

**Modify:**
- `app/entities.py` — add `Finding`
- `app/repositories.py` — add `FindingRepository`; add `TransactionRepository.foreign_charges()`; add `RecurringSeriesRepository`
- `app/schemas.py` — add `FindingOut`, `MoneyRadarResponse`, `RadarSummary`
- `app/main.py:67` — register `money_radar.router`
- `app/services/notifications.py` — emit findings as notifications + weekly hidden-money digest
- `app/routers/sync.py` — call `MoneyRadarService.scan()` after Gmail sync
- `app/routers/transactions.py` (ingest_sms) — call scan after SMS ingest

---

## Phase A — Persistence

### Task 1: `Finding` entity

**Files:**
- Modify: `app/entities.py` (append after `CardBill`)
- Test: `tests/test_money_radar.py`

- [ ] **Step 1: Write the failing test**

Create `tests/test_money_radar.py`:
```python
from __future__ import annotations

from datetime import datetime

import pytest
from sqlalchemy import select

from app.entities import Finding


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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv/bin/python -m pytest tests/test_money_radar.py::test_finding_persists_and_reads_back -v`
Expected: FAIL with `ImportError: cannot import name 'Finding'`.

- [ ] **Step 3: Write minimal implementation**

Append to `app/entities.py` (imports `Boolean, DateTime, Float, String` already present at top):
```python
class Finding(Base):
    __tablename__ = "findings"

    id: Mapped[int] = mapped_column(primary_key=True)
    type: Mapped[str] = mapped_column(String(32))
    severity: Mapped[str] = mapped_column(String(16))
    title: Mapped[str] = mapped_column(String(256))
    detail: Mapped[str] = mapped_column(String(512))
    amount: Mapped[float | None] = mapped_column(Float, nullable=True)
    currency: Mapped[str] = mapped_column(String(8), default="INR")
    occurred_at: Mapped[datetime] = mapped_column(DateTime)
    detected_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)
    dedup_key: Mapped[str] = mapped_column(String(256), unique=True, index=True)
    status: Mapped[str] = mapped_column(String(16), default="new")  # new|seen|dismissed|resolved
    evidence_kind: Mapped[str | None] = mapped_column(String(16), nullable=True)
    evidence_ref: Mapped[str | None] = mapped_column(String(256), nullable=True)
    query_json: Mapped[str | None] = mapped_column(String(512), nullable=True)
```

> Note: the app uses `Base.metadata.create_all` on startup (`app/main.py:24`) — no migration needed; the `findings` table is created automatically.

- [ ] **Step 4: Run test to verify it passes**

Run: `.venv/bin/python -m pytest tests/test_money_radar.py::test_finding_persists_and_reads_back -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/entities.py tests/test_money_radar.py
git commit -m "feat(money-radar): add Finding entity"
```

---

### Task 2: `FindingRepository`

**Files:**
- Modify: `app/repositories.py` (append a new class; `select`, `func` already imported at top)
- Test: `tests/test_money_radar.py`

- [ ] **Step 1: Write the failing test**

Append to `tests/test_money_radar.py`:
```python
from dataclasses import dataclass

from app.repositories import FindingRepository


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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv/bin/python -m pytest tests/test_money_radar.py -k upsert -v`
Expected: FAIL with `ImportError: cannot import name 'FindingRepository'`.

- [ ] **Step 3: Write minimal implementation**

Append to `app/repositories.py`:
```python
class FindingRepository:
    _SEVERITY_RANK = {"security": 0, "action": 1, "warning": 2, "positive": 3, "info": 4}

    def __init__(self, session: AsyncSession):
        self.session = session

    async def upsert_by_dedup_key(self, draft):
        from app.entities import Finding
        existing = (await self.session.execute(
            select(Finding).where(Finding.dedup_key == draft.dedup_key))).scalar_one_or_none()
        if existing is not None:
            # Refresh display fields; never touch `status` (a dismissed finding stays dismissed).
            existing.type = draft.type
            existing.severity = draft.severity
            existing.title = draft.title
            existing.detail = draft.detail
            existing.amount = draft.amount
            existing.currency = draft.currency
            existing.occurred_at = draft.occurred_at
            existing.evidence_kind = draft.evidence_kind
            existing.evidence_ref = draft.evidence_ref
            existing.query_json = draft.query_json
            return existing
        finding = Finding(
            type=draft.type, severity=draft.severity, title=draft.title,
            detail=draft.detail, amount=draft.amount, currency=draft.currency,
            occurred_at=draft.occurred_at, dedup_key=draft.dedup_key, status="new",
            evidence_kind=draft.evidence_kind, evidence_ref=draft.evidence_ref,
            query_json=draft.query_json)
        self.session.add(finding)
        return finding

    async def list_active(self, *, include_dismissed: bool = False) -> list:
        from app.entities import Finding
        stmt = select(Finding)
        if not include_dismissed:
            stmt = stmt.where(Finding.status != "dismissed")
        rows = list((await self.session.execute(stmt)).scalars())
        rows.sort(key=lambda f: (self._SEVERITY_RANK.get(f.severity, 9), -(f.amount or 0.0)))
        return rows

    async def count_new(self) -> int:
        from app.entities import Finding
        return int((await self.session.execute(
            select(func.count()).select_from(Finding).where(Finding.status == "new"))).scalar_one())

    async def mark_all_seen(self) -> None:
        from sqlalchemy import update
        from app.entities import Finding
        await self.session.execute(
            update(Finding).where(Finding.status == "new").values(status="seen"))

    async def dismiss(self, finding_id: int) -> bool:
        from app.entities import Finding
        f = await self.session.get(Finding, finding_id)
        if f is None:
            return False
        f.status = "dismissed"
        return True
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.venv/bin/python -m pytest tests/test_money_radar.py -k "upsert or sorts" -v`
Expected: PASS (both tests)

- [ ] **Step 5: Commit**

```bash
git add app/repositories.py tests/test_money_radar.py
git commit -m "feat(money-radar): add FindingRepository with idempotent upsert"
```

---

## Phase B — Event parsers

Each parser takes `(sender: str, body: str)` and returns a `dict | None`. They mirror the style of `app/parsing/bills.py`. Test bodies are real redacted messages from `emu.db`.

### Task 3: mandate parser

**Files:**
- Create: `app/parsing/events/__init__.py` (empty), `app/parsing/events/mandates.py`
- Test: `tests/test_parse_events.py`

- [ ] **Step 1: Write the failing test**

Create `tests/test_parse_events.py`:
```python
from app.parsing.events.mandates import parse_mandate


def test_mandate_setup_simple():
    r = parse_mandate("AXISBK", "UPI mandate set up for Spotify India for INR 119.00. Not you? Call us")
    assert r == {"merchant": "Spotify India", "amount": 119.0, "action": "created"}


def test_mandate_created_with_dates():
    r = parse_mandate("AXISBK",
        "Your UPI ASPRESENTED mandate has been successfully created towards Google Play "
        "from 15-04-25 to 31-12-35 for INR 119.00 - Axis Bank")
    assert r["merchant"] == "Google Play"
    assert r["amount"] == 119.0
    assert r["action"] == "created"


def test_mandate_bob_on_merchant_for_amount():
    r = parse_mandate("BOBTXN",
        "You have successfully created a mandate on NETFLIX COM for a frequency of "
        "ASPRESENTED starting from 2025-04-15 12:00:00 AM for amount 59.00 -BOB")
    assert r["merchant"] == "NETFLIX COM"
    assert r["amount"] == 59.0


def test_mandate_revoked():
    r = parse_mandate("AXISBK", "Your UPI mandate has been successfully revoked towards Jiocinema for INR 29.00 - Axis Bank")
    assert r["action"] == "revoked"
    assert r["merchant"] == "Jiocinema"


def test_mandate_rs_with_paise():
    r = parse_mandate("BOBTXN", "Your UPI-Mandate is successfully created towards Garuda Construction for Rs. 14915.00. Funds are blocked")
    assert r["merchant"] == "Garuda Construction"
    assert r["amount"] == 14915.0


def test_non_mandate_returns_none():
    assert parse_mandate("AXISBK", "Rs 181.00 debited from A/C XX2404 to amazonpay") is None
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv/bin/python -m pytest tests/test_parse_events.py -k mandate -v`
Expected: FAIL with `ModuleNotFoundError: app.parsing.events.mandates`.

- [ ] **Step 3: Write minimal implementation**

Create `app/parsing/events/__init__.py` (empty file).
Create `app/parsing/events/mandates.py`:
```python
from __future__ import annotations

import re

_MERCHANT = re.compile(
    r"(?:towards|set up for|mandate on)\s+(.+?)"
    r"(?:\s+(?:from|for a frequency|for INR|for Rs|for amount|for ₹|for|starting)|\.|,)",
    re.IGNORECASE)
_AMOUNT = re.compile(r"(?:for\s+(?:INR|Rs\.?|₹)|amount)\s*([\d,]+\.?\d*)", re.IGNORECASE)


def parse_mandate(sender: str, body: str) -> dict | None:
    low = body.lower()
    if "mandate" not in low and "autopay" not in low:
        return None
    if not any(k in low for k in ("created", "set up", "set-up", "revoked")):
        return None
    m = _MERCHANT.search(body)
    a = _AMOUNT.search(body)
    if not m or not a:
        return None
    return {
        "merchant": m.group(1).strip(),
        "amount": float(a.group(1).replace(",", "")),
        "action": "revoked" if "revok" in low else "created",
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.venv/bin/python -m pytest tests/test_parse_events.py -k mandate -v`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add app/parsing/events/__init__.py app/parsing/events/mandates.py tests/test_parse_events.py
git commit -m "feat(money-radar): mandate message parser"
```

---

### Task 4: decline parser

**Files:**
- Create: `app/parsing/events/declines.py`
- Test: `tests/test_parse_events.py`

- [ ] **Step 1: Write the failing test**

Append to `tests/test_parse_events.py`:
```python
from app.parsing.events.declines import parse_decline


def test_decline_cvv_with_amount():
    r = parse_decline("IDFCFB", "Txn of INR 6802.00 on IDFC FIRST Bank Credit Card XX0684 at PAYTM APP declined due to Incorrect CVV. Retry")
    assert r["bank"] == "IDFC"
    assert r["last4"] == "0684"
    assert r["amount"] == 6802.0
    assert "incorrect cvv" in r["reason"].lower()


def test_decline_shared_limit():
    r = parse_decline("AXISBK", "Txn on Axis Bank Credit Card XX9966 is declined as you have exhausted shared limit across all your cards. Pay")
    assert r["bank"] == "AXIS"
    assert r["last4"] == "9966"
    assert "shared limit" in r["reason"].lower()


def test_decline_blocked_debit_card():
    r = parse_decline("AXISBK", "Your transaction was declined as your debit card ending with 4145 is blocked. Pl contact your base branch")
    assert r["last4"] == "4145"
    assert "blocked" in r["reason"].lower()


def test_non_decline_returns_none():
    assert parse_decline("AXISBK", "Statement for your Axis Bank Credit Card XX9966 of INR 4265 has been generated") is None
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv/bin/python -m pytest tests/test_parse_events.py -k decline -v`
Expected: FAIL with `ModuleNotFoundError`.

- [ ] **Step 3: Write minimal implementation**

Create `app/parsing/events/declines.py`:
```python
from __future__ import annotations

import re

_AMOUNT = re.compile(r"INR\s*([\d,]+\.?\d*)", re.IGNORECASE)
_LAST4 = re.compile(r"(?:XX|ending with\s*)(\d{4})", re.IGNORECASE)
_REASON = re.compile(r"(?:due to|declined as|is declined as)\s+(.+?)[\.\n]", re.IGNORECASE)


def _bank(blob: str) -> str:
    blob = blob.lower()
    if "idfc" in blob:
        return "IDFC"
    if "axis" in blob:
        return "AXIS"
    if "baroda" in blob or "-bob" in blob or " bob" in blob:
        return "BOB"
    return "UNKNOWN"


def parse_decline(sender: str, body: str) -> dict | None:
    low = body.lower()
    if "declined" not in low and "is blocked" not in low:
        return None
    if "mandate" in low:        # mandate revokes are handled by the mandate parser
        return None
    amt = _AMOUNT.search(body)
    last4 = _LAST4.search(body)
    reason = _REASON.search(body)
    return {
        "bank": _bank(f"{sender} {body}"),
        "last4": last4.group(1) if last4 else None,
        "amount": float(amt.group(1).replace(",", "")) if amt else None,
        "reason": (reason.group(1).strip() if reason else "declined"),
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.venv/bin/python -m pytest tests/test_parse_events.py -k decline -v`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add app/parsing/events/declines.py tests/test_parse_events.py
git commit -m "feat(money-radar): decline/blocked message parser"
```

---

### Task 5: refund parser

**Files:**
- Create: `app/parsing/events/refunds.py`
- Test: `tests/test_parse_events.py`

- [ ] **Step 1: Write the failing test**

Append to `tests/test_parse_events.py`:
```python
from app.parsing.events.refunds import parse_refund


def test_refund_reversal_card():
    r = parse_refund("IDFCFB", "Txn of INR 71.52 reversed on your IDFC FIRST Bank Credit Card XX0684 at UBER INDIA SYSTE PVT LTD on 27-AUG-2024")
    assert r["kind"] == "reversal"
    assert r["amount"] == 71.52
    assert r["last4"] == "0684"


def test_refund_explicit():
    r = parse_refund("IDFCFB", "Dear Cardmember, Transaction of INR 336.75 made at on 10-05-2026 has been refunded to your IDFC FIRST Bank Credit Card ending XX0684")
    assert r["kind"] == "refund"
    assert r["amount"] == 336.75


def test_refund_credit_balance():
    r = parse_refund("AXISBK", "You have a credit balance of INR 20430.6/- on your Axis Bank Credit Card no. XX6978. Call to place your refund")
    assert r["kind"] == "credit_balance"
    assert r["amount"] == 20430.6
    assert r["last4"] == "6978"


def test_non_refund_returns_none():
    assert parse_refund("AXISBK", "Rs 500 debited from your account") is None
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv/bin/python -m pytest tests/test_parse_events.py -k refund -v`
Expected: FAIL with `ModuleNotFoundError`.

- [ ] **Step 3: Write minimal implementation**

Create `app/parsing/events/refunds.py`:
```python
from __future__ import annotations

import re

_AMOUNT = re.compile(r"(?:INR|Rs\.?|₹)\s*([\d,]+\.?\d*)", re.IGNORECASE)
_LAST4 = re.compile(r"(?:XX|no\.?\s*XX|ending\s*XX?)(\d{4})", re.IGNORECASE)


def parse_refund(sender: str, body: str) -> dict | None:
    low = body.lower()
    if "credit balance" in low:
        kind = "credit_balance"
    elif "reversed" in low:
        kind = "reversal"
    elif "refunded" in low or "refund" in low:
        kind = "refund"
    else:
        return None
    amt = _AMOUNT.search(body)
    if not amt:
        return None
    last4 = _LAST4.search(body)
    return {
        "kind": kind,
        "amount": float(amt.group(1).replace(",", "")),
        "last4": last4.group(1) if last4 else None,
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.venv/bin/python -m pytest tests/test_parse_events.py -k refund -v`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add app/parsing/events/refunds.py tests/test_parse_events.py
git commit -m "feat(money-radar): refund/reversal/credit-balance parser"
```

---

### Task 6: EMI parser

**Files:**
- Create: `app/parsing/events/emi.py`
- Test: `tests/test_parse_events.py`

- [ ] **Step 1: Write the failing test**

Append to `tests/test_parse_events.py`:
```python
from app.parsing.events.emi import parse_emi


def test_emi_conversion_confirmed():
    r = parse_emi("IDFCFB", "Your purchase of INR 24000.00 on IDFC FIRST Bank Credit Card XX0684 has been converted to EMI of 6 months")
    assert r["amount"] == 24000.0
    assert r["tenure_months"] == 6


def test_emi_promo_ignored():
    # Promotional "convert now" offers are NOT conversions — must return None.
    assert parse_emi("IDFCFB", "Break down your IDFC FIRST Bank Credit Card transactions into easy EMIs. Convert now & get cashback") is None
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv/bin/python -m pytest tests/test_parse_events.py -k emi -v`
Expected: FAIL with `ModuleNotFoundError`.

- [ ] **Step 3: Write minimal implementation**

Create `app/parsing/events/emi.py`:
```python
from __future__ import annotations

import re

_AMOUNT = re.compile(r"(?:INR|Rs\.?|₹)\s*([\d,]+\.?\d*)", re.IGNORECASE)
_TENURE = re.compile(r"(\d{1,2})\s*month", re.IGNORECASE)


def parse_emi(sender: str, body: str) -> dict | None:
    low = body.lower()
    # Only real conversions, never the "convert now" promos.
    if "converted to emi" not in low and "emi conversion" not in low:
        return None
    amt = _AMOUNT.search(body)
    if not amt:
        return None
    tenure = _TENURE.search(body)
    return {
        "amount": float(amt.group(1).replace(",", "")),
        "tenure_months": int(tenure.group(1)) if tenure else None,
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.venv/bin/python -m pytest tests/test_parse_events.py -k emi -v`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add app/parsing/events/emi.py tests/test_parse_events.py
git commit -m "feat(money-radar): EMI-conversion parser"
```

---

## Phase C — Detector engine

`app/enrichment/money_radar.py` holds `FindingDraft`, all detector functions (pure: inputs in, drafts out), and `MoneyRadarService.scan()` which loads data, runs detectors, and upserts.

### Task 7: `FindingDraft` + module scaffold

**Files:**
- Create: `app/enrichment/money_radar.py`
- Test: `tests/test_money_radar.py`

- [ ] **Step 1: Write the failing test**

Append to `tests/test_money_radar.py`:
```python
from app.enrichment.money_radar import FindingDraft


def test_finding_draft_defaults():
    d = FindingDraft(type="mandate", severity="action", title="t", detail="d",
                     occurred_at=datetime(2026, 4, 15), dedup_key="k")
    assert d.amount is None
    assert d.currency == "INR"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv/bin/python -m pytest tests/test_money_radar.py -k draft_defaults -v`
Expected: FAIL with `ModuleNotFoundError: app.enrichment.money_radar`.

- [ ] **Step 3: Write minimal implementation**

Create `app/enrichment/money_radar.py`:
```python
from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime


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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.venv/bin/python -m pytest tests/test_money_radar.py -k draft_defaults -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/enrichment/money_radar.py tests/test_money_radar.py
git commit -m "feat(money-radar): FindingDraft scaffold"
```

---

### Task 8: event-derived detectors (mandate, decline, refund, EMI)

These iterate raw messages and call the Phase-B parsers. Signature: `detect_*(messages, today) -> list[FindingDraft]`, where each `message` is an object with `.sender`, `.body`, `.received_at`, `.id`.

**Files:**
- Modify: `app/enrichment/money_radar.py`
- Test: `tests/test_money_radar.py`

- [ ] **Step 1: Write the failing test**

Append to `tests/test_money_radar.py`:
```python
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv/bin/python -m pytest tests/test_money_radar.py -k "detect_mandates or detect_declines or detect_refunds or detect_emi" -v`
Expected: FAIL with `ImportError`.

- [ ] **Step 3: Write minimal implementation**

Append to `app/enrichment/money_radar.py`:
```python
from app.parsing.events.declines import parse_decline
from app.parsing.events.emi import parse_emi
from app.parsing.events.mandates import parse_mandate
from app.parsing.events.refunds import parse_refund


def detect_mandates(messages, today) -> list[FindingDraft]:
    out: list[FindingDraft] = []
    for m in messages:
        r = parse_mandate(m.sender, m.body)
        if not r or r["action"] != "created":
            continue
        merchant, amount = r["merchant"], r["amount"]
        out.append(FindingDraft(
            type="mandate", severity="action",
            title=f"{merchant} auto-pay set up · ₹{amount:,.0f}",
            detail="A recurring auto-debit mandate was created — make sure you still want it.",
            amount=amount, occurred_at=m.received_at,
            dedup_key=f"mandate:{merchant.lower()}:{amount:.0f}",
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
            dedup_key=f"refund:{r['kind']}:{last4}:{r['amount']:.0f}",
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.venv/bin/python -m pytest tests/test_money_radar.py -k "detect_mandates or detect_declines or detect_refunds or detect_emi" -v`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/enrichment/money_radar.py tests/test_money_radar.py
git commit -m "feat(money-radar): event-derived detectors (mandate/decline/refund/emi)"
```

---

### Task 9: statistical detectors (price hike, duplicate, large charge, forgotten recurring)

These run over `analytics_rows`-shaped dicts: `{merchant, amount, occurred_at, account_type, last4, bank, category}`. Reuse `normalize_merchant` from `app.parsing.types`.

**Files:**
- Modify: `app/enrichment/money_radar.py`
- Test: `tests/test_money_radar.py`

- [ ] **Step 1: Write the failing test**

Append to `tests/test_money_radar.py`:
```python
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv/bin/python -m pytest tests/test_money_radar.py -k "price_hike or duplicate or large_charge or forgotten" -v`
Expected: FAIL with `ImportError`.

- [ ] **Step 3: Write minimal implementation**

Append to `app/enrichment/money_radar.py`:
```python
from statistics import mean, median

from app.parsing.types import normalize_merchant

_MOVEMENT = ("upi transfer", "atm", "cash", "neft", "imps", "rtgs", "cred",
             "credit card payment", "card payment", "bill pay")


def _is_movement(merchant: str) -> bool:
    m = (merchant or "").lower()
    return any(k in m for k in _MOVEMENT)


def detect_price_hikes(rows, today) -> list[FindingDraft]:
    groups: dict[str, list[dict]] = {}
    for r in rows:
        if _is_movement(r["merchant"]):
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
        base = median(r["amount"] for r in prior)
        if base <= 0 or latest["amount"] <= base * 1.15:
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
    from datetime import timedelta
    groups: dict[tuple, list[dict]] = {}
    for r in rows:
        if _is_movement(r["merchant"]):
            continue
        key = (normalize_merchant(r["merchant"] or ""), round(r["amount"]))
        groups.setdefault(key, []).append(r)
    out: list[FindingDraft] = []
    for (mkey, amt), items in groups.items():
        if not mkey or len(items) < 2:
            continue
        items.sort(key=lambda r: r["occurred_at"])
        cluster = [items[0]]
        for r in items[1:]:
            if (r["occurred_at"] - cluster[-1]["occurred_at"]) <= timedelta(hours=window_hours):
                cluster.append(r)
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
        by_merchant.setdefault(normalize_merchant(r["merchant"] or ""), []).append(r["amount"])
    out: list[FindingDraft] = []
    for r in rows:
        if _is_movement(r["merchant"]):
            continue
        amounts = by_merchant.get(normalize_merchant(r["merchant"] or ""), [r["amount"]])
        med = median(amounts)
        if r["amount"] >= max(floor, 4 * med):
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
        out.append(FindingDraft(
            type="forgotten_recurring", severity="info",
            title=f"Yearly charge: {last['merchant']} · ₹{last['amount']:,.0f}",
            detail=f"Recurs about once a year ({round(cadence)}d) — easy to forget.",
            amount=last["amount"], occurred_at=last["occurred_at"],
            dedup_key=f"forgotten:{normalize_merchant(last['merchant'] or '')}",
            query_json=json.dumps({"merchant": last["merchant"]})))
    return out
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.venv/bin/python -m pytest tests/test_money_radar.py -k "price_hike or duplicate or large_charge or forgotten" -v`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add app/enrichment/money_radar.py tests/test_money_radar.py
git commit -m "feat(money-radar): statistical detectors (price hike/duplicate/large/forgotten)"
```

---

### Task 10: aggregate detectors (forex markup, hidden card costs)

Forex needs `original_amount`/`currency` (not in `analytics_rows`), so add `TransactionRepository.foreign_charges()`. Card-costs compares each recent bill's `total_due` to the card purchases captured for that cycle.

**Files:**
- Modify: `app/repositories.py` (add `foreign_charges`), `app/enrichment/money_radar.py`
- Test: `tests/test_money_radar.py`

- [ ] **Step 1: Write the failing test**

Append to `tests/test_money_radar.py`:
```python
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv/bin/python -m pytest tests/test_money_radar.py -k "forex or card_costs" -v`
Expected: FAIL with `ImportError`.

- [ ] **Step 3: Write minimal implementation**

Add to `app/repositories.py`, inside `TransactionRepository` (after `analytics_rows`):
```python
    async def foreign_charges(self, start, end) -> list[dict]:
        """Debits in [start,end) that were billed in a non-INR currency."""
        from app.entities import Transaction
        stmt = (select(Transaction.merchant, Transaction.amount,
                       Transaction.original_amount, Transaction.currency,
                       Transaction.occurred_at)
                .where(Transaction.direction == "debit",
                       Transaction.occurred_at >= start, Transaction.occurred_at < end,
                       Transaction.currency != "INR"))
        return [{"merchant": m, "amount": float(a),
                 "original_amount": (float(o) if o is not None else None),
                 "currency": c, "occurred_at": ts}
                for m, a, o, c, ts in (await self.session.execute(stmt)).all()]
```

Append to `app/enrichment/money_radar.py`:
```python
_FOREX_MARKUP_RATE = 0.035   # typical bank forex markup
_GST_RATE = 0.18             # GST charged on the markup


def detect_forex_markup(foreign_charges, today) -> list[FindingDraft]:
    if not foreign_charges:
        return []
    total_inr = sum(r["amount"] for r in foreign_charges)
    markup = total_inr * _FOREX_MARKUP_RATE
    markup_with_gst = markup * (1 + _GST_RATE)
    year = today.strftime("%Y")
    return [FindingDraft(
        type="forex_markup", severity="info",
        title=f"~₹{markup_with_gst:,.0f} hidden forex markup",
        detail=(f"₹{total_inr:,.0f} of foreign charges this year carry an estimated "
                f"~3.5% markup + GST your bank never itemises."),
        amount=round(markup_with_gst, 2), occurred_at=today,
        dedup_key=f"forex_markup:{year}")]


def detect_card_costs(bills, card_rows, today, *, cycle_days: int = 38) -> list[FindingDraft]:
    from datetime import timedelta
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
        if gap < 200 or gap < b.total_due * 0.02:
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.venv/bin/python -m pytest tests/test_money_radar.py -k "forex or card_costs" -v`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add app/repositories.py app/enrichment/money_radar.py tests/test_money_radar.py
git commit -m "feat(money-radar): aggregate detectors (forex markup, hidden card costs)"
```

---

### Task 11: `MoneyRadarService.scan()` orchestration

Loads transactions + messages + bills, runs all 10 detectors, upserts every draft. Idempotent.

**Files:**
- Modify: `app/enrichment/money_radar.py`
- Test: `tests/test_money_radar.py`

- [ ] **Step 1: Write the failing test**

Append to `tests/test_money_radar.py`:
```python
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

    await svc.scan()                 # second pass must not duplicate
    await session.commit()
    assert len(await repo.list_active()) == first
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv/bin/python -m pytest tests/test_money_radar.py -k scan_creates -v`
Expected: FAIL with `ImportError: cannot import name 'MoneyRadarService'`.

- [ ] **Step 3: Write minimal implementation**

Append to `app/enrichment/money_radar.py`:
```python
from datetime import date, datetime, timedelta

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

        repo = FindingRepository(self.session)
        for d in drafts:
            await repo.upsert_by_dedup_key(d)
        return len(drafts)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.venv/bin/python -m pytest tests/test_money_radar.py -k scan_creates -v`
Expected: PASS

- [ ] **Step 5: Run the full detector suite + commit**

Run: `.venv/bin/python -m pytest tests/test_money_radar.py tests/test_parse_events.py -q`
Expected: PASS (all)

```bash
git add app/enrichment/money_radar.py tests/test_money_radar.py
git commit -m "feat(money-radar): MoneyRadarService.scan orchestration"
```

---

## Phase D — API

### Task 12: schemas + router + registration

**Files:**
- Modify: `app/schemas.py`, `app/main.py:67`
- Create: `app/routers/money_radar.py`
- Test: `tests/test_api_money_radar.py`

- [ ] **Step 1: Write the failing test**

Create `tests/test_api_money_radar.py` (reuse the `client` fixture pattern from `tests/test_api_insights.py`):
```python
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.db import Base, new_session_factory
from app.deps import get_session
from app.main import app


@pytest_asyncio.fixture
async def client():
    engine, Session = new_session_factory("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async def override():
        async with Session() as s:
            yield s

    app.dependency_overrides[get_session] = override
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c
    app.dependency_overrides.clear()
    await engine.dispose()


async def _seed_mandate(client):
    await client.post("/ingest/sms", json={
        "sender": "AXISBK", "external_id": "sms-m1",
        "body": "UPI mandate set up for Spotify India for INR 119.00. Not you?",
        "received_at": "2026-04-15T10:00:00"})


async def test_scan_then_feed_and_summary(client):
    await _seed_mandate(client)
    scan = await client.post("/money-radar/scan")
    assert scan.status_code == 200

    feed = (await client.get("/money-radar")).json()
    assert feed["new_count"] >= 1
    assert any(f["type"] == "mandate" for f in feed["findings"])
    assert feed["findings"][0]["query"] is None or isinstance(feed["findings"][0]["query"], dict)

    summary = (await client.get("/money-radar/summary")).json()
    assert summary["new_count"] == feed["new_count"]
    assert len(summary["top"]) <= 3


async def test_seen_then_dismiss(client):
    await _seed_mandate(client)
    await client.post("/money-radar/scan")
    fid = (await client.get("/money-radar")).json()["findings"][0]["id"]

    await client.post("/money-radar/seen")
    assert (await client.get("/money-radar")).json()["new_count"] == 0

    await client.post(f"/money-radar/{fid}/dismiss")
    assert all(f["id"] != fid for f in (await client.get("/money-radar")).json()["findings"])
```

> The `/ingest/sms` body shape matches `app/schemas.py:SmsIn` — confirm its field names while writing the seed (sender/body/received_at/external_id).

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv/bin/python -m pytest tests/test_api_money_radar.py -v`
Expected: FAIL with 404 (route not registered).

- [ ] **Step 3: Write minimal implementation**

Append to `app/schemas.py`:
```python
class FindingOut(BaseModel):
    id: int
    type: str
    severity: str
    title: str
    detail: str
    amount: float | None
    currency: str
    occurred_at: datetime
    status: str
    query: dict | None = None


class MoneyRadarResponse(BaseModel):
    findings: list[FindingOut]
    new_count: int


class RadarSummary(BaseModel):
    new_count: int
    top: list[FindingOut]
```
(Ensure `from datetime import datetime` and `from pydantic import BaseModel` are imported at the top of `schemas.py` — they already are, used by `TransactionOut`.)

Create `app/routers/money_radar.py`:
```python
from __future__ import annotations

import json

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.deps import get_session
from app.enrichment.money_radar import MoneyRadarService
from app.repositories import FindingRepository

router = APIRouter()


def _to_out(f) -> dict:
    return {
        "id": f.id, "type": f.type, "severity": f.severity, "title": f.title,
        "detail": f.detail, "amount": f.amount, "currency": f.currency,
        "occurred_at": f.occurred_at, "status": f.status,
        "query": json.loads(f.query_json) if f.query_json else None,
    }


@router.get("/money-radar")
async def money_radar(session: AsyncSession = Depends(get_session)):
    repo = FindingRepository(session)
    findings = await repo.list_active()
    return {"findings": [_to_out(f) for f in findings], "new_count": await repo.count_new()}


@router.get("/money-radar/summary")
async def money_radar_summary(session: AsyncSession = Depends(get_session)):
    repo = FindingRepository(session)
    findings = await repo.list_active()
    return {"new_count": await repo.count_new(), "top": [_to_out(f) for f in findings[:3]]}


@router.post("/money-radar/scan")
async def money_radar_scan(session: AsyncSession = Depends(get_session)):
    n = await MoneyRadarService(session).scan()
    await session.commit()
    return {"scanned": n}


@router.post("/money-radar/seen")
async def money_radar_seen(session: AsyncSession = Depends(get_session)):
    await FindingRepository(session).mark_all_seen()
    await session.commit()
    return {"ok": True}


@router.post("/money-radar/{finding_id}/dismiss")
async def money_radar_dismiss(finding_id: int, session: AsyncSession = Depends(get_session)):
    ok = await FindingRepository(session).dismiss(finding_id)
    await session.commit()
    return {"ok": ok}
```

Register it in `app/main.py`. Add the import alongside the other router imports and add after line 67 (`app.include_router(sync.router)`):
```python
from app.routers import money_radar  # add to the existing routers import block
app.include_router(money_radar.router)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.venv/bin/python -m pytest tests/test_api_money_radar.py -v`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add app/schemas.py app/routers/money_radar.py app/main.py tests/test_api_money_radar.py
git commit -m "feat(money-radar): REST API (feed, summary, scan, seen, dismiss)"
```

---

## Phase E — Notifications + weekly digest

### Task 13: emit findings as notifications + hidden-money digest

**Files:**
- Modify: `app/services/notifications.py`
- Test: `tests/test_api_money_radar.py`

- [ ] **Step 1: Write the failing test**

Append to `tests/test_api_money_radar.py`:
```python
async def test_findings_appear_in_notifications(client):
    await _seed_mandate(client)
    await client.post("/money-radar/scan")
    notifs = (await client.get("/notifications")).json()["notifications"]
    radar = [n for n in notifs if n["type"] == "money_radar"]
    assert radar, "expected a money_radar notification"
    assert radar[0]["route"] == "money_radar"
    # Stable id == the finding's dedup_key (client dedups on it).
    assert radar[0]["id"].startswith("mandate:")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv/bin/python -m pytest tests/test_api_money_radar.py -k notifications -v`
Expected: FAIL (no money_radar items).

- [ ] **Step 3: Write minimal implementation**

In `app/services/notifications.py`, add a builder and a digest, and call them inside `build_notifications`.

Add near the other `_*_notifications` helpers:
```python
def _money_radar_notifications(findings) -> list[dict]:
    """Each NEW finding → a notification with id == dedup_key (client dedups)."""
    items: list[dict] = []
    for f in findings:
        if f.status != "new":
            continue
        items.append({
            "id": f.dedup_key,
            "type": "money_radar",
            "severity": f.severity if f.severity in ("info", "warning") else "info",
            "route": "money_radar",
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
```

Inside `build_notifications`, before `return items`, add (extend the imports at the top of the function to include `FindingRepository`):
```python
    # Money Radar findings → notifications + weekly hidden-money digest.
    from app.repositories import FindingRepository
    radar = await FindingRepository(session).list_active()
    items += _money_radar_notifications(radar)
    radar_digest = _hidden_money_digest(radar, today)
    if radar_digest:
        items.append(radar_digest)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.venv/bin/python -m pytest tests/test_api_money_radar.py -k notifications -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/services/notifications.py tests/test_api_money_radar.py
git commit -m "feat(money-radar): emit findings as notifications + weekly digest"
```

---

## Phase F — Auto-scan on sync + recurring cross-link

### Task 14: run `scan()` after each ingest

**Files:**
- Modify: `app/routers/sync.py` (Gmail sync), `app/routers/transactions.py` (`ingest_sms`)
- Test: `tests/test_api_money_radar.py`

- [ ] **Step 1: Write the failing test**

Append to `tests/test_api_money_radar.py`:
```python
async def test_sms_ingest_triggers_scan(client):
    # Posting a mandate SMS should auto-produce a finding without an explicit scan.
    await client.post("/ingest/sms", json={
        "sender": "AXISBK", "external_id": "sms-auto1",
        "body": "UPI mandate set up for Netflix India for INR 649.00. Not you?",
        "received_at": "2026-04-15T10:00:00"})
    feed = (await client.get("/money-radar")).json()
    assert any(f["type"] == "mandate" for f in feed["findings"])
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv/bin/python -m pytest tests/test_api_money_radar.py -k triggers_scan -v`
Expected: FAIL (no finding without explicit scan).

- [ ] **Step 3: Write minimal implementation**

In `app/routers/transactions.py`, in the `ingest_sms` handler, after the existing ingest + `await session.commit()`, add:
```python
    from app.enrichment.money_radar import MoneyRadarService
    await MoneyRadarService(session).scan()
    await session.commit()
```

In `app/routers/sync.py`, in `sync_gmail`, after the loop and before `return`, replace the final `await session.commit()` region with:
```python
    await session.commit()
    from app.enrichment.money_radar import MoneyRadarService
    await MoneyRadarService(session).scan()
    await session.commit()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.venv/bin/python -m pytest tests/test_api_money_radar.py -k triggers_scan -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/routers/transactions.py app/routers/sync.py tests/test_api_money_radar.py
git commit -m "feat(money-radar): auto-scan after SMS/email ingest"
```

---

### Task 15: cross-link mandates into recurring series (Save More)

So detected mandates also appear in the existing recurring/Save-More surface.

**Files:**
- Modify: `app/repositories.py` (add `RecurringSeriesRepository`), `app/enrichment/money_radar.py` (`MoneyRadarService.scan` upserts a series per mandate)
- Test: `tests/test_money_radar.py`

- [ ] **Step 1: Write the failing test**

Append to `tests/test_money_radar.py`:
```python
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.venv/bin/python -m pytest tests/test_money_radar.py -k recurring_series -v`
Expected: FAIL with `ImportError: cannot import name 'RecurringSeriesRepository'`.

- [ ] **Step 3: Write minimal implementation**

Append to `app/repositories.py`:
```python
class RecurringSeriesRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def list_all(self) -> list:
        from app.entities import RecurringSeries
        return list((await self.session.execute(select(RecurringSeries))).scalars())

    async def upsert(self, *, merchant: str, account_id: int, expected_amount: float,
                     last_seen_at, cadence_days: int = 30):
        from datetime import timedelta

        from app.entities import RecurringSeries
        existing = (await self.session.execute(
            select(RecurringSeries).where(RecurringSeries.merchant == merchant))).scalar_one_or_none()
        next_at = last_seen_at + timedelta(days=cadence_days)
        if existing is not None:
            existing.expected_amount = expected_amount
            existing.last_seen_at = last_seen_at
            existing.next_expected_at = next_at
            return existing
        s = RecurringSeries(
            merchant=merchant, account_id=account_id, cadence_days=cadence_days,
            expected_amount=expected_amount, last_seen_at=last_seen_at, next_expected_at=next_at)
        self.session.add(s)
        return s
```

In `app/enrichment/money_radar.py`, at the end of `MoneyRadarService.scan` (before `return len(drafts)`), add:
```python
        # Cross-link mandates → recurring series so they also show in Save More.
        from app.repositories import AccountRepository, RecurringSeriesRepository
        accounts = await AccountRepository(self.session).list_all()
        default_account_id = accounts[0].id if accounts else None
        if default_account_id is not None:
            series_repo = RecurringSeriesRepository(self.session)
            for d in drafts:
                if d.type != "mandate":
                    continue
                merchant = json.loads(d.query_json)["merchant"]
                await series_repo.upsert(
                    merchant=merchant, account_id=default_account_id,
                    expected_amount=d.amount or 0.0, last_seen_at=d.occurred_at)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.venv/bin/python -m pytest tests/test_money_radar.py -k recurring_series -v`
Expected: PASS

- [ ] **Step 5: Run the FULL suite + commit**

Run: `.venv/bin/python -m pytest -q`
Expected: PASS (existing 84 tests + all new tests; no regressions)

```bash
git add app/repositories.py app/enrichment/money_radar.py tests/test_money_radar.py
git commit -m "feat(money-radar): cross-link mandates into recurring series"
```

---

## Phase G — End-to-end smoke against real data (manual, optional)

### Task 16: backfill the real DB and eyeball the output

- [ ] **Step 1: Run a one-shot scan against `emu.db`**

```bash
cd backend
ET_DATABASE_URL=sqlite+aiosqlite:///./emu.db .venv/bin/python -m uvicorn app.main:app --port 8000 &
sleep 3
curl -s -X POST localhost:8000/money-radar/scan
curl -s localhost:8000/money-radar | python3 -m json.tool | head -60
```
Expected: a Spotify price-hike, the Anthropic duplicate, mandates (Netflix/SafeGold/etc.), forex markup, and refund/credit-balance findings appear. Sanity-check titles/amounts against the spec's evidence table.

- [ ] **Step 2: Note any noisy detectors** (false positives) and tune thresholds inline (floors/percentages live in `money_radar.py`), re-running the scan. Commit any tuning:

```bash
git add app/enrichment/money_radar.py
git commit -m "chore(money-radar): tune detector thresholds against real data"
```

---

## Self-Review

**Spec coverage** (each §-5 detector → task): mandate/decline/refund/EMI → Task 8; price hike/duplicate/large/forgotten → Task 9; forex markup/card costs → Task 10; scan → Task 11; API (§6) → Task 12; notifications+digest (§7) → Task 13; sync hook (§5 "after each sync") → Task 14; recurring cross-link (§5 note) → Task 15; persisted Finding model (§3/§4) → Tasks 1–2. No gaps.

**Type consistency:** `FindingDraft` fields (Task 7) match `FindingRepository.upsert_by_dedup_key` reads (Task 2) and the `_Draft` test stub. `analytics_rows` dict keys (`merchant, amount, occurred_at, account_type, last4, bank, category`) are used consistently by all row-based detectors. `foreign_charges` keys (`merchant, amount, original_amount, currency, occurred_at`) match `detect_forex_markup`. Notification item shape (`id, type, severity, route, title, body`) matches the existing `build_notifications` items.

**Out-of-scope guard:** detectors 8 & 9 are labelled "estimate" in their `detail` strings (spec §10). Mandate `valid_to` placeholder dates are never surfaced (we only read merchant/amount/action). Backfill idempotency proven by Task 11 + Task 15 second-scan assertions.
