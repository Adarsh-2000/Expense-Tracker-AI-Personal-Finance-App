# Credit-Card Rewards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-credit-card rewards view — points balance, ≈₹ value, where/how to redeem, expiry (in-app + FCM), and redemption/voucher history — sourced from decrypted statement PDFs + SMS/email events + a curated catalog.

**Architecture:** Backend follows the proven Zerodha-holdings pattern: new `CardReward`/`RewardEvent` entities + repositories, a `card_rewards` parser reusing `holdings._decrypted_text`, a static catalog module, a `sync_card_rewards` service folded best-effort into `/sync/gmail`, and a `/rewards` router. Expiry alerts ride the existing `build_notifications`→`dispatch_new_pushes` FCM pipeline. Android adds a `RewardsScreen` reached from an Insights hub tile, a conditional Dashboard expiry strip, and Bills↔Rewards cross-links.

**Tech Stack:** Python FastAPI + SQLAlchemy async + pytest/httpx; `pypdf`/`pdfplumber` (already used). Kotlin + Jetpack Compose (Material3), Retrofit/Moshi, AndroidX Lifecycle.

---

## Testing conventions

- **Backend:** TDD with pytest from `backend/`: `.venv/bin/pytest <path> -v`. Tests use the in-memory `client`/`session` fixtures (see `tests/test_sync_investments.py`, `tests/test_api_accounts.py`).
- **Android:** no Compose UI test harness; the gate per task is a Kotlin compile
  `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -4` → `BUILD SUCCESSFUL`. Final on-device verification is Task 18 via the `run-app` skill.
- **No early returns inside Compose composables** — use `if/else`, never `return@Composable`.

---

## File Structure

**Backend**
- Modify `app/entities.py` — add `UserProfile.name`; new `CardReward`, `RewardEvent`.
- Modify `app/schemas.py` — add `name` to `ProfileIn`.
- Modify `app/repositories.py` — `ProfileRepository.upsert(name=)`; new `CardRewardRepository`, `RewardEventRepository`.
- Create `app/data/__init__.py`, `app/data/reward_catalogs.py` — static catalog + ≈₹ math.
- Create `app/parsing/card_rewards.py` — password derivation, statement points parser, redemption/voucher message parsers.
- Modify `app/connectors/gmail_poller.py` — Axis/IDFC statement fetch queries.
- Create `app/services/card_rewards.py` — `sync_card_rewards(session)`.
- Create `app/routers/rewards.py` — `GET /rewards`.
- Modify `app/routers/sync.py` — best-effort `sync_card_rewards` call.
- Modify `app/main.py` — register `rewards.router`.
- Modify `app/services/notifications.py` — reward-expiry notifications.
- Create tests: `tests/test_card_rewards_parsing.py`, `tests/test_card_rewards_service.py`, `tests/test_api_rewards.py`.

**Android**
- Modify `data/api/ExpenseApi.kt` — `RewardsResponse`/`RewardCardDto`/`RewardEventDto`/`RewardOptionDto` DTOs + `rewards()` endpoint.
- Modify `data/repository/ExpenseRepository.kt` — `rewards()`.
- Create `domain/usecase/RewardsUseCases.kt`; modify `domain/UseCases.kt` — wire `getRewards`.
- Create `ui/rewards/RewardsUiState.kt`, `ui/rewards/RewardsViewModel.kt`, `ui/rewards/RewardsScreen.kt`.
- Modify `ui/insights/InsightsScreen.kt` — Rewards tile + `onOpenRewards`.
- Modify `ui/shell/AppShell.kt` — `Screen.Rewards` route wiring.
- Modify `ui/dashboard/DashboardScreen.kt` + `DashboardViewModel.kt` + `DashboardUiState.kt` — expiry strip.
- Modify `ui/bills/BillsScreen.kt` — rewards cross-link.

---

## PHASE A — Backend data foundation

### Task 1: Add `name` to the user profile

**Files:**
- Modify: `backend/app/entities.py` (UserProfile)
- Modify: `backend/app/schemas.py` (ProfileIn)
- Modify: `backend/app/repositories.py` (ProfileRepository.upsert)
- Modify: `backend/app/routers/portfolio.py` (put_profile passes name)
- Test: `backend/tests/test_card_rewards_service.py`

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test_card_rewards_service.py`:

```python
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.db import Base, new_session_factory
from app.deps import get_session
from app.main import app


