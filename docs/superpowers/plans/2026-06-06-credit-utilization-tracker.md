# Credit Utilization Tracker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Per-credit-card utilization (used ÷ sanctioned limit) from the `Avl Limit` in card transaction SMS — a collapsed-by-default Bills mini-bar (expand-on-tap for detail) and a Money Radar alert. No Dashboard chip (avoids overload; the Money Radar finding already surfaces high utilization there).

**Architecture:** A new `limit_scan` extracts available credit (latest `Avl Limit`) and a max-observed sanctioned-limit heuristic; a `utilization` service combines them with an optional `accounts.credit_limit` override; a `/cards/utilization` endpoint + `PUT /cards/{id}/limit` feed the Android UI; a Money Radar detector (using accounts so it honors overrides) fires at ≥30%/≥50%.

**Tech Stack:** Python FastAPI + SQLAlchemy async + pytest; Kotlin + Jetpack Compose (Material3), Retrofit/Moshi.

---

## Testing conventions
- **Backend:** `cd backend && .venv/bin/pytest <path> -v` (in-memory fixtures; see `tests/test_api_rewards.py`, `tests/test_card_rewards_service.py`).
- **Android:** compile gate `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -4` → BUILD SUCCESSFUL. On-device verify in Task 9.
- **No early returns inside composables** — if/else only.

---

## File Structure
- Modify `backend/app/entities.py` — `Account.credit_limit`.
- Modify `backend/app/repositories.py` — `AccountRepository.set_credit_limit`.
- Modify `backend/app/schemas.py` — `SetLimitIn`.
- Create `backend/app/enrichment/limit_scan.py` — `latest_avl_limit_for`, `max_avl_limit_for`.
- Create `backend/app/services/utilization.py` — `card_utilization`.
- Modify `backend/app/routers/accounts.py` — `GET /cards/utilization`, `PUT /cards/{id}/limit`.
- Modify `backend/app/enrichment/money_radar.py` — `detect_high_utilization` + wire into `scan`.
- Modify `android/.../data/api/ExpenseApi.kt`, `data/repository/ExpenseRepository.kt`, `domain/usecase/UtilizationUseCases.kt` (new), `domain/UseCases.kt`.
- Modify `android/.../ui/bills/{BillsViewModel,BillsUiState,BillsScreen}.kt`.
- (No Dashboard changes — see spec "Dashboard — deliberately NO new chip".)

---

## PHASE A — Backend

### Task 1: `Account.credit_limit` column + repository setter + schema

**Files:** Modify `backend/app/entities.py`, `backend/app/repositories.py`, `backend/app/schemas.py`. Test: `backend/tests/test_utilization.py`.

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test_utilization.py`:

```python
import pytest_asyncio

from app.db import Base, new_session_factory