@pytest_asyncio.fixture
async def session_maker():
    engine, Session = new_session_factory("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield Session
    await engine.dispose()


async def test_profile_upsert_persists_name(session_maker):
    from app.repositories import ProfileRepository

    async with session_maker() as s:
        await ProfileRepository(s).upsert(pan="DXWPK1177G", dob="29-03-1998", name="Harsh Khandelwal")
        await s.commit()
    async with session_maker() as s:
        p = await ProfileRepository(s).get()
        assert p.name == "Harsh Khandelwal"
        assert p.dob == "29-03-1998"
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && .venv/bin/pytest tests/test_card_rewards_service.py::test_profile_upsert_persists_name -v`
Expected: FAIL — `TypeError: upsert() got an unexpected keyword argument 'name'`.

- [ ] **Step 3: Add the `name` column to `UserProfile`**

In `backend/app/entities.py`, in `class UserProfile`, add after the `dob` line:

```python
    name: Mapped[str | None] = mapped_column(String(64), nullable=True)
```

- [ ] **Step 4: Extend `ProfileRepository.upsert` and `ProfileIn`**

In `backend/app/repositories.py`, replace `ProfileRepository.upsert` with:

```python
    async def upsert(self, *, pan: str | None, dob: str | None,
                     name: str | None = None) -> UserProfile:
        existing = await self.get()
        if existing is not None:
            existing.pan = pan
            existing.dob = dob
            if name is not None:
                existing.name = name
            existing.updated_at = datetime.now()
            return existing
        profile = UserProfile(id=1, pan=pan, dob=dob, name=name, updated_at=datetime.now())
        self.session.add(profile)
        return profile
```

In `backend/app/schemas.py`, add to `class ProfileIn`:

```python
    name: str | None = None
```

In `backend/app/routers/portfolio.py`, change the `put_profile` upsert call to forward name:

```python
    await ProfileRepository(session).upsert(pan=pan, dob=payload.dob, name=payload.name)
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && .venv/bin/pytest tests/test_card_rewards_service.py::test_profile_upsert_persists_name -v`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add backend/app/entities.py backend/app/schemas.py backend/app/repositories.py backend/app/routers/portfolio.py backend/tests/test_card_rewards_service.py
git commit -m "$(cat <<'EOF'
feat(backend): add name to user profile for statement decryption

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 2: `CardReward` + `RewardEvent` entities & repositories

**Files:**
- Modify: `backend/app/entities.py`
- Modify: `backend/app/repositories.py`
- Test: `backend/tests/test_card_rewards_service.py`

- [ ] **Step 1: Write the failing test**

Append to `backend/tests/test_card_rewards_service.py`:

```python
async def test_card_reward_upsert_replaces_per_card(session_maker):
    from app.repositories import CardRewardRepository

    async with session_maker() as s:
        repo = CardRewardRepository(s)
        await repo.upsert_for_card(bank="IDFC", last4="0684", program="IDFC_FIRST",
                                   points_balance=12400, earned=300, redeemed=0,
                                   expiring_points=1250, expiring_date="2026-06-18",
                                   statement_period="MAY 2026", as_of="2026-06-02")
        await repo.upsert_for_card(bank="IDFC", last4="0684", program="IDFC_FIRST",
                                   points_balance=12700, earned=300, redeemed=0,
                                   expiring_points=0, expiring_date=None,
                                   statement_period="JUN 2026", as_of="2026-07-02")
        await s.commit()
        rows = await repo.list_all()
        assert len(rows) == 1
        assert rows[0].points_balance == 12700
        assert rows[0].as_of == "2026-07-02"


async def test_reward_event_dedup(session_maker):
    from app.repositories import RewardEventRepository

    async with session_maker() as s:
        repo = RewardEventRepository(s)
        ev = dict(bank="IDFC", last4="0684", points=1780, merchant="MAKEMYTRIP",
                  occurred_at="2026-05-20", voucher_code=None, voucher_expiry=None,
                  kind="redemption")
        assert await repo.add_if_new(**ev) is True
        assert await repo.add_if_new(**ev) is False
        await s.commit()
        assert len(await repo.list_recent(10)) == 1
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && .venv/bin/pytest tests/test_card_rewards_service.py -k "card_reward or reward_event" -v`
Expected: FAIL — `ImportError: cannot import name 'CardRewardRepository'`.

- [ ] **Step 3: Add the entities**

In `backend/app/entities.py`, change the sqlalchemy import line to include `Integer`:

```python
from sqlalchemy import Boolean, DateTime, Float, ForeignKey, Integer, String, UniqueConstraint
```

Then add at the end of the file:

```python
class CardReward(Base):
    # Latest reward snapshot per credit card (replace-per-card, like Holding).
    __tablename__ = "card_rewards"
    __table_args__ = (UniqueConstraint("bank", "last4", name="uq_card_reward_identity"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    bank: Mapped[str] = mapped_column(String(32))
    last4: Mapped[str] = mapped_column(String(4))
    program: Mapped[str] = mapped_column(String(32))  # "IDFC_FIRST" | "AXIS_EDGE"
    points_balance: Mapped[int] = mapped_column(Integer, default=0)
    earned: Mapped[int] = mapped_column(Integer, default=0)
    redeemed: Mapped[int] = mapped_column(Integer, default=0)
    expiring_points: Mapped[int] = mapped_column(Integer, default=0)
    expiring_date: Mapped[str | None] = mapped_column(String(10), nullable=True)  # YYYY-MM-DD
    statement_period: Mapped[str | None] = mapped_column(String(32), nullable=True)
    as_of: Mapped[str] = mapped_column(String(10))  # YYYY-MM-DD


class RewardEvent(Base):
    # Append-only log of redemptions + vouchers parsed from SMS/email.
    __tablename__ = "reward_events"
    id: Mapped[int] = mapped_column(primary_key=True)
    bank: Mapped[str] = mapped_column(String(32))
    last4: Mapped[str | None] = mapped_column(String(4), nullable=True)
    points: Mapped[int | None] = mapped_column(Integer, nullable=True)
    merchant: Mapped[str | None] = mapped_column(String(128), nullable=True)
    occurred_at: Mapped[str] = mapped_column(String(10))  # YYYY-MM-DD
    voucher_code: Mapped[str | None] = mapped_column(String(64), nullable=True)
    voucher_expiry: Mapped[str | None] = mapped_column(String(10), nullable=True)
    kind: Mapped[str] = mapped_column(String(16))  # "redemption" | "voucher"
    dedup_key: Mapped[str] = mapped_column(String(160), unique=True)
```

- [ ] **Step 4: Add the repositories**

In `backend/app/repositories.py`, confirm the top imports include `delete` and `select` (they do — used by `HoldingRepository`) and that the new entities are importable. Add at the end of the file:

```python
class CardRewardRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def upsert_for_card(self, *, bank: str, last4: str, program: str,
                              points_balance: int, earned: int, redeemed: int,
                              expiring_points: int, expiring_date: str | None,
                              statement_period: str | None, as_of: str):
        from app.entities import CardReward
        existing = (await self.session.execute(
            select(CardReward).where(CardReward.bank == bank, CardReward.last4 == last4)
        )).scalars().first()
        if existing is not None:
            existing.program = program
            existing.points_balance = points_balance
            existing.earned = earned
            existing.redeemed = redeemed
            existing.expiring_points = expiring_points
            existing.expiring_date = expiring_date
            existing.statement_period = statement_period
            existing.as_of = as_of
            return existing
        row = CardReward(bank=bank, last4=last4, program=program,
                         points_balance=points_balance, earned=earned, redeemed=redeemed,
                         expiring_points=expiring_points, expiring_date=expiring_date,
                         statement_period=statement_period, as_of=as_of)
        self.session.add(row)
        return row

    async def list_all(self):
        from app.entities import CardReward
        return list((await self.session.execute(select(CardReward))).scalars())


class RewardEventRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    @staticmethod
    def _key(bank, last4, occurred_at, points, voucher_code) -> str:
        return f"{bank}|{last4 or ''}|{occurred_at}|{points or ''}|{voucher_code or ''}"

    async def add_if_new(self, *, bank: str, last4: str | None, points: int | None,
                         merchant: str | None, occurred_at: str, voucher_code: str | None,
                         voucher_expiry: str | None, kind: str) -> bool:
        from app.entities import RewardEvent
        key = self._key(bank, last4, occurred_at, points, voucher_code)
        exists = (await self.session.execute(
            select(RewardEvent).where(RewardEvent.dedup_key == key)
        )).scalars().first()
        if exists is not None:
            return False
        self.session.add(RewardEvent(
            bank=bank, last4=last4, points=points, merchant=merchant,
            occurred_at=occurred_at, voucher_code=voucher_code,
            voucher_expiry=voucher_expiry, kind=kind, dedup_key=key))
        return True

    async def list_recent(self, limit: int = 20):
        from app.entities import RewardEvent
        return list((await self.session.execute(
            select(RewardEvent).order_by(RewardEvent.occurred_at.desc()).limit(limit)
        )).scalars())

    async def list_for_card(self, bank: str, last4: str | None, limit: int = 5):
        from app.entities import RewardEvent
        q = select(RewardEvent).where(RewardEvent.bank == bank)
        if last4 is not None:
            q = q.where(RewardEvent.last4 == last4)
        return list((await self.session.execute(
            q.order_by(RewardEvent.occurred_at.desc()).limit(limit))).scalars())
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && .venv/bin/pytest tests/test_card_rewards_service.py -k "card_reward or reward_event" -v`
Expected: PASS (2 passed).

- [ ] **Step 6: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add backend/app/entities.py backend/app/repositories.py backend/tests/test_card_rewards_service.py
git commit -m "$(cat <<'EOF'
feat(backend): CardReward + RewardEvent entities and repositories

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## PHASE B — Backend parsing + catalog

### Task 3: Statement password derivation

**Files:**
- Create: `backend/app/parsing/card_rewards.py`
- Test: `backend/tests/test_card_rewards_parsing.py`

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test_card_rewards_parsing.py`:

```python
from app.parsing.card_rewards import derive_statement_passwords


def test_axis_password_name_plus_ddmm():
    pwds = derive_statement_passwords(name="Harsh Khandelwal", dob="29-03-1998", last4="9966")
    assert "HARS2903" in pwds


def test_candidates_include_dob_and_card_variants():
    pwds = derive_statement_passwords(name="Harsh Khandelwal", dob="29-03-1998", last4="9966")
    assert "29031998" in pwds          # DDMMYYYY
    assert "HARS9966" in pwds          # name4 + last4
    # name4 must be uppercase letters only, ignoring the space
    assert all(p == p.upper() for p in pwds if p[:4].isalpha())


def test_missing_name_still_yields_dob_candidates():
    pwds = derive_statement_passwords(name=None, dob="29-03-1998", last4=None)
    assert "29031998" in pwds
    assert pwds  # non-empty
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && .venv/bin/pytest tests/test_card_rewards_parsing.py -k password -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.parsing.card_rewards'`.

- [ ] **Step 3: Create the parser module with password derivation**

Create `backend/app/parsing/card_rewards.py`:

```python
"""Credit-card rewards: statement-password derivation, statement points-summary
extraction, and SMS/email redemption/voucher parsing.

Reuses holdings._decrypted_text to open password-protected statement PDFs."""
from __future__ import annotations

import re


def _name4(name: str | None) -> str | None:
    if not name:
        return None
    letters = re.sub(r"[^A-Za-z]", "", name)
    return letters[:4].upper() if len(letters) >= 4 else None


def _ddmm(dob: str | None) -> str | None:
    # dob stored as "DD-MM-YYYY"
    if not dob:
        return None
    m = re.match(r"(\d{2})-(\d{2})-(\d{4})", dob.strip())
    return m.group(1) + m.group(2) if m else None


def _ddmmyyyy(dob: str | None) -> str | None:
    if not dob:
        return None
    m = re.match(r"(\d{2})-(\d{2})-(\d{4})", dob.strip())
    return m.group(1) + m.group(2) + m.group(3) if m else None


def derive_statement_passwords(*, name: str | None, dob: str | None,
                               last4: str | None) -> list[str]:
    """Ordered candidate passwords for Axis/IDFC statement PDFs. The decrypt step
    tries each until one opens the file. Axis = name4+DDMM; others fall back to
    DDMMYYYY and name4+last4."""
    n4, dm, dmy = _name4(name), _ddmm(dob), _ddmmyyyy(dob)
    out: list[str] = []
    if n4 and dm:
        out.append(n4 + dm)          # Axis primary: HARS2903
    if dmy:
        out.append(dmy)              # IDFC candidate: 29031998
    if n4 and last4:
        out.append(n4 + last4)       # name4 + last4
    # de-dup, preserve order
    seen: set[str] = set()
    return [p for p in out if not (p in seen or seen.add(p))]
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && .venv/bin/pytest tests/test_card_rewards_parsing.py -k password -v`
Expected: PASS (3 passed).

- [ ] **Step 5: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add backend/app/parsing/card_rewards.py backend/tests/test_card_rewards_parsing.py
git commit -m "$(cat <<'EOF'
feat(backend): credit-card statement password derivation

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 4: Reward catalog + ≈₹ value math

**Files:**
- Create: `backend/app/data/__init__.py`, `backend/app/data/reward_catalogs.py`
- Test: `backend/tests/test_card_rewards_parsing.py`

- [ ] **Step 1: Write the failing test**

Append to `backend/tests/test_card_rewards_parsing.py`:

```python
from app.data.reward_catalogs import catalog_for, points_to_inr


def test_catalog_known_programs():
    idfc = catalog_for("IDFC_FIRST")
    axis = catalog_for("AXIS_EDGE")
    assert idfc and axis
    assert idfc["display_name"]
    assert idfc["conversion_rate_inr_per_point"] > 0
    assert any(p["label"] for p in idfc["partners"])


def test_points_to_inr_rounds():
    # 12400 * 0.25 = 3100.0
    assert points_to_inr(12400, "IDFC_FIRST") == 3100.0
    # unknown program -> 0.0 (no value claim)
    assert points_to_inr(100, "UNKNOWN") == 0.0
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && .venv/bin/pytest tests/test_card_rewards_parsing.py -k "catalog or points_to_inr" -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.data'`.

- [ ] **Step 3: Create the catalog**

Create empty `backend/app/data/__init__.py`. Create `backend/app/data/reward_catalogs.py`:

```python
"""Static, hand-maintained redemption catalog per reward program. Not parsed —
this is curated knowledge: conversion value, where to redeem, expiry policy.

Conversion rates are conservative ₹-per-point estimates for the ≈₹ value hint.
Adjust as programs change."""
from __future__ import annotations

_CATALOG: dict[str, dict] = {
    "IDFC_FIRST": {
        "display_name": "FIRST Rewards",
        "conversion_rate_inr_per_point": 0.25,
        "expiry_policy": "Never-expiring on most cards; bonus points may expire.",
        "portal_url": "https://www.idfcfirstbank.com/credit-card/rewards",
        "partners": [
            {"label": "Travel", "kind": "travel"},
            {"label": "Amazon", "kind": "shopping"},
            {"label": "Statement credit", "kind": "cashback"},
            {"label": "Vouchers", "kind": "voucher"},
        ],
    },
    "AXIS_EDGE": {
        "display_name": "EDGE Rewards",
        "conversion_rate_inr_per_point": 0.20,
        "expiry_policy": "EDGE points typically expire 3 years after earning.",
        "portal_url": "https://www.axisbank.com/edgerewards",
        "partners": [
            {"label": "Flights", "kind": "travel"},
            {"label": "Gift cards", "kind": "voucher"},
            {"label": "Amazon Prime", "kind": "voucher"},
            {"label": "Merchandise", "kind": "shopping"},
        ],
    },
}


def catalog_for(program: str) -> dict | None:
    return _CATALOG.get(program)


def points_to_inr(points: int, program: str) -> float:
    cat = _CATALOG.get(program)
    if not cat:
        return 0.0
    return round(points * cat["conversion_rate_inr_per_point"], 2)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && .venv/bin/pytest tests/test_card_rewards_parsing.py -k "catalog or points_to_inr" -v`
Expected: PASS (2 passed).

- [ ] **Step 5: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add backend/app/data/__init__.py backend/app/data/reward_catalogs.py backend/tests/test_card_rewards_parsing.py
git commit -m "$(cat <<'EOF'
feat(backend): curated reward redemption catalog + INR value math

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 5: Statement points-summary parser

**Files:**
- Modify: `backend/app/parsing/card_rewards.py`
- Test: `backend/tests/test_card_rewards_parsing.py`

Reward-summary blocks in Axis/IDFC statements render (after layout-flattening) with
labelled numeric rows. We key off labels and read the adjacent integer. The parser is
tolerant: any field it cannot find defaults to 0/None.

- [ ] **Step 1: Write the failing test**

Append to `backend/tests/test_card_rewards_parsing.py`:

```python
from app.parsing.card_rewards import extract_points_summary

AXIS_TEXT = """
EDGE REWARD POINTS SUMMARY
Opening Balance            5,000
Points Earned              4,100
Points Redeemed              200
Points Expired                 0
Closing Balance            8,900
Points expiring on 30-06-2026: 1,200
Statement Period: 01-05-2026 to 31-05-2026
"""

IDFC_TEXT = """
REWARD POINTS SUMMARY
Opening Points            12,100
Earned                       300
Redeemed                       0
Closing Points            12,400
1,250 points expiring on 18-06-2026
"""


def test_extract_axis_summary():
    s = extract_points_summary(AXIS_TEXT, "AXIS_EDGE")
    assert s["points_balance"] == 8900
    assert s["earned"] == 4100
    assert s["redeemed"] == 200
    assert s["expiring_points"] == 1200
    assert s["expiring_date"] == "2026-06-30"


def test_extract_idfc_summary():
    s = extract_points_summary(IDFC_TEXT, "IDFC_FIRST")
    assert s["points_balance"] == 12400
    assert s["earned"] == 300
    assert s["expiring_points"] == 1250
    assert s["expiring_date"] == "2026-06-18"


def test_extract_returns_none_without_balance():
    assert extract_points_summary("no rewards here", "AXIS_EDGE") is None
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && .venv/bin/pytest tests/test_card_rewards_parsing.py -k extract_ -v`
Expected: FAIL — `ImportError: cannot import name 'extract_points_summary'`.

- [ ] **Step 3: Implement the extractor**

Append to `backend/app/parsing/card_rewards.py`:

```python
_INT = r"([\d,]+)"


def _find_int(text: str, *label_patterns: str) -> int | None:
    """First integer following any of the given label patterns (case-insensitive)."""
    for pat in label_patterns:
        m = re.search(pat + r"[^\d-]*" + _INT, text, re.I)
        if m:
            return int(m.group(1).replace(",", ""))
    return None


def _find_expiry(text: str) -> tuple[int | None, str | None]:
    """Points-expiring amount + ISO date from either
    'Points expiring on DD-MM-YYYY: N' or 'N points expiring on DD-MM-YYYY'."""
    m = re.search(r"expiring on\s+(\d{2})-(\d{2})-(\d{4})\s*[:\-]?\s*" + _INT, text, re.I)
    if m:
        iso = f"{m.group(3)}-{m.group(2)}-{m.group(1)}"
        return int(m.group(4).replace(",", "")), iso
    m = re.search(_INT + r"\s+points?\s+expiring on\s+(\d{2})-(\d{2})-(\d{4})", text, re.I)
    if m:
        iso = f"{m.group(4)}-{m.group(3)}-{m.group(2)}"
        return int(m.group(1).replace(",", "")), iso
    return None, None


def _find_period(text: str) -> str | None:
    m = re.search(r"Statement Period:\s*(.+)", text, re.I)
    return m.group(1).strip()[:32] if m else None


def extract_points_summary(text: str, program: str) -> dict | None:
    """Parse a reward-points summary out of decrypted statement text. Returns a
    dict with balance/earned/redeemed/expiring, or None when no closing balance
    can be found (so callers don't persist an empty snapshot)."""
    balance = _find_int(text, r"Closing Balance", r"Closing Points", r"Available Reward Points")
    if balance is None:
        return None
    earned = _find_int(text, r"Points Earned", r"\bEarned\b") or 0
    redeemed = _find_int(text, r"Points Redeemed", r"\bRedeemed\b") or 0
    expiring_points, expiring_date = _find_expiry(text)
    return {
        "program": program,
        "points_balance": balance,
        "earned": earned,
        "redeemed": redeemed,
        "expiring_points": expiring_points or 0,
        "expiring_date": expiring_date,
        "statement_period": _find_period(text),
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && .venv/bin/pytest tests/test_card_rewards_parsing.py -k extract_ -v`
Expected: PASS (3 passed).

- [ ] **Step 5: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add backend/app/parsing/card_rewards.py backend/tests/test_card_rewards_parsing.py
git commit -m "$(cat <<'EOF'
feat(backend): statement reward-points summary extractor

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 6: Redemption + voucher message parsers

**Files:**
- Modify: `backend/app/parsing/card_rewards.py`
- Test: `backend/tests/test_card_rewards_parsing.py`

- [ ] **Step 1: Write the failing test**

Append to `backend/tests/test_card_rewards_parsing.py`:

```python
from app.parsing.card_rewards import parse_redemption_message, parse_voucher_email

REDEEM_SMS = ("Congrats! 1780 reward points redeemed from your IDFC FIRST Bank Credit "
              "Card XX0684 at MAKEMYTRIP INDIA PVT L . Available Reward Points: 0")

VOUCHER_EMAIL = ("Thank you for redeeming your eDGE Loyalty Reward Points for Amazon Prime "
                 "Instant Gift Code. Voucher Code PV8T-NVXS9S-YYR Voucher Denomination 1499 "
                 "Date of Expiry 1 Amazon Prime 07 Jun 2026")


def test_parse_redemption_extracts_points_and_card():
    ev = parse_redemption_message("sms", "IDFCFB", REDEEM_SMS, occurred_at="2026-05-20")
    assert ev["kind"] == "redemption"
    assert ev["points"] == 1780
    assert ev["last4"] == "0684"
    assert "MAKEMYTRIP" in ev["merchant"]
    assert ev["bank"] == "IDFC"


def test_parse_voucher_extracts_expiry():
    ev = parse_voucher_email(VOUCHER_EMAIL, occurred_at="2026-06-05")
    assert ev["kind"] == "voucher"
    assert ev["voucher_code"] == "PV8T-NVXS9S-YYR"
    assert ev["voucher_expiry"] == "2026-06-07"
    assert ev["bank"] == "AXIS"


def test_parse_non_reward_returns_none():
    assert parse_redemption_message("sms", "AXISBK", "Your OTP is 1234", occurred_at="2026-05-20") is None
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && .venv/bin/pytest tests/test_card_rewards_parsing.py -k "redemption or voucher" -v`
Expected: FAIL — `ImportError: cannot import name 'parse_redemption_message'`.

- [ ] **Step 3: Implement the message parsers**

Append to `backend/app/parsing/card_rewards.py`:

```python
_MONTHS = {m: i for i, m in enumerate(
    ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"], 1)}


def _bank_from_sender(sender: str, body: str) -> str | None:
    s = (sender + " " + body).lower()
    if "idfc" in s:
        return "IDFC"
    if "axis" in s or "edge" in s:
        return "AXIS"
    return None


def _last4(body: str) -> str | None:
    m = re.search(r"X{2,}(\d{4})", body) or re.search(r"ending\s+\w*?(\d{4})", body, re.I)
    return m.group(1) if m else None


def parse_redemption_message(channel: str, sender: str, body: str, *,
                             occurred_at: str) -> dict | None:
    """Extract a redemption event ('N reward points redeemed ... at MERCHANT') from
    an SMS/email body. Returns None when the body isn't a redemption."""
    m = re.search(r"([\d,]+)\s+reward points?\s+redeemed", body, re.I)
    if not m:
        return None
    bank = _bank_from_sender(sender, body)
    if not bank:
        return None
    merchant_m = re.search(r"\bat\s+([A-Z0-9][A-Z0-9 &.\-]{2,40})", body)
    merchant = merchant_m.group(1).strip(" .") if merchant_m else None
    return {
        "bank": bank, "last4": _last4(body),
        "points": int(m.group(1).replace(",", "")),
        "merchant": merchant, "occurred_at": occurred_at,
        "voucher_code": None, "voucher_expiry": None, "kind": "redemption",
    }


def parse_voucher_email(body: str, *, occurred_at: str) -> dict | None:
    """Extract a redeemed-voucher event (code + expiry) from an EDGE voucher email."""
    code_m = re.search(r"Voucher Code\s+([A-Z0-9\-]{6,})", body, re.I)
    if not code_m:
        return None
    exp_m = re.search(r"(\d{1,2})\s+([A-Za-z]{3})[a-z]*\s+(\d{4})", body)
    expiry = None
    if exp_m:
        mon = _MONTHS.get(exp_m.group(2).lower()[:3])
        if mon:
            expiry = f"{exp_m.group(3)}-{mon:02d}-{int(exp_m.group(1)):02d}"
    return {
        "bank": "AXIS", "last4": None, "points": None, "merchant": "Voucher",
        "occurred_at": occurred_at, "voucher_code": code_m.group(1),
        "voucher_expiry": expiry, "kind": "voucher",
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && .venv/bin/pytest tests/test_card_rewards_parsing.py -k "redemption or voucher" -v`
Expected: PASS (3 passed). Then run the whole parsing file: `.venv/bin/pytest tests/test_card_rewards_parsing.py -v` → all pass.

- [ ] **Step 5: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add backend/app/parsing/card_rewards.py backend/tests/test_card_rewards_parsing.py
git commit -m "$(cat <<'EOF'
feat(backend): redemption + voucher message parsers

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## PHASE C — Backend service, router, sync wiring, FCM

### Task 7: Gmail statement-fetch queries + service wrapper

**Files:**
- Modify: `backend/app/connectors/gmail_poller.py`
- Modify: `backend/app/services/gmail.py`

- [ ] **Step 1: Add the query constants**

In `backend/app/connectors/gmail_poller.py`, after the `HOLDING_STATEMENT_QUERY` definition (line ~44), add:

```python
AXIS_CARD_STATEMENT_QUERY = "from:cc.statements@axisbank.com has:attachment"
IDFC_CARD_STATEMENT_QUERY = "from:statement@idfcfirstbank.com has:attachment"
```

These are consumed by `fetch_latest_holding_pdf(gmail_service, query=...)`, which is generic over the query (returns the first PDF attachment of the newest matching email).

- [ ] **Step 2: Add a query-parameterized statement fetch wrapper**

In `backend/app/services/gmail.py`, add this function right after `fetch_latest_holding_pdf_bytes` (it mirrors that function exactly, but takes a query). This is the high-level accessor the service layer uses (the codebase never passes a raw `service` object around — it uses these wrappers):

```python
def fetch_latest_statement_pdf_bytes(query: str) -> bytes | None:
    """Build the Gmail client from token.json and download the latest PDF attachment
    matching `query` (e.g. a credit-card statement). Returns None when not authorized
    or no such email exists. Mirrors fetch_latest_holding_pdf_bytes."""
    import os

    token = "token.json"
    if not os.path.exists(token):
        return None
    try:
        from google.auth.transport.requests import Request
        from google.oauth2.credentials import Credentials
        from googleapiclient.discovery import build

        from app.connectors.gmail_poller import fetch_latest_holding_pdf

        creds = Credentials.from_authorized_user_file(token, GMAIL_SCOPES)
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
            with open(token, "w") as f:
                f.write(creds.to_json())
        service = build("gmail", "v1", credentials=creds)
        return fetch_latest_holding_pdf(service, query=query)
    except Exception:
        import logging
        logging.getLogger(__name__).exception("failed to fetch statement PDF")
        return None
```

- [ ] **Step 3: Verify import sanity**

Run: `cd backend && .venv/bin/python -c "from app.connectors.gmail_poller import AXIS_CARD_STATEMENT_QUERY, IDFC_CARD_STATEMENT_QUERY; from app.services.gmail import fetch_latest_statement_pdf_bytes; print('ok')"`
Expected: `ok`.

- [ ] **Step 4: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add backend/app/connectors/gmail_poller.py backend/app/services/gmail.py
git commit -m "$(cat <<'EOF'
feat(backend): Axis/IDFC statement Gmail queries + fetch wrapper

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 8: `sync_card_rewards` service

**Files:**
- Create: `backend/app/services/card_rewards.py`
- Test: `backend/tests/test_card_rewards_service.py`

- [ ] **Step 1: Write the failing test**

Append to `backend/tests/test_card_rewards_service.py`:

```python
async def test_sync_card_rewards_parses_messages_without_gmail(session_maker, monkeypatch):
    # No Gmail token in tests -> statement fetch is skipped; redemption parsing from
    # raw_messages still runs. Seed one redemption SMS and assert an event is logged.
    from app.entities import RawMessage
    from app.repositories import RewardEventRepository
    from app.services.card_rewards import sync_card_rewards

    async with session_maker() as s:
        s.add(RawMessage(channel="sms", sender="IDFCFB",
                         body=("Congrats! 1780 reward points redeemed from your IDFC FIRST "
                               "Bank Credit Card XX0684 at MAKEMYTRIP INDIA PVT L . "
                               "Available Reward Points: 0"),
                         received_at=__import__("datetime").datetime(2026, 5, 20),
                         external_id="m1", parsed_status="parsed"))
        await s.commit()
        result = await sync_card_rewards(s)
        await s.commit()
        assert result["status"] == "ok"
        events = await RewardEventRepository(s).list_recent(10)
        assert any(e.points == 1780 and e.last4 == "0684" for e in events)
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && .venv/bin/pytest tests/test_card_rewards_service.py::test_sync_card_rewards_parses_messages_without_gmail -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.services.card_rewards'`.

- [ ] **Step 3: Implement the service**

Create `backend/app/services/card_rewards.py`:

```python
"""Sync credit-card rewards: decrypt + parse statement PDFs for points balances,
and scan recent messages for redemption/voucher events. Best-effort and idempotent;
mirrors services.networth.sync_investments."""
from __future__ import annotations

import logging

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

logger = logging.getLogger("rewards")

# (program, issuer bank, gmail query) for each statement source.
_STATEMENT_SOURCES = [
    ("AXIS_EDGE", "AXIS", "AXIS_CARD_STATEMENT_QUERY"),
    ("IDFC_FIRST", "IDFC", "IDFC_CARD_STATEMENT_QUERY"),
]


async def _sync_statements(session: AsyncSession) -> int:
    """Fetch + decrypt + parse the latest statement per issuer. Returns #cards updated.
    No-op when Gmail isn't authorized."""
    import os

    if not os.path.exists("token.json"):
        return 0
    from app.connectors import gmail_poller
    from app.parsing.card_rewards import derive_statement_passwords, extract_points_summary
    from app.parsing.holdings import _decrypted_text
    from app.repositories import CardRewardRepository, ProfileRepository
    from app.services.gmail import fetch_latest_statement_pdf_bytes

    profile = await ProfileRepository(session).get()
    name = profile.name if profile else None
    dob = profile.dob if profile else None

    repo = CardRewardRepository(session)
    updated = 0
    for program, bank, query_name in _STATEMENT_SOURCES:
        query = getattr(gmail_poller, query_name)
        pdf = fetch_latest_statement_pdf_bytes(query)
        if not pdf:
            continue
        text = None
        for pwd in derive_statement_passwords(name=name, dob=dob, last4=None):
            text = _decrypted_text(pdf, pwd)
            if text:
                break
        if not text:
            continue
        summary = extract_points_summary(text, program)
        if not summary:
            continue
        import re
        m = re.search(r"X{2,}(\d{4})|ending\s+\w*?(\d{4})", text)
        last4 = (m.group(1) or m.group(2)) if m else "0000"
        from datetime import date
        await repo.upsert_for_card(
            bank=bank, last4=last4, program=program,
            points_balance=summary["points_balance"], earned=summary["earned"],
            redeemed=summary["redeemed"], expiring_points=summary["expiring_points"],
            expiring_date=summary["expiring_date"],
            statement_period=summary["statement_period"], as_of=date.today().isoformat())
        updated += 1
    return updated


async def _sync_events(session: AsyncSession) -> int:
    """Scan recent raw_messages for redemption + voucher events. Returns #new events."""
    from app.entities import RawMessage
    from app.parsing.card_rewards import parse_redemption_message, parse_voucher_email
    from app.repositories import RewardEventRepository

    repo = RewardEventRepository(session)
    rows = list((await session.execute(
        select(RawMessage).order_by(RawMessage.received_at.desc()).limit(2000)
    )).scalars())
    added = 0
    for r in rows:
        occurred = (r.received_at.date().isoformat() if r.received_at else "")
        ev = parse_redemption_message(r.channel, r.sender or "", r.body or "", occurred_at=occurred)
        if ev is None and r.channel == "email":
            ev = parse_voucher_email(r.body or "", occurred_at=occurred)
        if ev and await repo.add_if_new(**ev):
            added += 1
    return added


async def sync_card_rewards(session: AsyncSession) -> dict:
    """Top-level rewards sync: statements + events. Commits its own work."""
    cards = await _sync_statements(session)
    events = await _sync_events(session)
    await session.commit()
    return {"status": "ok", "cards_updated": cards, "events_added": events}
```

NOTE: `fetch_latest_statement_pdf_bytes` is the wrapper added in Task 7 Step 2; it
mirrors `fetch_latest_holding_pdf_bytes` (builds the Gmail client from `token.json`
internally). The service layer never handles a raw `service` object — always go through
these `app.services.gmail` wrappers.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && .venv/bin/pytest tests/test_card_rewards_service.py::test_sync_card_rewards_parses_messages_without_gmail -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add backend/app/services/card_rewards.py backend/tests/test_card_rewards_service.py
git commit -m "$(cat <<'EOF'
feat(backend): sync_card_rewards service (statements + events)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 9: `GET /rewards` router

**Files:**
- Create: `backend/app/routers/rewards.py`
- Test: `backend/tests/test_api_rewards.py`

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test_api_rewards.py`:

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
        yield c, Session
    app.dependency_overrides.clear()
    await engine.dispose()


async def test_rewards_endpoint_returns_card_with_value(client):
    c, Session = client
    from app.repositories import CardRewardRepository
    async with Session() as s:
        await CardRewardRepository(s).upsert_for_card(
            bank="IDFC", last4="0684", program="IDFC_FIRST", points_balance=12400,
            earned=300, redeemed=0, expiring_points=1250, expiring_date="2026-06-18",
            statement_period="MAY 2026", as_of="2026-06-02")
        await s.commit()

    r = await c.get("/rewards")
    assert r.status_code == 200
    body = r.json()
    assert body["total_points"] == 12400
    assert body["total_value_inr"] == 3100.0
    card = body["cards"][0]
    assert card["display_name"] == "FIRST Rewards"
    assert card["value_inr"] == 3100.0
    assert card["expiring_points"] == 1250
    assert any(o["label"] for o in card["redemption_options"])


async def test_rewards_empty(client):
    c, _ = client
    r = await c.get("/rewards")
    assert r.status_code == 200
    assert r.json() == {"total_value_inr": 0.0, "total_points": 0, "cards": []}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && .venv/bin/pytest tests/test_api_rewards.py -v`
Expected: FAIL — 404 (router not registered).

- [ ] **Step 3: Implement the router**

Create `backend/app/routers/rewards.py`:

```python
from __future__ import annotations

from datetime import date, datetime

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.deps import get_session

router = APIRouter()

_STALE_DAYS = 35


def _is_stale(as_of: str) -> bool:
    try:
        return (date.today() - datetime.fromisoformat(as_of).date()).days > _STALE_DAYS
    except Exception:
        return False


@router.get("/rewards")
async def get_rewards(session: AsyncSession = Depends(get_session)):
    from app.data.reward_catalogs import catalog_for, points_to_inr
    from app.repositories import CardRewardRepository, RewardEventRepository

    cards = await CardRewardRepository(session).list_all()
    ev_repo = RewardEventRepository(session)

    out_cards = []
    total_points = 0
    total_value = 0.0
    for card in cards:
        cat = catalog_for(card.program) or {}
        value = points_to_inr(card.points_balance, card.program)
        total_points += card.points_balance
        total_value += value
        events = await ev_repo.list_for_card(card.bank, card.last4, limit=5)
        out_cards.append({
            "bank": card.bank, "last4": card.last4, "program": card.program,
            "display_name": cat.get("display_name", card.program),
            "points_balance": card.points_balance, "value_inr": value,
            "as_of": card.as_of, "statement_period": card.statement_period,
            "expiring_points": card.expiring_points, "expiring_date": card.expiring_date,
            "stale": _is_stale(card.as_of),
            "redemption_options": cat.get("partners", []),
            "recent_events": [{
                "kind": e.kind, "points": e.points, "merchant": e.merchant,
                "occurred_at": e.occurred_at, "voucher_code": e.voucher_code,
                "voucher_expiry": e.voucher_expiry,
            } for e in events],
        })

    # Sort: expiring-soonest first (cards with an expiring_date), then value desc.
    out_cards.sort(key=lambda c: (c["expiring_date"] or "9999-12-31", -c["value_inr"]))
    return {
        "total_value_inr": round(total_value, 2),
        "total_points": total_points,
        "cards": out_cards,
    }
```

- [ ] **Step 4: Register the router**

In `backend/app/main.py`, add `rewards` to the `from app.routers import (...)` tuple and add after the other `include_router` lines:

```python
app.include_router(rewards.router)
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && .venv/bin/pytest tests/test_api_rewards.py -v`
Expected: PASS (2 passed).

- [ ] **Step 6: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add backend/app/routers/rewards.py backend/app/main.py backend/tests/test_api_rewards.py
git commit -m "$(cat <<'EOF'
feat(backend): GET /rewards endpoint with per-card value + options

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 10: Wire `sync_card_rewards` into `/sync/gmail`

**Files:**
- Modify: `backend/app/routers/sync.py`
- Test: `backend/tests/test_card_rewards_service.py`

- [ ] **Step 1: Write the failing test**

Append to `backend/tests/test_card_rewards_service.py`:

```python
async def test_sync_gmail_invokes_card_rewards(session_maker, monkeypatch):
    import app.routers.sync as sync_router
    from app.deps import get_session
    from app.main import app
    from httpx import ASGITransport, AsyncClient

    calls = {"n": 0}

    async def fake(session):
        calls["n"] += 1
        return {"status": "ok"}

    monkeypatch.setattr(sync_router, "sync_card_rewards", fake, raising=False)

    async def override():
        async with session_maker() as s:
            yield s

    app.dependency_overrides[get_session] = override
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.post("/sync/gmail")
    app.dependency_overrides.clear()
    assert r.status_code == 200
    assert calls["n"] == 1
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && .venv/bin/pytest tests/test_card_rewards_service.py::test_sync_gmail_invokes_card_rewards -v`
Expected: FAIL — `AttributeError: ... has no attribute 'sync_card_rewards'`.

- [ ] **Step 3: Add the import + best-effort call**

In `backend/app/routers/sync.py`, add near the top imports:

```python
from app.services.card_rewards import sync_card_rewards
```

Then in `sync_gmail`, after the `sync_investments` best-effort block (line ~77) and before the push-dispatch block, insert:

```python
    # Refresh credit-card rewards (statement points + redemption/voucher events).
    # Best-effort: never break transaction ingestion.
    try:
        await sync_card_rewards(session)
    except Exception:
        await session.rollback()
        logging.getLogger("rewards").exception("post-sync card-rewards sync failed")
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && .venv/bin/pytest tests/test_card_rewards_service.py -v`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add backend/app/routers/sync.py backend/tests/test_card_rewards_service.py
git commit -m "$(cat <<'EOF'
feat(backend): sync card rewards during gmail sync (best-effort)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 11: Reward-expiry notifications (FCM)

**Files:**
- Modify: `backend/app/services/notifications.py`
- Test: `backend/tests/test_card_rewards_service.py`

- [ ] **Step 1: Write the failing test**

Append to `backend/tests/test_card_rewards_service.py`:

```python
async def test_reward_expiry_notification(session_maker):
    from datetime import date, timedelta
    from app.repositories import CardRewardRepository
    from app.services.notifications import build_notifications

    soon = (date.today() + timedelta(days=10)).isoformat()
    async with session_maker() as s:
        await CardRewardRepository(s).upsert_for_card(
            bank="IDFC", last4="0684", program="IDFC_FIRST", points_balance=12400,
            earned=0, redeemed=0, expiring_points=1250, expiring_date=soon,
            statement_period="MAY 2026", as_of=date.today().isoformat())
        await s.commit()
        items = await build_notifications(s)
        reward = [i for i in items if i["type"] == "reward"]
        assert reward, "expected a reward-expiry notification"
        assert reward[0]["route"] == "rewards"
        assert "1,250" in reward[0]["body"] or "1250" in reward[0]["body"]
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && .venv/bin/pytest tests/test_card_rewards_service.py::test_reward_expiry_notification -v`
Expected: FAIL — no item with `type == "reward"`.

- [ ] **Step 3: Add the notification builder**

In `backend/app/services/notifications.py`, add this helper near the other `_*_notifications` functions:

```python
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
                    "title": "Voucher expiring soon",
                    "body": f"Voucher {e.voucher_code} expires {e.voucher_expiry}.",
                })
    return items
```

Then inside `build_notifications`, before `return items`, add:

```python
    # Reward points / voucher expiry.
    from app.repositories import CardRewardRepository, RewardEventRepository
    reward_cards = await CardRewardRepository(session).list_all()
    reward_events = await RewardEventRepository(session).list_recent(50)
    items += _reward_notifications(reward_cards, reward_events, today)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && .venv/bin/pytest tests/test_card_rewards_service.py::test_reward_expiry_notification -v`
Expected: PASS.

- [ ] **Step 5: Run the FULL backend suite (no regressions)**

Run: `cd backend && .venv/bin/pytest -q`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add backend/app/services/notifications.py backend/tests/test_card_rewards_service.py
git commit -m "$(cat <<'EOF'
feat(backend): reward points + voucher expiry notifications (FCM)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## PHASE D — Android data layer

### Task 12: Rewards DTOs, API, repository, use-case

**Files:**
- Modify: `android/app/.../data/api/ExpenseApi.kt`
- Modify: `android/app/.../data/repository/ExpenseRepository.kt`
- Create: `android/app/.../domain/usecase/RewardsUseCases.kt`
- Modify: `android/app/.../domain/UseCases.kt`

- [ ] **Step 1: Add DTOs + endpoint to `ExpenseApi.kt`**

In `data/api/ExpenseApi.kt`, add these DTOs near the other `data class` definitions (e.g. after `NetWorthResponse`):

```kotlin
@JsonClass(generateAdapter = true)
data class RewardOptionDto(
    val label: String,
    val kind: String,
)

@JsonClass(generateAdapter = true)
data class RewardEventDto(
    val kind: String,
    val points: Int?,
    val merchant: String?,
    @Json(name = "occurred_at") val occurredAt: String,
    @Json(name = "voucher_code") val voucherCode: String?,
    @Json(name = "voucher_expiry") val voucherExpiry: String?,
)

@JsonClass(generateAdapter = true)
data class RewardCardDto(
    val bank: String,
    val last4: String,
    val program: String,
    @Json(name = "display_name") val displayName: String,
    @Json(name = "points_balance") val pointsBalance: Int,
    @Json(name = "value_inr") val valueInr: Double,
    @Json(name = "as_of") val asOf: String,
    @Json(name = "statement_period") val statementPeriod: String?,
    @Json(name = "expiring_points") val expiringPoints: Int,
    @Json(name = "expiring_date") val expiringDate: String?,
    val stale: Boolean,
    @Json(name = "redemption_options") val redemptionOptions: List<RewardOptionDto>,
    @Json(name = "recent_events") val recentEvents: List<RewardEventDto>,
)

@JsonClass(generateAdapter = true)
data class RewardsResponse(
    @Json(name = "total_value_inr") val totalValueInr: Double,
    @Json(name = "total_points") val totalPoints: Int,
    val cards: List<RewardCardDto>,
)
```

(If the file's existing DTOs use `@Json`/`@JsonClass`, match that; confirm imports
`com.squareup.moshi.Json` and `com.squareup.moshi.JsonClass` are present — they are,
used by the other DTOs.)

Then inside `interface ExpenseApi`, add:

```kotlin
    @GET("rewards")
    suspend fun rewards(): RewardsResponse
```

- [ ] **Step 2: Add the repository method**

In `data/repository/ExpenseRepository.kt`, add alongside the other methods (e.g. near `netWorth`):

```kotlin
    suspend fun rewards(): Result<RewardsResponse> =
        runCatching { api.rewards() }
```

Confirm `RewardsResponse` is imported (same package `data.api` — add the import if the
file imports DTOs explicitly).

- [ ] **Step 3: Add the use-case + wire it**

Create `domain/usecase/RewardsUseCases.kt`:

```kotlin
package com.ledgernoir.tracker.domain.usecase

import com.ledgernoir.tracker.data.api.RewardsResponse
import com.ledgernoir.tracker.data.repository.ExpenseRepository

/** Returns the per-card rewards summary. */
class GetRewardsUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<RewardsResponse> = repo.rewards()
}
```

In `domain/UseCases.kt`, add the import and a field in `class UseCases`:

```kotlin
import com.ledgernoir.tracker.domain.usecase.GetRewardsUseCase
```
```kotlin
    val getRewards = GetRewardsUseCase(repo)
```

- [ ] **Step 4: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -4`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/data/api/ExpenseApi.kt android/app/src/main/java/com/ledgernoir/tracker/data/repository/ExpenseRepository.kt android/app/src/main/java/com/ledgernoir/tracker/domain/usecase/RewardsUseCases.kt android/app/src/main/java/com/ledgernoir/tracker/domain/UseCases.kt
git commit -m "$(cat <<'EOF'
feat(android): rewards DTOs, API, repository, use-case

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## PHASE E — Android Rewards screen

### Task 13: `RewardsUiState` + `RewardsViewModel`

**Files:**
- Create: `android/app/.../ui/rewards/RewardsUiState.kt`
- Create: `android/app/.../ui/rewards/RewardsViewModel.kt`

- [ ] **Step 1: Create the UiState**

Create `ui/rewards/RewardsUiState.kt`:

```kotlin
package com.ledgernoir.tracker.ui.rewards

import com.ledgernoir.tracker.data.api.RewardCardDto

/**
 * State for the Rewards screen. [cards] drives the per-card list (already sorted
 * expiring-soonest-first by the backend); [totalValueInr]/[totalPoints] drive the
 * aggregate hero (shown only when more than one card).
 */
data class RewardsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val totalValueInr: Double = 0.0,
    val totalPoints: Int = 0,
    val cards: List<RewardCardDto> = emptyList(),
)
```

- [ ] **Step 2: Create the ViewModel**

Create `ui/rewards/RewardsViewModel.kt`:

```kotlin
package com.ledgernoir.tracker.ui.rewards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.domain.usecase.GetRewardsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the rewards fetch. Initial load shows a spinner; refresh() (resume +
 * pull-to-refresh) re-fetches silently. Mirrors NetWorthViewModel.
 */
class RewardsViewModel(
    private val getRewards: GetRewardsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RewardsUiState())
    val uiState: StateFlow<RewardsUiState> = _uiState.asStateFlow()

    init { load(showSpinner = true) }

    fun refresh() = load(showSpinner = false)

    private fun load(showSpinner: Boolean) {
        viewModelScope.launch {
            _uiState.value =
                if (showSpinner) _uiState.value.copy(loading = true)
                else _uiState.value.copy(refreshing = true)
            getRewards().onSuccess {
                _uiState.value = _uiState.value.copy(
                    totalValueInr = it.totalValueInr,
                    totalPoints = it.totalPoints,
                    cards = it.cards,
                )
            }
            _uiState.value = _uiState.value.copy(loading = false, refreshing = false)
        }
    }
}
```

- [ ] **Step 3: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -4`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/ui/rewards/RewardsUiState.kt android/app/src/main/java/com/ledgernoir/tracker/ui/rewards/RewardsViewModel.kt
git commit -m "$(cat <<'EOF'
feat(android): RewardsUiState + RewardsViewModel

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 14: `RewardsScreen`

**Files:**
- Create: `android/app/.../ui/rewards/RewardsScreen.kt`

- [ ] **Step 1: Create the screen**

Create `ui/rewards/RewardsScreen.kt`:

```kotlin
package com.ledgernoir.tracker.ui.rewards

import com.ledgernoir.tracker.ui.designsystem.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.data.api.RewardCardDto
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.ui.common.ledgerViewModelFactory
import com.ledgernoir.tracker.ui.theme.AccentGreen
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.DisplayFamily
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextMuted
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary

private val AmberWarn = Color(0xFFFBBF24)

private fun cardAccent(bank: String): Color = when (bank.uppercase()) {
    "AXIS" -> Color(0xFFAE275F)
    "BOB" -> Color(0xFFF26522)
    "IDFC" -> Color(0xFF9C1D26)
    else -> AccentTeal
}

@Composable
fun RewardsScreen(repository: ExpenseRepository, onBack: () -> Unit) {
    val vm: RewardsViewModel = viewModel(
        factory = ledgerViewModelFactory { RewardsViewModel(it.useCases.getRewards) },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        ResponsiveContainer {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onBack()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary)
                }
                Spacer(Modifier.width(4.dp))
                Text("Rewards", style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary, fontFamily = DisplayFamily)
            }
            Spacer(Modifier.height(20.dp))

            if (state.loading) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentTeal)
                }
            } else if (state.cards.isEmpty()) {
                CredCard {
                    Text("No rewards yet", color = TextPrimary,
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Sync your email so we can read your card statements.",
                        color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                if (state.cards.size > 1) {
                    CredCard {
                        Text("TOTAL REDEEMABLE", style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary, letterSpacing = 1.4.sp)
                        Spacer(Modifier.height(10.dp))
                        MoneyText(amount = state.totalValueInr, fontSize = 32.sp,
                            color = AccentGreen, showPaise = false)
                        Spacer(Modifier.height(6.dp))
                        Text("across ${state.cards.size} cards · ${state.totalPoints} pts",
                            color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(20.dp))
                }
                state.cards.forEachIndexed { i, card ->
                    RewardCard(card)
                    if (i < state.cards.size - 1) Spacer(Modifier.height(16.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RewardCard(card: RewardCardDto) {
    val accent = cardAccent(card.bank)
    CredCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(accent))
            Spacer(Modifier.width(10.dp))
            Text("${card.bank} ••${card.last4}", color = TextPrimary,
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Text(card.displayName, color = TextSecondary,
                style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${card.pointsBalance} pts", color = TextPrimary, fontSize = 22.sp,
                fontFamily = DisplayFamily, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            MoneyText(amount = card.valueInr, fontSize = 18.sp, color = AccentGreen,
                showPaise = false)
        }
        Spacer(Modifier.height(4.dp))
        val asOf = if (card.stale) "as of ${card.asOf} · may be outdated"
                   else "as of ${card.asOf}"
        Text(asOf, color = TextMuted, style = MaterialTheme.typography.labelSmall)

        if (card.expiringPoints > 0 && card.expiringDate != null) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AmberWarn.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("⚠ ${card.expiringPoints} pts expire ${card.expiringDate}",
                    color = AmberWarn, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold)
            }
        }

        if (card.redemptionOptions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("REDEEM AT", style = MaterialTheme.typography.labelSmall,
                color = TextSecondary, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                card.redemptionOptions.forEach { opt ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(SurfaceMid)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(opt.label, style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary, maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        val voucher = card.recentEvents.firstOrNull { it.kind == "voucher" && it.voucherExpiry != null }
        if (voucher != null) {
            Spacer(Modifier.height(10.dp))
            Text("🎟 ${voucher.voucherCode} · exp ${voucher.voucherExpiry}",
                color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        }

        val redemptions = card.recentEvents.filter { it.kind == "redemption" }
        if (redemptions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("RECENT", style = MaterialTheme.typography.labelSmall,
                color = TextSecondary, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(6.dp))
            redemptions.take(3).forEach { ev ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(ev.merchant ?: "Redeemed", color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                        maxLines = 1)
                    Text("${ev.points ?: 0} pts · ${ev.occurredAt}", color = TextMuted,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -6`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/ui/rewards/RewardsScreen.kt
git commit -m "$(cat <<'EOF'
feat(android): RewardsScreen (per-card points, value, expiry, redeem)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## PHASE F — Android integration

### Task 15: Insights "Rewards" tile + navigation route

**Files:**
- Modify: `android/app/.../ui/insights/InsightsScreen.kt`
- Modify: `android/app/.../ui/shell/AppShell.kt`

- [ ] **Step 1: Add `onOpenRewards` param + the tile**

In `ui/insights/InsightsScreen.kt`, add a parameter to `InsightsScreen` (alongside
`onOpenMoneyRadar`):

```kotlin
    onOpenRewards: () -> Unit = {},
```

Then change the second tile `Row` (currently `[Budgets | Money Radar]`) so the grid
becomes the approved 5-tile layout. Replace the block:

```kotlin
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InsightsNavTile("Budgets", "Caps · overspend alerts",
                            Color(0xFFFBBF24), Modifier.weight(1f), onOpenBudget)
                        InsightsNavTile("Money Radar", "Mandates · forex · fees",
                            Color(0xFFEF4444), Modifier.weight(1f), onOpenMoneyRadar,
                            badgeCount = state.radarNewCount)
                    }
```

with:

```kotlin
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InsightsNavTile("Budgets", "Caps · overspend alerts",
                            Color(0xFFFBBF24), Modifier.weight(1f), onOpenBudget)
                        InsightsNavTile("Rewards", "Points · redeem · expiry",
                            AccentGreen, Modifier.weight(1f), onOpenRewards)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InsightsNavTile("Money Radar", "Mandates · forex · fees",
                            Color(0xFFEF4444), Modifier.weight(1f), onOpenMoneyRadar,
                            badgeCount = state.radarNewCount)
                    }
```

(`AccentGreen` is already imported in this file.)

- [ ] **Step 2: Add the `Screen.Rewards` route + wire Insights**

In `ui/shell/AppShell.kt`:

First add the import near the other screen imports:

```kotlin
import com.ledgernoir.tracker.ui.rewards.RewardsScreen
```

Find the `sealed class Screen` (or route constant list) and the `Screen.Budget` /
`Screen.MoneyRadar` route definitions. Add a sibling route. If routes are declared as
`object`s in a sealed class, add:

```kotlin
    object Rewards : Screen("rewards")
```

(If routes are plain string consts, mirror that style with `const val ROUTE_REWARDS = "rewards"`.)

Then where `InsightsScreen(...)` is invoked, add the new callback:

```kotlin
                    onOpenRewards = { navController.navigate("rewards") },
```

And add the composable destination next to the Budget/MoneyRadar `composable(...)` blocks:

```kotlin
            composable("rewards") {
                RewardsScreen(
                    repository = app.repository,
                    onBack = { navController.popBackStack() },
                )
            }
```

Finally, add `"rewards"` to the `hideBottomBar` route set (search for where
`ROUTE_TXN_RANGE`/MoneyRadar routes are added to that set) so the screen is full-screen:

```kotlin
                currentDest?.route == "rewards" ||
```

- [ ] **Step 3: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -6`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/ui/insights/InsightsScreen.kt android/app/src/main/java/com/ledgernoir/tracker/ui/shell/AppShell.kt
git commit -m "$(cat <<'EOF'
feat(android): Rewards tile in Insights hub + navigation route

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 16: Dashboard expiry strip

**Files:**
- Modify: `android/app/.../ui/dashboard/DashboardUiState.kt`
- Modify: `android/app/.../ui/dashboard/DashboardViewModel.kt`
- Modify: `android/app/.../ui/dashboard/DashboardScreen.kt`

- [ ] **Step 1: Add expiry fields to `DashboardUiState`**

In `ui/dashboard/DashboardUiState.kt`, add fields to the state data class:

```kotlin
    val rewardsExpiringPoints: Int = 0,
    val rewardsExpiringLabel: String? = null,   // e.g. "1,250 IDFC pts expire 18 Jun"
```

- [ ] **Step 2: Fetch rewards-expiry in `DashboardViewModel`**

In `ui/dashboard/DashboardViewModel.kt`, add `getRewards` to the constructor params:

```kotlin
    private val getRewards: com.ledgernoir.tracker.domain.usecase.GetRewardsUseCase,
```

Add a new `init` block (mirrors the radar one) that loads the soonest-expiring card:

```kotlin
    init {
        // Rewards-expiry strip: surface the soonest card expiry, if any.
        viewModelScope.launch {
            getRewards().onSuccess { resp ->
                val soon = resp.cards.firstOrNull { it.expiringPoints > 0 && it.expiringDate != null }
                if (soon != null) {
                    _uiState.value = _uiState.value.copy(
                        rewardsExpiringPoints = soon.expiringPoints,
                        rewardsExpiringLabel =
                            "${soon.expiringPoints} ${soon.bank} pts expire ${soon.expiringDate}",
                    )
                }
            }
        }
    }
```

Then update the `ledgerViewModelFactory { DashboardViewModel(...) }` construction site
(in `DashboardScreen.kt` or wherever the VM is built) to pass `it.useCases.getRewards`.

- [ ] **Step 3: Render the strip in `DashboardScreen`**

In `ui/dashboard/DashboardScreen.kt`, near the `MoneyRadarStripCard` usage (search
`radarNewCount > 0`), add a sibling conditional block:

```kotlin
            if (state.rewardsExpiringLabel != null) {
                RewardsExpiryStripCard(
                    label = state.rewardsExpiringLabel!!,
                    onClick = { onOpenRewards() },
                )
                Spacer(Modifier.height(16.dp))
            }
```

Add the composable near `MoneyRadarStripCard`:

```kotlin
@Composable
private fun RewardsExpiryStripCard(label: String, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    CredCard(modifier = Modifier.clickable {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress); onClick()
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚠️", fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Points expiring", style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(2.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            }
            Text("Redeem →", style = MaterialTheme.typography.labelMedium, color = AccentGreen)
        }
    }
}
```

Ensure `DashboardScreen` has an `onOpenRewards: () -> Unit = {}` param and that
`AppShell` passes `onOpenRewards = { navController.navigate("rewards") }` to it. (If the
import for `LocalHapticFeedback`, `AccentGreen`, `HapticFeedbackType`, `clickable`, or
`sp` is missing, add it — they are standard and used elsewhere in the file.)

- [ ] **Step 4: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -6`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/ui/dashboard/DashboardUiState.kt android/app/src/main/java/com/ledgernoir/tracker/ui/dashboard/DashboardViewModel.kt android/app/src/main/java/com/ledgernoir/tracker/ui/dashboard/DashboardScreen.kt
git commit -m "$(cat <<'EOF'
feat(android): dashboard rewards-expiry strip

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 17: Bills → Rewards cross-link

**Files:**
- Modify: `android/app/.../ui/bills/BillsScreen.kt`
- Modify: `android/app/.../ui/shell/AppShell.kt`

- [ ] **Step 1: Add an `onOpenRewards` param + entry point to BillsScreen**

In `ui/bills/BillsScreen.kt`, add to the `BillsScreen` signature:

```kotlin
    onOpenRewards: () -> Unit = {},
```

Add a tappable "View rewards →" affordance in the bills summary area. Locate
`BillsSummaryCard(...)` usage in `BillsScreen` and add, immediately after it:

```kotlin
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenRewards() }
                    .background(SurfaceMid)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text("View card rewards →", color = AccentGreen,
                    style = MaterialTheme.typography.labelLarge)
            }
```

(Confirm imports for `clickable`, `clip`, `RoundedCornerShape`, `SurfaceMid`,
`AccentGreen`, `background`, `padding` exist in the file; add any missing.)

- [ ] **Step 2: Pass the callback from AppShell**

In `ui/shell/AppShell.kt`, update the `BillsScreen(...)` invocation to pass:

```kotlin
                    onOpenRewards = { navController.navigate("rewards") },
```

- [ ] **Step 3: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -6`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/ui/bills/BillsScreen.kt android/app/src/main/java/com/ledgernoir/tracker/ui/shell/AppShell.kt
git commit -m "$(cat <<'EOF'
feat(android): Bills -> Rewards cross-link

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## PHASE G — Verification

### Task 18: Build, install, verify on device

**Use the project `run-app` skill** (backend on real `emu.db` + wireless adb). Device:
`SM-S901E` at `192.168.1.2` (re-discover the port via `adb mdns services`).

- [ ] **Step 1: Backend suite green + profile name set**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker/backend
.venv/bin/pytest -q              # all pass
# Seed the profile name so statement decryption can derive passwords:
.venv/bin/python -c "
import asyncio, os
os.environ['ET_DATABASE_URL']='sqlite+aiosqlite:///./emu.db'
from app.db import new_session_factory
from app.repositories import ProfileRepository
async def m():
    e,S=new_session_factory(os.environ['ET_DATABASE_URL'])
    async with S() as s:
        p=await ProfileRepository(s).get()
        await ProfileRepository(s).upsert(pan=p.pan if p else None,
            dob=p.dob if p else '29-03-1998', name='Harsh Khandelwal')
        await s.commit()
        print('name set:', (await ProfileRepository(s).get()).name)
    await e.dispose()
asyncio.run(m())"
```