@pytest_asyncio.fixture
async def session_maker():
    engine, Session = new_session_factory("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield Session
    await engine.dispose()


async def test_set_credit_limit(session_maker):
    from app.repositories import AccountRepository

    async with session_maker() as s:
        repo = AccountRepository(s)
        acc = await repo.get_or_create(bank="AXIS", type="card", last4="6978")
        await s.commit()
        updated = await repo.set_credit_limit(acc.id, credit_limit=1000000.0)
        await s.commit()
        assert updated is not None
        assert updated.credit_limit == 1000000.0
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && .venv/bin/pytest tests/test_utilization.py::test_set_credit_limit -v`
Expected: FAIL — `AttributeError: 'AccountRepository' object has no attribute 'set_credit_limit'` (or no such column).

- [ ] **Step 3: Add the column**

In `backend/app/entities.py`, in `class Account`, add after `manual_balance_at`:

```python
    credit_limit: Mapped[float | None] = mapped_column(Float, nullable=True)
```

- [ ] **Step 4: Add the repository setter + schema**

In `backend/app/repositories.py`, in `class AccountRepository`, add (next to `set_manual_balance`):

```python
    async def set_credit_limit(self, account_id: int, *, credit_limit: float):
        acc = await self.get(account_id)
        if acc is None:
            return None
        acc.credit_limit = credit_limit
        return acc
```

In `backend/app/schemas.py`, add:

```python
class SetLimitIn(BaseModel):
    credit_limit: float
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd backend && .venv/bin/pytest tests/test_utilization.py::test_set_credit_limit -v`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add backend/app/entities.py backend/app/repositories.py backend/app/schemas.py backend/tests/test_utilization.py
git commit -m "$(cat <<'EOF'
feat(backend): account credit_limit override column + setter

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 2: `limit_scan` — available + max observed limit

**Files:** Create `backend/app/enrichment/limit_scan.py`. Test: `backend/tests/test_utilization.py`.

- [ ] **Step 1: Write the failing test**

Append to `backend/tests/test_utilization.py`:

```python
async def test_limit_scan_latest_and_max(session_maker):
    from datetime import datetime
    from app.entities import RawMessage
    from app.enrichment.limit_scan import latest_avl_limit_for, max_avl_limit_for

    async with session_maker() as s:
        s.add_all([
            RawMessage(channel="sms", sender="AXISBK", external_id="a1", parsed_status="parsed",
                       received_at=datetime(2026, 5, 1),
                       body="Spent INR 5000 Axis Bank Card no. XX6978 Avl Limit: INR 980000.00"),
            RawMessage(channel="sms", sender="AXISBK", external_id="a2", parsed_status="parsed",
                       received_at=datetime(2026, 6, 1),
                       body="Spent INR 9000 Axis Bank Card no. XX6978 Avl Limit: INR 760000.50"),
        ])
        await s.commit()
        latest = await latest_avl_limit_for(s, last4="6978")
        assert latest is not None and latest[0] == 760000.50  # newest
        assert await max_avl_limit_for(s, last4="6978") == 980000.00
        assert await latest_avl_limit_for(s, last4="0000") is None
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && .venv/bin/pytest tests/test_utilization.py::test_limit_scan_latest_and_max -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.enrichment.limit_scan'`.

- [ ] **Step 3: Create the scanner**

Create `backend/app/enrichment/limit_scan.py`:

```python
"""Extract credit-card available limit ("Avl Limit: INR X") from transaction SMS.

Parallel to balance_scan (which intentionally skips Avl Limit). latest_* gives the
freshest available credit; max_* approximates the sanctioned limit (available peaks
when outstanding is ~0)."""
from __future__ import annotations

import re

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.entities import RawMessage

_LIMIT_RE = re.compile(r"Avl\.?\s*Limit:?\s*INR\s*([\d,]+\.?\d*)", re.IGNORECASE)


def _to_float(raw: str) -> float:
    return float(raw.replace(",", ""))


async def _rows_for(session: AsyncSession, last4: str):
    return (await session.execute(
        select(RawMessage.received_at, RawMessage.body)
        .where(RawMessage.body.like(f"%{last4}%"))
        .order_by(RawMessage.received_at.desc())
    )).all()


async def latest_avl_limit_for(session: AsyncSession, *, last4: str) -> tuple[float, str] | None:
    """(available_limit, as_of_iso) from the newest SMS that mentions this card and
    carries an Avl Limit, or None."""
    if not last4:
        return None
    for received_at, body in await _rows_for(session, last4):
        m = _LIMIT_RE.search(body or "")
        if m:
            as_of = received_at.date().isoformat() if hasattr(received_at, "date") else str(received_at)[:10]
            return _to_float(m.group(1)), as_of
    return None


async def max_avl_limit_for(session: AsyncSession, *, last4: str) -> float | None:
    """Largest Avl Limit ever seen for this card ≈ sanctioned limit, or None."""
    if not last4:
        return None
    best: float | None = None
    for _received_at, body in await _rows_for(session, last4):
        m = _LIMIT_RE.search(body or "")
        if m:
            v = _to_float(m.group(1))
            if best is None or v > best:
                best = v
    return best
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && .venv/bin/pytest tests/test_utilization.py::test_limit_scan_latest_and_max -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add backend/app/enrichment/limit_scan.py backend/tests/test_utilization.py
git commit -m "$(cat <<'EOF'
feat(backend): limit_scan for credit-card available/max Avl Limit

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 3: `utilization` service

**Files:** Create `backend/app/services/utilization.py`. Test: `backend/tests/test_utilization.py`.

- [ ] **Step 1: Write the failing test**

Append to `backend/tests/test_utilization.py`:

```python
async def test_card_utilization(session_maker):
    from datetime import datetime
    from app.entities import RawMessage
    from app.repositories import AccountRepository
    from app.services.utilization import card_utilization

    async with session_maker() as s:
        repo = AccountRepository(s)
        a1 = await repo.get_or_create(bank="AXIS", type="card", last4="6978")
        a2 = await repo.get_or_create(bank="IDFC", type="card", last4="0684")  # no Avl Limit
        s.add_all([
            RawMessage(channel="sms", sender="AXISBK", external_id="m1", parsed_status="parsed",
                       received_at=datetime(2026, 5, 1),
                       body="XX6978 Avl Limit: INR 1000000.00"),
            RawMessage(channel="sms", sender="AXISBK", external_id="m2", parsed_status="parsed",
                       received_at=datetime(2026, 6, 1),
                       body="XX6978 Avl Limit: INR 750000.00"),
        ])
        await s.commit()
        cards = await card_utilization(s)
        by = {c["last4"]: c for c in cards}
        axis = by["6978"]
        assert axis["source"] == "observed"
        assert axis["total_limit"] == 1000000.00
        assert axis["available"] == 750000.00
        assert axis["used"] == 250000.00
        assert axis["utilization_pct"] == 25.0
        assert by["0684"]["source"] == "unknown"


async def test_card_utilization_override(session_maker):
    from datetime import datetime
    from app.entities import RawMessage
    from app.repositories import AccountRepository
    from app.services.utilization import card_utilization

    async with session_maker() as s:
        repo = AccountRepository(s)
        a = await repo.get_or_create(bank="AXIS", type="card", last4="6978")
        await repo.set_credit_limit(a.id, credit_limit=800000.0)
        s.add(RawMessage(channel="sms", sender="AXISBK", external_id="m1", parsed_status="parsed",
                         received_at=datetime(2026, 6, 1),
                         body="XX6978 Avl Limit: INR 600000.00"))
        await s.commit()
        axis = {c["last4"]: c for c in await card_utilization(s)}["6978"]
        assert axis["source"] == "manual"
        assert axis["total_limit"] == 800000.0
        assert axis["used"] == 200000.0
        assert axis["utilization_pct"] == 25.0
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && .venv/bin/pytest tests/test_utilization.py -k card_utilization -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.services.utilization'`.

- [ ] **Step 3: Create the service**

Create `backend/app/services/utilization.py`:

```python
"""Per-credit-card utilization = used / sanctioned-limit. Available comes from the
latest Avl Limit SMS; the sanctioned limit is the account's credit_limit override
when set, else the max-observed Avl Limit heuristic. Cards with neither → 'unknown'."""
from __future__ import annotations

from sqlalchemy.ext.asyncio import AsyncSession


async def card_utilization(session: AsyncSession) -> list[dict]:
    from app.enrichment.limit_scan import latest_avl_limit_for, max_avl_limit_for
    from app.repositories import AccountRepository

    accounts = [a for a in await AccountRepository(session).list_all() if a.type == "card"]
    out: list[dict] = []
    for a in accounts:
        last4 = a.last4 or ""
        latest = await latest_avl_limit_for(session, last4=last4)
        available = latest[0] if latest else None
        as_of = latest[1] if latest else None

        if a.credit_limit is not None:
            total = a.credit_limit
            source = "manual"
        else:
            total = await max_avl_limit_for(session, last4=last4)
            source = "observed" if total is not None else "unknown"

        if source == "unknown" or total is None or total <= 0 or available is None:
            out.append({
                "account_id": a.id, "bank": a.bank, "last4": a.last4,
                "total_limit": total or 0.0, "available": available or 0.0,
                "used": 0.0, "utilization_pct": 0.0, "as_of": as_of,
                "source": "unknown" if source == "unknown" or available is None else source,
            })
            continue

        used = round(total - available, 2)
        pct = round(used / total * 100.0, 1)
        out.append({
            "account_id": a.id, "bank": a.bank, "last4": a.last4,
            "total_limit": round(total, 2), "available": round(available, 2),
            "used": used, "utilization_pct": pct, "as_of": as_of, "source": source,
        })
    out.sort(key=lambda c: -c["utilization_pct"])
    return out
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && .venv/bin/pytest tests/test_utilization.py -k card_utilization -v`
Expected: PASS (2 passed).

- [ ] **Step 5: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add backend/app/services/utilization.py backend/tests/test_utilization.py
git commit -m "$(cat <<'EOF'
feat(backend): card utilization service (override + heuristic)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 4: routes — `GET /cards/utilization` + `PUT /cards/{id}/limit`

**Files:** Modify `backend/app/routers/accounts.py`. Test: `backend/tests/test_api_utilization.py`.

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test_api_utilization.py`:

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


async def test_get_utilization_and_set_limit(client):
    c, Session = client
    from datetime import datetime
    from app.entities import RawMessage
    from app.repositories import AccountRepository

    async with Session() as s:
        acc = await AccountRepository(s).get_or_create(bank="AXIS", type="card", last4="6978")
        s.add_all([
            RawMessage(channel="sms", sender="AXISBK", external_id="m1", parsed_status="parsed",
                       received_at=datetime(2026, 5, 1), body="XX6978 Avl Limit: INR 1000000.00"),
            RawMessage(channel="sms", sender="AXISBK", external_id="m2", parsed_status="parsed",
                       received_at=datetime(2026, 6, 1), body="XX6978 Avl Limit: INR 700000.00"),
        ])
        await s.commit()
        acc_id = acc.id

    r = await c.get("/cards/utilization")
    assert r.status_code == 200
    card = r.json()["cards"][0]
    assert card["utilization_pct"] == 30.0
    assert card["source"] == "observed"

    r2 = await c.put(f"/cards/{acc_id}/limit", json={"credit_limit": 800000.0})
    assert r2.status_code == 200
    r3 = await c.get("/cards/utilization")
    card3 = r3.json()["cards"][0]
    assert card3["source"] == "manual"
    assert card3["total_limit"] == 800000.0
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && .venv/bin/pytest tests/test_api_utilization.py -v`
Expected: FAIL — 404.

- [ ] **Step 3: Add the routes**

In `backend/app/routers/accounts.py`, add (after the `/accounts` route):

```python
@router.get("/cards/utilization")
async def cards_utilization(session: AsyncSession = Depends(get_session)):
    from app.services.utilization import card_utilization
    return {"cards": await card_utilization(session)}


@router.put("/cards/{account_id}/limit")
async def set_card_limit(account_id: int, payload: "SetLimitIn",
                         session: AsyncSession = Depends(get_session)):
    from app.repositories import AccountRepository
    from fastapi import HTTPException
    acc = await AccountRepository(session).set_credit_limit(
        account_id, credit_limit=payload.credit_limit)
    if acc is None:
        raise HTTPException(status_code=404, detail="account not found")
    await session.commit()
    return {"ok": True}
```

Add the import at the top of `accounts.py`:

```python
from app.schemas import SetLimitIn
```

(and remove the quotes around `SetLimitIn` in the handler signature → `payload: SetLimitIn`.)

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && .venv/bin/pytest tests/test_api_utilization.py -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add backend/app/routers/accounts.py backend/tests/test_api_utilization.py
git commit -m "$(cat <<'EOF'
feat(backend): /cards/utilization + PUT /cards/{id}/limit

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 5: Money Radar high-utilization detector

**Files:** Modify `backend/app/enrichment/money_radar.py`. Test: `backend/tests/test_utilization.py`.

- [ ] **Step 1: Write the failing test**

Append to `backend/tests/test_utilization.py`:

```python
async def test_high_utilization_detector(session_maker):
    from datetime import datetime
    from app.entities import RawMessage
    from app.repositories import AccountRepository
    from app.enrichment.money_radar import detect_high_utilization

    async with session_maker() as s:
        repo = AccountRepository(s)
        a = await repo.get_or_create(bank="AXIS", type="card", last4="6978")
        await repo.set_credit_limit(a.id, credit_limit=1000000.0)
        s.add(RawMessage(channel="sms", sender="AXISBK", external_id="m1", parsed_status="parsed",
                         received_at=datetime(2026, 6, 1), body="XX6978 Avl Limit: INR 400000.00"))
        await s.commit()
        accounts = await repo.list_all()
        msgs = [m for m in (await __import__("app.repositories", fromlist=["RawMessageRepository"])
                            .RawMessageRepository(s).list_by_status("parsed"))]
        drafts = detect_high_utilization(msgs, accounts, datetime(2026, 6, 2))
        assert any(d.type == "utilization" and d.severity == "action" for d in drafts)  # 60% used
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && .venv/bin/pytest tests/test_utilization.py::test_high_utilization_detector -v`
Expected: FAIL — `ImportError: cannot import name 'detect_high_utilization'`.

- [ ] **Step 3: Add the detector + wire into scan**

In `backend/app/enrichment/money_radar.py`, add this detector near the other `detect_*` functions. It is a pure function over messages + accounts (so it honors the `credit_limit` override without a session):

```python
_AVL_LIMIT_RE = re.compile(r"Avl\.?\s*Limit:?\s*INR\s*([\d,]+\.?\d*)", re.IGNORECASE)


def detect_high_utilization(messages, accounts, today) -> list[FindingDraft]:
    """High credit-utilization findings per card. Sanctioned limit = account
    credit_limit override else max observed Avl Limit; available = latest Avl Limit."""
    drafts: list[FindingDraft] = []
    overrides = {(a.last4 or ""): a.credit_limit for a in accounts
                 if a.type == "card" and a.last4}
    # Gather Avl Limit observations per last4 (messages are newest-first from scan()).
    latest: dict[str, float] = {}
    maxima: dict[str, float] = {}
    for m in messages:
        body = m.body or ""
        lm = _AVL_LIMIT_RE.search(body)
        if not lm:
            continue
        cm = re.search(r"X{2,}(\d{4})", body)
        if not cm:
            continue
        last4 = cm.group(1)
        val = float(lm.group(1).replace(",", ""))
        latest.setdefault(last4, val)            # first seen = newest
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
            dedup_key=f"util:{last4}:{bucket}",
        ))
    return drafts
```

Confirm `re` is imported at the top of the file (it is). Then in `MoneyRadarService.scan`, the `accounts` list is already loaded later in the method — move/ensure it's available before the detector calls, then add the detector to the `drafts` assembly. Specifically, after the existing `accounts = await AccountRepository(self.session).list_all()` line (currently below the detector block), and add the call. Simplest safe edit: add near the other `drafts +=` lines:

```python
        from app.repositories import AccountRepository as _AcctRepo
        _accounts = await _AcctRepo(self.session).list_all()
        drafts += detect_high_utilization(messages, _accounts, today)
```

(Place this with the other `drafts += detect_*` lines, before `repo = FindingRepository(self.session)`.)

- [ ] **Step 4: Run to verify it passes + full suite**

Run: `cd backend && .venv/bin/pytest tests/test_utilization.py -v`
Expected: PASS (all). Then `cd backend && .venv/bin/pytest -q` → all pass (no regressions).

- [ ] **Step 5: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add backend/app/enrichment/money_radar.py backend/tests/test_utilization.py
git commit -m "$(cat <<'EOF'
feat(backend): Money Radar high-utilization detector (30/50%)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## PHASE B — Android

### Task 6: DTOs + API + repository + use-cases

**Files:** Modify `data/api/ExpenseApi.kt`, `data/repository/ExpenseRepository.kt`, create `domain/usecase/UtilizationUseCases.kt`, modify `domain/UseCases.kt`.

- [ ] **Step 1: Add DTOs + endpoints**

In `data/api/ExpenseApi.kt`, add near the other DTOs:

```kotlin
@JsonClass(generateAdapter = true)
data class CardUtilizationDto(
    @Json(name = "account_id") val accountId: Int,
    val bank: String,
    val last4: String?,
    @Json(name = "total_limit") val totalLimit: Double,
    val available: Double,
    val used: Double,
    @Json(name = "utilization_pct") val utilizationPct: Double,
    @Json(name = "as_of") val asOf: String?,
    val source: String,
)

@JsonClass(generateAdapter = true)
data class CardUtilizationResponse(val cards: List<CardUtilizationDto>)

@JsonClass(generateAdapter = true)
data class SetLimitPayload(
    @Json(name = "credit_limit") val creditLimit: Double,
)
```

In `interface ExpenseApi`, add:

```kotlin
    @GET("cards/utilization")
    suspend fun cardUtilization(): CardUtilizationResponse

    @PUT("cards/{id}/limit")
    suspend fun setCardLimit(@Path("id") id: Int, @Body payload: SetLimitPayload)
```

Confirm `@PUT`, `@Path`, `@Body` are imported (other endpoints use them; add `import retrofit2.http.PUT` / `Path` / `Body` if missing).

- [ ] **Step 2: Add repository methods**

In `data/repository/ExpenseRepository.kt`, add:

```kotlin
    suspend fun cardUtilization(): Result<CardUtilizationResponse> =
        runCatching { api.cardUtilization() }

    suspend fun setCardLimit(id: Int, creditLimit: Double): Result<Unit> =
        runCatching { api.setCardLimit(id, SetLimitPayload(creditLimit)) }
```

- [ ] **Step 3: Add use-cases + wire**

Create `domain/usecase/UtilizationUseCases.kt`:

```kotlin
package com.ledgernoir.tracker.domain.usecase

import com.ledgernoir.tracker.data.api.CardUtilizationResponse
import com.ledgernoir.tracker.data.repository.ExpenseRepository

/** Per-card credit utilization. */
class GetCardUtilizationUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<CardUtilizationResponse> = repo.cardUtilization()
}

/** Set a manual sanctioned-limit override for a card account. */
class SetCardLimitUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(id: Int, creditLimit: Double): Result<Unit> =
        repo.setCardLimit(id, creditLimit)
}
```

In `domain/UseCases.kt`, add imports + fields in `class UseCases`:

```kotlin
import com.ledgernoir.tracker.domain.usecase.GetCardUtilizationUseCase
import com.ledgernoir.tracker.domain.usecase.SetCardLimitUseCase
```
```kotlin
    val getCardUtilization = GetCardUtilizationUseCase(repo)
    val setCardLimit = SetCardLimitUseCase(repo)
```

- [ ] **Step 4: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -4`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/data/api/ExpenseApi.kt android/app/src/main/java/com/ledgernoir/tracker/data/repository/ExpenseRepository.kt android/app/src/main/java/com/ledgernoir/tracker/domain/usecase/UtilizationUseCases.kt android/app/src/main/java/com/ledgernoir/tracker/domain/UseCases.kt
git commit -m "$(cat <<'EOF'
feat(android): card-utilization DTOs, API, repository, use-cases

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 7: Bills screen utilization bar + Set-limit dialog

**Files:** Modify `ui/bills/BillsUiState.kt`, `ui/bills/BillsViewModel.kt`, `ui/bills/BillsScreen.kt`.

- [ ] **Step 1: Hold utilization in BillsUiState**

In `ui/bills/BillsUiState.kt`, add a field to the state data class:

```kotlin
    val utilizationByAccount: Map<Int, com.ledgernoir.tracker.data.api.CardUtilizationDto> = emptyMap(),
```

- [ ] **Step 2: Fetch utilization in BillsViewModel**

In `ui/bills/BillsViewModel.kt`, add `getCardUtilization` + `setCardLimit` to the constructor params (match the existing use-case param style), and in the load/init flow fetch utilization. Add a new load in `init` (or extend the existing fetch) and a `setLimit` method + a `refresh()` if not present:

```kotlin
    init {
        viewModelScope.launch {
            getCardUtilization().onSuccess { resp ->
                _uiState.value = _uiState.value.copy(
                    utilizationByAccount = resp.cards.associateBy { it.accountId },
                )
            }
        }
    }

    fun setLimit(accountId: Int, creditLimit: Double) {
        viewModelScope.launch {
            setCardLimit(accountId, creditLimit).onSuccess {
                getCardUtilization().onSuccess { resp ->
                    _uiState.value = _uiState.value.copy(
                        utilizationByAccount = resp.cards.associateBy { it.accountId },
                    )
                }
            }
        }
    }
```

Update the `ledgerViewModelFactory { BillsViewModel(...) }` construction site (in `BillsScreen.kt`) to pass `it.useCases.getCardUtilization` and `it.useCases.setCardLimit` in the matching positions. Add the imports for the two use-case types in BillsViewModel.kt.

- [ ] **Step 3: Render the bar + dialog in BillsScreen**

In `ui/bills/BillsScreen.kt`, pass the utilization for each card into `CardStateCard` and add a setter callback. Change the `CardStateCard(c, onClick = {...})` call site to also pass:

```kotlin
                        CardStateCard(
                            c = c,
                            util = state.utilizationByAccount[c.accountId],
                            onSetLimit = { limit -> vm.setLimit(c.accountId, limit) },
                            onClick = { /* existing onClick body unchanged */ },
                        )
```

Change the `CardStateCard` signature from
`private fun CardStateCard(c: com.ledgernoir.tracker.data.api.CardStateDto, onClick: () -> Unit = {})`
to:

```kotlin
private fun CardStateCard(
    c: com.ledgernoir.tracker.data.api.CardStateDto,
    util: com.ledgernoir.tracker.data.api.CardUtilizationDto? = null,
    onSetLimit: (Double) -> Unit = {},
    onClick: () -> Unit = {},
) {
```

Inside `CardStateCard`'s `CredCard { ... }`, after the existing unbilled/due rows, add the
utilization block. **UX: collapsed by default = one slim bar + "24%"; tap expands the
breakdown in place** (reuses `animateContentSize`, the app's existing expand idiom). Wrap
the block in `Column(Modifier.animateContentSize())` and gate the detail on a local
`expanded` state:

```kotlin
            if (util != null && util.source != "unknown" && util.totalLimit > 0) {
                Spacer(Modifier.height(12.dp))
                var expanded by remember { mutableStateOf(false) }
                val pct = util.utilizationPct.toFloat().coerceIn(0f, 100f)
                val barColor = when {
                    pct >= 50f -> Color(0xFFEF4444)
                    pct >= 30f -> Color(0xFFFBBF24)
                    else -> Color(0xFF34D399)
                }
                Column(modifier = Modifier.animateContentSize().clickable { expanded = !expanded }) {
                    // Collapsed: slim bar + compact "24%" only (one glanceable line).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.weight(1f).height(6.dp)
                                .clip(RoundedCornerShape(3.dp)).background(SurfaceMid),
                        ) {
                            Box(Modifier.fillMaxWidth(pct / 100f).height(6.dp)
                                .clip(RoundedCornerShape(3.dp)).background(barColor))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("${pct.toInt()}%", color = barColor,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold)
                    }
                    // Expanded: breakdown + set-limit, revealed on tap.
                    if (expanded) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "₹${"%,.0f".format(util.used)} used · ₹${"%,.0f".format(util.available)} available" +
                                (util.asOf?.let { " · as of $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall, color = TextSecondary,
                        )
                        Spacer(Modifier.height(6.dp))
                        var showDialog by remember { mutableStateOf(false) }
                        Text("Set limit ›", color = AccentTeal,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.clickable { showDialog = true })
                        if (showDialog) {
                            SetLimitDialog(
                                onDismiss = { showDialog = false },
                                onConfirm = { v -> showDialog = false; onSetLimit(v) },
                            )
                        }
                    }
                }
            } else if (util != null) {
                // Unknown limit (e.g. IDFC, no Avl Limit): one subtle line, no fake bar.
                Spacer(Modifier.height(10.dp))
                var showDialog by remember { mutableStateOf(false) }
                Text("Set limit to track utilization ›", color = AccentTeal,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clickable { showDialog = true })
                if (showDialog) {
                    SetLimitDialog(
                        onDismiss = { showDialog = false },
                        onConfirm = { v -> showDialog = false; onSetLimit(v) },
                    )
                }
            }
```

Add the imports needed by the expand idiom: `androidx.compose.animation.animateContentSize`,
`androidx.compose.foundation.layout.Row`/`width`, `androidx.compose.ui.Alignment`,
`androidx.compose.ui.text.font.FontWeight` (most already present in this file — add any missing).

Add the dialog composable at the bottom of the file:

```kotlin
@Composable
private fun SetLimitDialog(onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var text by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set credit limit") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text, onValueChange = { text = it.filter { ch -> ch.isDigit() } },
                singleLine = true, label = { Text("Sanctioned limit (₹)") },
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { text.toDoubleOrNull()?.let(onConfirm) },
            ) { Text("Save", color = AccentTeal) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
    )
}
```

Ensure imports exist (add any missing): `androidx.compose.foundation.clickable`,
`androidx.compose.foundation.background`, `androidx.compose.foundation.shape.RoundedCornerShape`,
`androidx.compose.ui.draw.clip`, `androidx.compose.ui.graphics.Color`,
`androidx.compose.runtime.{mutableStateOf,remember,getValue,setValue}`,
`androidx.compose.foundation.layout.{height,fillMaxWidth,Spacer}`, theme colors
`AccentTeal`/`SurfaceMid`/`TextSecondary`.

- [ ] **Step 4: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -6`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/ui/bills/BillsUiState.kt android/app/src/main/java/com/ledgernoir/tracker/ui/bills/BillsViewModel.kt android/app/src/main/java/com/ledgernoir/tracker/ui/bills/BillsScreen.kt
git commit -m "$(cat <<'EOF'
feat(android): per-card utilization bar + set-limit dialog on Bills

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## PHASE C — Verification