- [ ] **Step 2: Full build + install + launch**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker/android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ADB=~/Library/Android/sdk/platform-tools/adb
DEV=$($ADB devices | awk '/device$/{print $1; exit}')
./gradlew :app:assembleDebug --console=plain 2>&1 | tail -3      # BUILD SUCCESSFUL
$ADB -s "$DEV" install -r app/build/outputs/apk/debug/app-debug.apk
$ADB -s "$DEV" reverse tcp:8000 tcp:8000
$ADB -s "$DEV" shell am start -n com.ledgernoir.tracker/.MainActivity
```

- [ ] **Step 3: Verify the Rewards screen**

Insights tab → tap the **Rewards** tile. Screenshot (`$ADB -s "$DEV" exec-out screencap -p > /tmp/r.png`) and confirm:
  - Per-card cards render with points + ≈₹ value + "as of" line.
  - If a card has expiring points, the amber "⚠ N pts expire <date>" banner shows.
  - "Redeem at" chips render from the catalog.
  - Aggregate "TOTAL REDEEMABLE ≈₹" hero shows when >1 card.

- [ ] **Step 4: Verify the dashboard strip + cross-link**

  - If any card is expiring, the Dashboard shows the "⚠️ Points expiring · Redeem →" strip; tapping opens Rewards.
  - Bills screen shows "View card rewards →"; tapping opens Rewards.

- [ ] **Step 5: Trigger a real sync (statement parse)**

Tap **Sync Email** on the dashboard. After it completes, re-open Rewards and confirm
balances reflect the parsed statement (or, if IDFC password format differs, check the
backend log for "card-rewards" and adjust `derive_statement_passwords` candidates).
Confirm `/rewards` server-side: `curl -s localhost:8000/rewards | head -c 400`.

- [ ] **Step 6: Confirm backend suite still green**

Run: `cd backend && .venv/bin/pytest -q`
Expected: all pass.

---

## Self-Review notes (author)

- **Spec coverage:** statement PDF parse → Tasks 5,8; password derivation (name+DOB) →
  Tasks 1,3; redemption/voucher parse → Task 6; catalog + ≈₹ → Task 4; `/rewards` →
  Task 9; sync wiring → Tasks 7,10; FCM expiry → Task 11; RewardsScreen → Tasks 12-14;
  Insights tile → Task 15; dashboard strip → Task 16; Bills cross-link → Task 17;
  on-device verify → Task 18. All spec sections mapped.
- **Type consistency:** `RewardsResponse`/`RewardCardDto` fields (camelCase via `@Json`)
  match the router's snake_case JSON keys. `getRewards` use-case name consistent across
  Tasks 12-16. `sync_card_rewards(session)` signature consistent Tasks 8/10.
- **Known risk (flagged in spec):** statement-PDF parsing is only fully verifiable on a
  live sync (Task 18 Step 5). IDFC password format is candidate-driven; if all candidates
  fail, the screen still works for redemption events + catalog, and the fix is localized
  to `derive_statement_passwords`.
- **Verified during planning:** the Gmail access pattern — `services/gmail.py` exposes
  high-level `fetch_latest_*_pdf_bytes()` wrappers (not a shared `service` accessor);
  Task 7 adds `fetch_latest_statement_pdf_bytes(query)` mirroring the proven
  `fetch_latest_holding_pdf_bytes`. The `cardBankColor` map and `MoneyRadarStripCard`
  pattern were also confirmed present for reuse.