### Task 8: Build, install, verify on device

**Use the `run-app` skill.** Device `SM-S901E` at `192.168.1.2` (re-discover port via
`adb mdns services`). Backend on `:8000` with the real `emu.db` (which carries the Axis
card `Avl Limit` history). **Apply the schema migration first** (the live `emu.db` lacks
the new `credit_limit` column — `create_all` won't add it):

- [ ] **Step 1: Migrate emu.db + backend suite**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker/backend
cp emu.db emu.db.bak.util.$(date +%s)
.venv/bin/python -c "
import sqlite3
con=sqlite3.connect('emu.db'); cur=con.cursor()
cols=[r[1] for r in cur.execute('PRAGMA table_info(accounts)')]
if 'credit_limit' not in cols:
    cur.execute('ALTER TABLE accounts ADD COLUMN credit_limit FLOAT'); con.commit(); print('added accounts.credit_limit')
else: print('already present')
con.close()"
.venv/bin/pytest -q       # all pass
```

- [ ] **Step 2: Build + install + launch**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker/android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ADB=~/Library/Android/sdk/platform-tools/adb
DEV=$($ADB devices | awk '/device$/{print $1; exit}')
./gradlew :app:assembleDebug --console=plain 2>&1 | tail -3       # BUILD SUCCESSFUL
$ADB -s "$DEV" install -r app/build/outputs/apk/debug/app-debug.apk
$ADB -s "$DEV" reverse tcp:8000 tcp:8000
$ADB -s "$DEV" shell am start -n com.ledgernoir.tracker/.MainActivity
```

- [ ] **Step 3: Verify**

  - `curl -s localhost:8000/cards/utilization | head -c 400` → Axis cards show `utilization_pct` + `source: observed`.
  - Bills screen: each Axis card shows the utilization bar at the right color + "N% used · ₹X of ₹Y". IDFC cards show "Set credit limit ›".
  - Tap "Set credit limit ›" on an IDFC card, enter a value, Save → the bar appears.
  - Tapping a Bills utilization bar expands the `₹used · ₹available · as of` breakdown.
  - Insights → Money Radar: a high-utilization finding appears if any card ≥30%.
  - Screenshot each (`$ADB -s "$DEV" exec-out screencap -p > /tmp/u.png`).

- [ ] **Step 4: Backend suite still green**

Run: `cd backend && .venv/bin/pytest -q`
Expected: all pass.

---

## Self-Review notes (author)
- **Spec coverage:** credit_limit column → Task 1; limit_scan → Task 2; utilization service
  (override/heuristic/unknown) → Task 3; routes → Task 4; Money Radar detector → Task 5;
  DTOs/API/use-cases → Task 6; Bills collapsed bar + expand + set-limit → Task 7;
  device verify + migration → Task 8. UI refined for progressive disclosure (one
  glanceable line per card, detail on tap) with NO dashboard chip to avoid overload.
  device verify + migration → Task 9.
- **Type consistency:** `utilization_pct`/`total_limit`/`source` keys consistent service↔
  router↔DTO (`utilizationPct`/`totalLimit`/`source`). `getCardUtilization`/`setCardLimit`
  use-case names consistent Tasks 6/7/8. Detector `type="utilization"` matches the test.
- **Known migration gotcha (flagged):** Task 9 Step 1 adds `accounts.credit_limit` via
  ALTER (create_all won't alter existing tables) — same class of issue recorded in memory
  for `user_profile.name`.
- **YAGNI:** no utilization history; statement-PDF limit parse deferred.
