# Expense Tracker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a local-first, single-user expense tracker for India (INR) that ingests bank/card alerts from both email and SMS, deduplicates them into one ledger, and provides categorization, budgets, dashboards, and recurring detection across a backend, React web dashboard, and native Android app.

**Architecture:** A FastAPI backend (async, repository + entity patterns) owns a PostgreSQL database. Raw messages are stored before parsing; per-bank parsers produce transactions; a fingerprint + time-window dedup engine collapses cross-channel duplicates; an enrichment layer categorizes, detects recurring charges, and evaluates budgets. A React web app and a native Kotlin Android app (which also forwards SMS) consume a REST API.

**Tech Stack:** Python 3.11, FastAPI, SQLAlchemy 2.0 (async) + asyncpg, Alembic, Pydantic v2, pytest + pytest-asyncio, PostgreSQL 16, Docker Compose, React + Vite + TypeScript, Recharts, Kotlin + Jetpack Compose + Retrofit.

---

## Phases

- **Phase A — Backend core pipeline:** project scaffold, DB, entities, repositories, raw-message storage, parsers, dedup.
- **Phase B — Enrichment:** categorizer, recurring detector, budget evaluator.
- **Phase C — Ingestion connectors:** Gmail poller, SMS webhook.
- **Phase D — REST API + React web dashboard.**
- **Phase E — Native Kotlin Android app.**
- **Phase F — Expanded scope (from research):** entity extension, balance/net-worth, zero-based budgeting, manual entry, edit/split, search, CSV export, app-lock.
- **Phase G — Premium dark UI** (revises Phase D's web client; built with the `frontend-design` skill).

Conventions: every code task follows TDD (write failing test → run it fail → implement → run it pass → commit). Commit messages use Conventional Commits.

---

## ⚠️ SCOPE AMENDMENT (2026-06-02) — READ BEFORE IMPLEMENTING

After Phases A1–A5 were built, industry research (`docs/superpowers/specs/2026-06-02-research-features-and-uiux.md`) and user decisions expanded the scope. The **updated source of truth is the spec** `docs/superpowers/specs/2026-06-02-expense-tracker-design.md`. Deltas vs. the original Phase A–E text below:

1. **New entity fields/classes** (see spec §6), implemented in **Phase F1/F2**:
   - `Account`: add `type` value `"cash"` and nullable `current_balance`.
   - `Transaction`: add `entry_type` (`"auto"`/`"manual"`, default `"auto"`), nullable `note`, nullable `balance_after`.
   - New entities: `TransactionSplit`, `BalanceSnapshot`, `BudgetAssignment`, `MonthlyIncome`.
2. **Parsers (A8/A9/A9b) must also capture `balance_after`** from "Avl Bal"/"Avl Lmt" when present. `ParsedTransaction` (A6) gains `balance_after: float | None = None`. When dispatching parser implementers, include this requirement.
3. **Budgeting is ZERO-BASED**, not simple per-category limits. Phase **F5** (`ZeroBasedBudgetService`) supersedes Task **B5** (`evaluate_budget` is retained only as a low-level helper for approach/exceed status).
4. **New backend features** (Phase F): balance/net-worth service, manual/cash entry, edit + recategorize + **split**, search/filter, CSV export, app-lock (PIN/biometric + auth).
5. **Web UI** is **premium dark + vibrant** with hero = "Spent this month" and secondary "Ready to assign". Phase **G** replaces the basic Tasks **D4/D5** UI; build it with the `frontend-design` skill. (The API tasks D1–D3 and the new Phase F endpoints remain valid.)

Everything not contradicted above still applies as written.

---

# Phase A — Backend Core Pipeline

### Task A1: Project scaffold and tooling

**Files:**
- Create: `backend/pyproject.toml`
- Create: `backend/app/__init__.py`
- Create: `backend/app/config.py`
- Create: `backend/tests/__init__.py`
- Create: `backend/tests/test_config.py`
- Create: `backend/.gitignore`

- [ ] **Step 1: Create `backend/.gitignore`**

```gitignore
__pycache__/
*.pyc
.venv/
.env
.pytest_cache/
.coverage
```

- [ ] **Step 2: Create `backend/pyproject.toml`**

```toml
[project]
name = "expense-tracker-backend"
version = "0.1.0"
requires-python = ">=3.11"
dependencies = [
    "fastapi>=0.110",
    "uvicorn[standard]>=0.29",
    "sqlalchemy[asyncio]>=2.0",
    "asyncpg>=0.29",
    "alembic>=1.13",
    "pydantic>=2.6",
    "pydantic-settings>=2.2",
    "google-api-python-client>=2.0",
    "google-auth-oauthlib>=1.2",
]

[project.optional-dependencies]
dev = [
    "pytest>=8.0",
    "pytest-asyncio>=0.23",
    "httpx>=0.27",
    "aiosqlite>=0.20",
]

[tool.pytest.ini_options]
asyncio_mode = "auto"
testpaths = ["tests"]
```

- [ ] **Step 3: Write the failing test** in `backend/tests/test_config.py`

```python
from app.config import Settings


def test_settings_have_defaults():
    s = Settings(_env_file=None)
    assert s.dedup_window_hours == 36
    assert s.default_currency == "INR"
```

- [ ] **Step 4: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_config.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.config'`

- [ ] **Step 5: Create `backend/app/__init__.py`** (empty file) and **`backend/app/config.py`**

```python
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="ET_")

    database_url: str = "postgresql+asyncpg://expense:expense@localhost:5432/expense"
    dedup_window_hours: int = 36
    default_currency: str = "INR"


settings = Settings()
```

- [ ] **Step 6: Create `backend/tests/__init__.py`** (empty file)

- [ ] **Step 7: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_config.py -v`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add backend/
git commit -m "chore: scaffold backend project with config"
```

---

### Task A2: Docker Compose for Postgres

**Files:**
- Create: `docker-compose.yml`
- Create: `backend/.env.example`

- [ ] **Step 1: Create `docker-compose.yml`**

```yaml
services:
  db:
    image: postgres:16
    environment:
      POSTGRES_USER: expense
      POSTGRES_PASSWORD: expense
      POSTGRES_DB: expense
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data

volumes:
  pgdata:
```

- [ ] **Step 2: Create `backend/.env.example`**

```dotenv
ET_DATABASE_URL=postgresql+asyncpg://expense:expense@localhost:5432/expense
ET_DEDUP_WINDOW_HOURS=36
ET_DEFAULT_CURRENCY=INR
```

- [ ] **Step 3: Start the database and verify**

Run: `docker compose up -d db && docker compose ps`
Expected: `db` service shows state `running`.

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml backend/.env.example
git commit -m "chore: add postgres docker-compose and env example"
```

---

### Task A3: Database engine and session factory

**Files:**
- Create: `backend/app/db.py`
- Create: `backend/tests/conftest.py`
- Create: `backend/tests/test_db.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_db.py`

```python
from app.db import Base, new_session_factory


async def test_session_factory_creates_schema():
    engine, Session = new_session_factory("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    async with Session() as session:
        assert session is not None
    await engine.dispose()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_db.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.db'`

- [ ] **Step 3: Create `backend/app/db.py`**

```python
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    pass


def new_session_factory(database_url: str):
    engine = create_async_engine(database_url, future=True)
    Session = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    return engine, Session
```

- [ ] **Step 4: Create `backend/tests/conftest.py`** (shared in-memory DB fixtures)

```python
import pytest_asyncio

from app.db import Base, new_session_factory


@pytest_asyncio.fixture
async def session():
    engine, Session = new_session_factory("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    async with Session() as s:
        yield s
    await engine.dispose()
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_db.py -v`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/app/db.py backend/tests/conftest.py backend/tests/test_db.py
git commit -m "feat: add async db engine and session factory"
```

---

### Task A4: Entity models (Account, RawMessage, Transaction, Category, Budget, RecurringSeries)

**Files:**
- Create: `backend/app/entities.py`
- Create: `backend/tests/test_entities.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_entities.py`

```python
from datetime import datetime

from app.entities import Account, RawMessage, Transaction


async def test_can_persist_account_message_transaction(session):
    account = Account(bank="AXIS", type="card", last4="1234", currency="INR")
    session.add(account)
    await session.flush()

    msg = RawMessage(channel="sms", sender="AX-AXISBK", body="spent", received_at=datetime(2026, 6, 1, 10, 0))
    session.add(msg)
    await session.flush()

    txn = Transaction(
        amount=100.0,
        direction="debit",
        merchant="SWIGGY",
        occurred_at=datetime(2026, 6, 1, 10, 0),
        account_id=account.id,
        fingerprint="1234|100.0|debit|swiggy",
    )
    session.add(txn)
    await session.flush()

    assert account.id is not None
    assert txn.account_id == account.id
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_entities.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.entities'`

- [ ] **Step 3: Create `backend/app/entities.py`**

```python
from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, Float, ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db import Base


class Account(Base):
    __tablename__ = "accounts"

    id: Mapped[int] = mapped_column(primary_key=True)
    bank: Mapped[str] = mapped_column(String(64))
    type: Mapped[str] = mapped_column(String(16))  # "card" | "bank"
    last4: Mapped[str] = mapped_column(String(4))
    currency: Mapped[str] = mapped_column(String(8), default="INR")


class Category(Base):
    __tablename__ = "categories"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(64), unique=True)
    parent_id: Mapped[int | None] = mapped_column(ForeignKey("categories.id"), nullable=True)


class RawMessage(Base):
    __tablename__ = "raw_messages"

    id: Mapped[int] = mapped_column(primary_key=True)
    channel: Mapped[str] = mapped_column(String(8))  # "email" | "sms"
    sender: Mapped[str] = mapped_column(String(128))
    body: Mapped[str] = mapped_column(String)
    received_at: Mapped[datetime] = mapped_column(DateTime)
    external_id: Mapped[str | None] = mapped_column(String(256), nullable=True, unique=True)
    parsed_status: Mapped[str] = mapped_column(String(16), default="pending")
    transaction_id: Mapped[int | None] = mapped_column(ForeignKey("transactions.id"), nullable=True)

    transaction: Mapped["Transaction | None"] = relationship(back_populates="sources")


class Transaction(Base):
    __tablename__ = "transactions"

    id: Mapped[int] = mapped_column(primary_key=True)
    amount: Mapped[float] = mapped_column(Float)
    direction: Mapped[str] = mapped_column(String(8))  # "debit" | "credit"
    merchant: Mapped[str] = mapped_column(String(256))
    occurred_at: Mapped[datetime] = mapped_column(DateTime)
    account_id: Mapped[int] = mapped_column(ForeignKey("accounts.id"))
    category_id: Mapped[int | None] = mapped_column(ForeignKey("categories.id"), nullable=True)
    fingerprint: Mapped[str] = mapped_column(String(512), index=True)

    sources: Mapped[list[RawMessage]] = relationship(back_populates="transaction")


class Budget(Base):
    __tablename__ = "budgets"

    id: Mapped[int] = mapped_column(primary_key=True)
    category_id: Mapped[int] = mapped_column(ForeignKey("categories.id"))
    period: Mapped[str] = mapped_column(String(16), default="monthly")
    limit_amount: Mapped[float] = mapped_column(Float)


class RecurringSeries(Base):
    __tablename__ = "recurring_series"

    id: Mapped[int] = mapped_column(primary_key=True)
    merchant: Mapped[str] = mapped_column(String(256))
    account_id: Mapped[int] = mapped_column(ForeignKey("accounts.id"))
    cadence_days: Mapped[int] = mapped_column(default=30)
    expected_amount: Mapped[float] = mapped_column(Float)
    last_seen_at: Mapped[datetime] = mapped_column(DateTime)
    next_expected_at: Mapped[datetime] = mapped_column(DateTime)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_entities.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/app/entities.py backend/tests/test_entities.py
git commit -m "feat: add SQLAlchemy entity models"
```

---

### Task A5: Repositories (data access layer)

**Files:**
- Create: `backend/app/repositories.py`
- Create: `backend/tests/test_repositories.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_repositories.py`

```python
from datetime import datetime

from app.entities import RawMessage
from app.repositories import AccountRepository, RawMessageRepository, TransactionRepository


async def test_account_get_or_create_is_idempotent(session):
    repo = AccountRepository(session)
    a1 = await repo.get_or_create(bank="AXIS", type="card", last4="1234")
    a2 = await repo.get_or_create(bank="AXIS", type="card", last4="1234")
    await session.flush()
    assert a1.id == a2.id


async def test_raw_message_external_id_dedup(session):
    repo = RawMessageRepository(session)
    m1 = RawMessage(channel="email", sender="x", body="b", received_at=datetime(2026, 6, 1), external_id="gmail-1")
    created = await repo.add_if_new(m1)
    assert created is not None
    dup = RawMessage(channel="email", sender="x", body="b", received_at=datetime(2026, 6, 1), external_id="gmail-1")
    again = await repo.add_if_new(dup)
    assert again is None


async def test_transaction_find_recent_by_fingerprint(session):
    acc = await AccountRepository(session).get_or_create(bank="AXIS", type="card", last4="1234")
    await session.flush()
    repo = TransactionRepository(session)
    txn = await repo.create(
        amount=100.0, direction="debit", merchant="SWIGGY",
        occurred_at=datetime(2026, 6, 1, 10, 0), account_id=acc.id,
        fingerprint="1234|100.0|debit|swiggy",
    )
    await session.flush()
    found = await repo.find_recent_by_fingerprint(
        "1234|100.0|debit|swiggy", before=datetime(2026, 6, 1, 20, 0), window_hours=36
    )
    assert found is not None and found.id == txn.id
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_repositories.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.repositories'`

- [ ] **Step 3: Create `backend/app/repositories.py`**

```python
from __future__ import annotations

from datetime import datetime, timedelta

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.entities import Account, RawMessage, Transaction


class AccountRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def get_or_create(self, *, bank: str, type: str, last4: str, currency: str = "INR") -> Account:
        stmt = select(Account).where(
            Account.bank == bank, Account.type == type, Account.last4 == last4
        )
        existing = (await self.session.execute(stmt)).scalar_one_or_none()
        if existing is not None:
            return existing
        account = Account(bank=bank, type=type, last4=last4, currency=currency)
        self.session.add(account)
        return account


class RawMessageRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def add_if_new(self, message: RawMessage) -> RawMessage | None:
        if message.external_id is not None:
            stmt = select(RawMessage).where(RawMessage.external_id == message.external_id)
            if (await self.session.execute(stmt)).scalar_one_or_none() is not None:
                return None
        self.session.add(message)
        return message

    async def list_pending(self) -> list[RawMessage]:
        stmt = select(RawMessage).where(RawMessage.parsed_status == "pending")
        return list((await self.session.execute(stmt)).scalars())


class TransactionRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def create(self, **kwargs) -> Transaction:
        txn = Transaction(**kwargs)
        self.session.add(txn)
        return txn

    async def find_recent_by_fingerprint(
        self, fingerprint: str, *, before: datetime, window_hours: int
    ) -> Transaction | None:
        earliest = before - timedelta(hours=window_hours)
        stmt = (
            select(Transaction)
            .where(
                Transaction.fingerprint == fingerprint,
                Transaction.occurred_at >= earliest,
                Transaction.occurred_at <= before,
            )
            .order_by(Transaction.occurred_at.desc())
        )
        return (await self.session.execute(stmt)).scalars().first()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_repositories.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/app/repositories.py backend/tests/test_repositories.py
git commit -m "feat: add account, raw message, and transaction repositories"
```

---

### Task A6: Parsed-transaction value object and merchant normalization

**Files:**
- Create: `backend/app/parsing/__init__.py`
- Create: `backend/app/parsing/types.py`
- Create: `backend/tests/test_parsing_types.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_parsing_types.py`

```python
from datetime import datetime

from app.parsing.types import ParsedTransaction, normalize_merchant


def test_normalize_merchant_lowercases_and_strips():
    assert normalize_merchant("  SWIGGY*Order 123 ") == "swiggy order 123"


def test_parsed_transaction_fingerprint():
    pt = ParsedTransaction(
        bank="AXIS", account_type="card", last4="1234", amount=100.0,
        direction="debit", merchant="SWIGGY", occurred_at=datetime(2026, 6, 1),
    )
    assert pt.fingerprint() == "1234|100.0|debit|swiggy"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_parsing_types.py -v`
Expected: FAIL with `ModuleNotFoundError`

- [ ] **Step 3: Create `backend/app/parsing/__init__.py`** (empty) and **`backend/app/parsing/types.py`**

```python
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime


def normalize_merchant(raw: str) -> str:
    cleaned = re.sub(r"[^a-z0-9]+", " ", raw.lower())
    return cleaned.strip()


@dataclass(frozen=True)
class ParsedTransaction:
    bank: str
    account_type: str  # "card" | "bank"
    last4: str
    amount: float
    direction: str  # "debit" | "credit"
    merchant: str
    occurred_at: datetime

    def fingerprint(self) -> str:
        return f"{self.last4}|{self.amount}|{self.direction}|{normalize_merchant(self.merchant)}"
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_parsing_types.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/app/parsing/
git add backend/tests/test_parsing_types.py
git commit -m "feat: add ParsedTransaction value object and merchant normalization"
```

---

### Task A7: Parser interface and registry

**Files:**
- Create: `backend/app/parsing/base.py`
- Create: `backend/app/parsing/registry.py`
- Create: `backend/tests/test_parser_registry.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_parser_registry.py`

```python
from datetime import datetime

from app.parsing.base import BankParser
from app.parsing.registry import BankParserRegistry
from app.parsing.types import ParsedTransaction


class FakeParser(BankParser):
    def matches(self, sender: str, body: str) -> bool:
        return "FAKEBNK" in sender

    def parse(self, sender: str, body: str, received_at: datetime) -> ParsedTransaction:
        return ParsedTransaction(
            bank="FAKE", account_type="card", last4="0000", amount=1.0,
            direction="debit", merchant="X", occurred_at=received_at,
        )


def test_registry_returns_none_when_no_match():
    reg = BankParserRegistry([FakeParser()])
    assert reg.parse("OTHER", "body", datetime(2026, 6, 1)) is None


def test_registry_uses_first_matching_parser():
    reg = BankParserRegistry([FakeParser()])
    result = reg.parse("FAKEBNK", "body", datetime(2026, 6, 1))
    assert result is not None and result.bank == "FAKE"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_parser_registry.py -v`
Expected: FAIL with `ModuleNotFoundError`

- [ ] **Step 3: Create `backend/app/parsing/base.py`**

```python
from __future__ import annotations

from abc import ABC, abstractmethod
from datetime import datetime

from app.parsing.types import ParsedTransaction


class BankParser(ABC):
    @abstractmethod
    def matches(self, sender: str, body: str) -> bool:
        ...

    @abstractmethod
    def parse(self, sender: str, body: str, received_at: datetime) -> ParsedTransaction:
        ...
```

- [ ] **Step 4: Create `backend/app/parsing/registry.py`**

```python
from __future__ import annotations

from datetime import datetime

from app.parsing.base import BankParser
from app.parsing.types import ParsedTransaction


class BankParserRegistry:
    def __init__(self, parsers: list[BankParser]):
        self._parsers = parsers

    def parse(self, sender: str, body: str, received_at: datetime) -> ParsedTransaction | None:
        for parser in self._parsers:
            if parser.matches(sender, body):
                return parser.parse(sender, body, received_at)
        return None
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_parser_registry.py -v`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/app/parsing/base.py backend/app/parsing/registry.py backend/tests/test_parser_registry.py
git commit -m "feat: add bank parser interface and registry"
```

---

### Task A8: Axis Bank parser (credit card + savings account)

Axis is the user's only institution that is *both* a credit card and a savings
account, so this parser must distinguish the two by whether the message names a
"Card no." or an "A/c no.".

**Files:**
- Create: `backend/app/parsing/banks/__init__.py`
- Create: `backend/app/parsing/banks/axis.py`
- Create: `backend/tests/test_parser_axis.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_parser_axis.py`

```python
from datetime import datetime

from app.parsing.banks.axis import AxisParser


def test_axis_credit_card_debit_sms():
    parser = AxisParser()
    body = "INR 450 spent on AXIS BANK Credit Card no. XX1234 at SWIGGY on 01-06-2026. Avl Lmt INR 49550"
    assert parser.matches("AX-AXISBK", body)
    pt = parser.parse("AX-AXISBK", body, datetime(2026, 6, 1, 10, 0))
    assert pt.last4 == "1234"
    assert pt.amount == 450.0
    assert pt.direction == "debit"
    assert pt.account_type == "card"
    assert "SWIGGY" in pt.merchant


def test_axis_savings_debit_sms():
    parser = AxisParser()
    body = "INR 500 debited from A/c no. XX5678 on 01-06-2026 to SWIGGY UPI. Avl Bal INR 10000 - Axis Bank"
    pt = parser.parse("AX-AXISBK", body, datetime(2026, 6, 1, 11, 0))
    assert pt.direction == "debit"
    assert pt.last4 == "5678"
    assert pt.account_type == "bank"
    assert pt.amount == 500.0
    assert "SWIGGY" in pt.merchant


def test_axis_savings_credit_sms():
    parser = AxisParser()
    body = "INR 2000 credited to A/c no. XX5678 on 01-06-2026 from JOHN UPI. Avl Bal INR 12000 - Axis Bank"
    pt = parser.parse("AX-AXISBK", body, datetime(2026, 6, 1, 12, 0))
    assert pt.direction == "credit"
    assert pt.account_type == "bank"
    assert pt.amount == 2000.0
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_parser_axis.py -v`
Expected: FAIL with `ModuleNotFoundError`

- [ ] **Step 3: Create `backend/app/parsing/banks/__init__.py`** (empty) and **`backend/app/parsing/banks/axis.py`**

```python
from __future__ import annotations

import re
from datetime import datetime

from app.parsing.base import BankParser
from app.parsing.types import ParsedTransaction

_AMOUNT = re.compile(r"inr\s*([\d,]+\.?\d*)", re.IGNORECASE)
_CARD = re.compile(r"card\s+no\.?\s*xx(\d{4})", re.IGNORECASE)
_ACCT = re.compile(r"a/?c\s+no\.?\s*xx(\d{4})", re.IGNORECASE)
_AT = re.compile(r"\bat\s+([A-Za-z0-9 .*&_-]+?)\s+on\b", re.IGNORECASE)
_TO = re.compile(r"\bto\s+([A-Za-z0-9 .*&_-]+?)\s+(?:upi|on)\b", re.IGNORECASE)
_FROM = re.compile(r"\bfrom\s+([A-Za-z0-9 .*&_-]+?)\s+(?:upi|on)\b", re.IGNORECASE)


class AxisParser(BankParser):
    def matches(self, sender: str, body: str) -> bool:
        return "AXIS" in sender.upper() or "axis bank" in body.lower()

    def parse(self, sender: str, body: str, received_at: datetime) -> ParsedTransaction:
        amount = float(_AMOUNT.search(body).group(1).replace(",", ""))
        is_credit = "credited" in body.lower()
        direction = "credit" if is_credit else "debit"

        card_match = _CARD.search(body)
        acct_match = _ACCT.search(body)
        if card_match:
            last4, account_type = card_match.group(1), "card"
        elif acct_match:
            last4, account_type = acct_match.group(1), "bank"
        else:
            last4, account_type = "0000", "card"

        merchant_match = _AT.search(body) or _TO.search(body) or _FROM.search(body)
        merchant = merchant_match.group(1).strip() if merchant_match else "UNKNOWN"

        return ParsedTransaction(
            bank="AXIS", account_type=account_type, last4=last4, amount=amount,
            direction=direction, merchant=merchant, occurred_at=received_at,
        )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_parser_axis.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/app/parsing/banks/__init__.py backend/app/parsing/banks/axis.py backend/tests/test_parser_axis.py
git commit -m "feat: add Axis Bank parser (card and savings)"
```

---

### Task A9: IDFC First Bank parser (credit card)

**Files:**
- Create: `backend/app/parsing/banks/idfc.py`
- Create: `backend/tests/test_parser_idfc.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_parser_idfc.py`

```python
from datetime import datetime

from app.parsing.banks.idfc import IdfcParser


def test_idfc_card_debit_sms():
    parser = IdfcParser()
    body = "Rs.1,250 spent on your IDFC FIRST Bank Credit Card XX9876 at AMAZON on 01-Jun-2026. Avl Lmt Rs.40000"
    assert parser.matches("VM-IDFCFB", body)
    pt = parser.parse("VM-IDFCFB", body, datetime(2026, 6, 1, 12, 0))
    assert pt.last4 == "9876"
    assert pt.amount == 1250.0
    assert pt.direction == "debit"
    assert pt.account_type == "card"
    assert "AMAZON" in pt.merchant


def test_idfc_card_refund_credit_sms():
    parser = IdfcParser()
    body = "Rs.300 credited to your IDFC FIRST Bank Credit Card XX9876 from FLIPKART on 02-Jun-2026."
    pt = parser.parse("VM-IDFCFB", body, datetime(2026, 6, 2, 9, 0))
    assert pt.direction == "credit"
    assert pt.amount == 300.0
    assert "FLIPKART" in pt.merchant
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_parser_idfc.py -v`
Expected: FAIL with `ModuleNotFoundError`

- [ ] **Step 3: Create `backend/app/parsing/banks/idfc.py`**

```python
from __future__ import annotations

import re
from datetime import datetime

from app.parsing.base import BankParser
from app.parsing.types import ParsedTransaction

_AMOUNT = re.compile(r"rs\.?\s*([\d,]+\.?\d*)", re.IGNORECASE)
_CARD = re.compile(r"card\s+(?:ending\s+)?xx?(\d{4})", re.IGNORECASE)
_AT = re.compile(r"\bat\s+([A-Za-z0-9 .*&_-]+?)\s+on\b", re.IGNORECASE)
_FROM = re.compile(r"\bfrom\s+([A-Za-z0-9 .*&_-]+?)\s+on\b", re.IGNORECASE)


class IdfcParser(BankParser):
    def matches(self, sender: str, body: str) -> bool:
        return "IDFC" in sender.upper() or "idfc first" in body.lower()

    def parse(self, sender: str, body: str, received_at: datetime) -> ParsedTransaction:
        amount = float(_AMOUNT.search(body).group(1).replace(",", ""))
        is_credit = "credited" in body.lower()
        direction = "credit" if is_credit else "debit"

        card_match = _CARD.search(body)
        last4 = card_match.group(1) if card_match else "0000"

        merchant_match = _AT.search(body) or _FROM.search(body)
        merchant = merchant_match.group(1).strip() if merchant_match else "UNKNOWN"

        return ParsedTransaction(
            bank="IDFC", account_type="card", last4=last4, amount=amount,
            direction=direction, merchant=merchant, occurred_at=received_at,
        )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_parser_idfc.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/app/parsing/banks/idfc.py backend/tests/test_parser_idfc.py
git commit -m "feat: add IDFC First Bank credit card parser"
```

---

### Task A9b: Bank of Baroda parser (savings account)

**Files:**
- Create: `backend/app/parsing/banks/bob.py`
- Create: `backend/tests/test_parser_bob.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_parser_bob.py`

```python
from datetime import datetime

from app.parsing.banks.bob import BobParser


def test_bob_credit_sms():
    parser = BobParser()
    body = "Rs.5000 Credited to A/c XX4321 on 01-06-2026 by UPI from JANE. Avl Bal Rs.10000 -Bank of Baroda"
    assert parser.matches("VK-BOBTXN", body)
    pt = parser.parse("VK-BOBTXN", body, datetime(2026, 6, 1, 13, 0))
    assert pt.last4 == "4321"
    assert pt.amount == 5000.0
    assert pt.direction == "credit"
    assert pt.account_type == "bank"
    assert "JANE" in pt.merchant


def test_bob_debit_sms():
    parser = BobParser()
    body = "Rs.700 Debited from A/c XX4321 on 02-06-2026 to BIGBASKET by UPI. Avl Bal Rs.9300 -Bank of Baroda"
    pt = parser.parse("VK-BOBTXN", body, datetime(2026, 6, 2, 8, 0))
    assert pt.direction == "debit"
    assert pt.amount == 700.0
    assert "BIGBASKET" in pt.merchant
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_parser_bob.py -v`
Expected: FAIL with `ModuleNotFoundError`

- [ ] **Step 3: Create `backend/app/parsing/banks/bob.py`**

```python
from __future__ import annotations

import re
from datetime import datetime

from app.parsing.base import BankParser
from app.parsing.types import ParsedTransaction

_AMOUNT = re.compile(r"rs\.?\s*([\d,]+\.?\d*)", re.IGNORECASE)
_ACCT = re.compile(r"a/?c\s*(?:no\.?\s*)?xx(\d{4})", re.IGNORECASE)
_FROM = re.compile(r"\bfrom\s+([A-Za-z0-9 .*&_-]+?)\.", re.IGNORECASE)
_TO = re.compile(r"\bto\s+([A-Za-z0-9 .*&_-]+?)\s+(?:by|on)\b", re.IGNORECASE)


class BobParser(BankParser):
    def matches(self, sender: str, body: str) -> bool:
        return "BOB" in sender.upper() or "bank of baroda" in body.lower()

    def parse(self, sender: str, body: str, received_at: datetime) -> ParsedTransaction:
        amount = float(_AMOUNT.search(body).group(1).replace(",", ""))
        is_credit = "credited" in body.lower()
        direction = "credit" if is_credit else "debit"

        acct_match = _ACCT.search(body)
        last4 = acct_match.group(1) if acct_match else "0000"

        merchant_match = _FROM.search(body) if is_credit else _TO.search(body)
        merchant = merchant_match.group(1).strip() if merchant_match else "UNKNOWN"

        return ParsedTransaction(
            bank="BOB", account_type="bank", last4=last4, amount=amount,
            direction=direction, merchant=merchant, occurred_at=received_at,
        )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_parser_bob.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/app/parsing/banks/bob.py backend/tests/test_parser_bob.py
git commit -m "feat: add Bank of Baroda savings parser"
```

---

### Task A10: Default registry factory

**Files:**
- Modify: `backend/app/parsing/registry.py`
- Create: `backend/tests/test_default_registry.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_default_registry.py`

```python
from datetime import datetime

from app.parsing.registry import default_registry


def test_default_registry_parses_all_user_banks():
    reg = default_registry()
    axis = reg.parse("AX-AXISBK", "INR 10 spent on AXIS BANK Credit Card no. XX1111 at SHOP on 01-06-2026.", datetime(2026, 6, 1))
    idfc = reg.parse("VM-IDFCFB", "Rs.20 spent on your IDFC FIRST Bank Credit Card XX2222 at STORE on 01-Jun-2026.", datetime(2026, 6, 1))
    bob = reg.parse("VK-BOBTXN", "Rs.30 Credited to A/c XX3333 on 01-06-2026 by UPI from JOHN. -Bank of Baroda", datetime(2026, 6, 1))
    assert axis.bank == "AXIS"
    assert idfc.bank == "IDFC"
    assert bob.bank == "BOB"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_default_registry.py -v`
Expected: FAIL with `ImportError: cannot import name 'default_registry'`

- [ ] **Step 3: Append to `backend/app/parsing/registry.py`**

```python
def default_registry() -> "BankParserRegistry":
    from app.parsing.banks.axis import AxisParser
    from app.parsing.banks.bob import BobParser
    from app.parsing.banks.idfc import IdfcParser

    return BankParserRegistry([AxisParser(), IdfcParser(), BobParser()])
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_default_registry.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/app/parsing/registry.py backend/tests/test_default_registry.py
git commit -m "feat: add default parser registry with Axis, IDFC, and Bank of Baroda"
```

---

### Task A11: Dedup engine

**Files:**
- Create: `backend/app/dedup.py`
- Create: `backend/tests/test_dedup.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_dedup.py`

```python
from datetime import datetime

from app.dedup import DedupEngine
from app.parsing.types import ParsedTransaction
from app.repositories import AccountRepository, RawMessageRepository, TransactionRepository
from app.entities import RawMessage


def _pt(channel_amount=100.0, when=datetime(2026, 6, 1, 10, 0)):
    return ParsedTransaction(
        bank="AXIS", account_type="card", last4="1234", amount=channel_amount,
        direction="debit", merchant="SWIGGY", occurred_at=when,
    )


async def _engine(session):
    return DedupEngine(
        accounts=AccountRepository(session),
        transactions=TransactionRepository(session),
        window_hours=36,
    )


async def test_cross_channel_duplicate_collapses(session):
    engine = await _engine(session)
    sms = RawMessage(channel="sms", sender="AX-AXISBK", body="b", received_at=datetime(2026, 6, 1, 10, 0))
    email = RawMessage(channel="email", sender="alerts@axis", body="b", received_at=datetime(2026, 6, 1, 10, 30))
    session.add_all([sms, email])
    await session.flush()

    t1 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 10, 0)), sms)
    t2 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 10, 30)), email)
    await session.flush()

    assert t1.id == t2.id  # same transaction, two sources
    assert {s.channel for s in t1.sources} == {"sms", "email"}


async def test_same_channel_repeat_is_new_transaction(session):
    engine = await _engine(session)
    sms1 = RawMessage(channel="sms", sender="AX-AXISBK", body="b", received_at=datetime(2026, 6, 1, 9, 0))
    sms2 = RawMessage(channel="sms", sender="AX-AXISBK", body="b", received_at=datetime(2026, 6, 1, 9, 5))
    session.add_all([sms1, sms2])
    await session.flush()

    t1 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 9, 0)), sms1)
    t2 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 9, 5)), sms2)
    await session.flush()

    assert t1.id != t2.id  # two genuine coffees, both via SMS


async def test_outside_window_is_new_transaction(session):
    engine = await _engine(session)
    sms = RawMessage(channel="sms", sender="AX-AXISBK", body="b", received_at=datetime(2026, 6, 1, 10, 0))
    email = RawMessage(channel="email", sender="alerts@axis", body="b", received_at=datetime(2026, 6, 5, 10, 0))
    session.add_all([sms, email])
    await session.flush()

    t1 = await engine.ingest(_pt(when=datetime(2026, 6, 1, 10, 0)), sms)
    t2 = await engine.ingest(_pt(when=datetime(2026, 6, 5, 10, 0)), email)
    await session.flush()

    assert t1.id != t2.id  # >36h apart
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_dedup.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.dedup'`

- [ ] **Step 3: Create `backend/app/dedup.py`**

```python
from __future__ import annotations

from app.entities import RawMessage, Transaction
from app.parsing.types import ParsedTransaction
from app.repositories import AccountRepository, TransactionRepository


class DedupEngine:
    def __init__(self, *, accounts: AccountRepository, transactions: TransactionRepository, window_hours: int):
        self.accounts = accounts
        self.transactions = transactions
        self.window_hours = window_hours

    async def ingest(self, pt: ParsedTransaction, source: RawMessage) -> Transaction:
        account = await self.accounts.get_or_create(
            bank=pt.bank, type=pt.account_type, last4=pt.last4
        )
        fingerprint = pt.fingerprint()
        match = await self.transactions.find_recent_by_fingerprint(
            fingerprint, before=pt.occurred_at, window_hours=self.window_hours
        )

        if match is not None and not self._has_source_from_channel(match, source.channel):
            source.transaction = match
            source.parsed_status = "parsed"
            return match

        txn = await self.transactions.create(
            amount=pt.amount, direction=pt.direction, merchant=pt.merchant,
            occurred_at=pt.occurred_at, account_id=account.id, fingerprint=fingerprint,
        )
        source.transaction = txn
        source.parsed_status = "parsed"
        return txn

    @staticmethod
    def _has_source_from_channel(txn: Transaction, channel: str) -> bool:
        return any(s.channel == channel for s in txn.sources)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_dedup.py -v`
Expected: PASS (all three cases)

- [ ] **Step 5: Commit**

```bash
git add backend/app/dedup.py backend/tests/test_dedup.py
git commit -m "feat: add cross-channel dedup engine with same-channel guard"
```

---

### Task A12: Ingestion service (raw store → parse → dedup, with review queue)

**Files:**
- Create: `backend/app/ingestion_service.py`
- Create: `backend/tests/test_ingestion_service.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_ingestion_service.py`

```python
from datetime import datetime

from app.ingestion_service import IngestionService
from app.parsing.registry import default_registry
from sqlalchemy import select
from app.entities import RawMessage, Transaction


async def _service(session):
    return IngestionService(session=session, registry=default_registry(), window_hours=36)


async def test_ingest_unknown_bank_goes_to_review(session):
    svc = await _service(session)
    txn = await svc.ingest_message(
        channel="sms", sender="UNKNOWN", body="random text", received_at=datetime(2026, 6, 1),
        external_id="x1",
    )
    await session.flush()
    assert txn is None
    msg = (await session.execute(select(RawMessage).where(RawMessage.external_id == "x1"))).scalar_one()
    assert msg.parsed_status == "needs_review"


async def test_ingest_known_bank_creates_transaction(session):
    svc = await _service(session)
    txn = await svc.ingest_message(
        channel="sms", sender="AX-AXISBK",
        body="INR 450 spent on AXIS BANK Credit Card no. XX1234 at SWIGGY on 01-06-2026.",
        received_at=datetime(2026, 6, 1, 10, 0), external_id="h1",
    )
    await session.flush()
    assert txn is not None and txn.amount == 450.0


async def test_ingest_is_idempotent_on_external_id(session):
    svc = await _service(session)
    body = "INR 450 spent on AXIS BANK Credit Card no. XX1234 at SWIGGY on 01-06-2026."
    await svc.ingest_message(channel="sms", sender="AX-AXISBK", body=body, received_at=datetime(2026, 6, 1, 10, 0), external_id="dup")
    await svc.ingest_message(channel="sms", sender="AX-AXISBK", body=body, received_at=datetime(2026, 6, 1, 10, 0), external_id="dup")
    await session.flush()
    count = len((await session.execute(select(Transaction))).scalars().all())
    assert count == 1
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_ingestion_service.py -v`
Expected: FAIL with `ModuleNotFoundError`

- [ ] **Step 3: Create `backend/app/ingestion_service.py`**

```python
from __future__ import annotations

from datetime import datetime

from sqlalchemy.ext.asyncio import AsyncSession

from app.dedup import DedupEngine
from app.entities import RawMessage, Transaction
from app.parsing.registry import BankParserRegistry
from app.repositories import AccountRepository, RawMessageRepository, TransactionRepository


class IngestionService:
    def __init__(self, *, session: AsyncSession, registry: BankParserRegistry, window_hours: int):
        self.session = session
        self.registry = registry
        self.messages = RawMessageRepository(session)
        self.dedup = DedupEngine(
            accounts=AccountRepository(session),
            transactions=TransactionRepository(session),
            window_hours=window_hours,
        )

    async def ingest_message(
        self, *, channel: str, sender: str, body: str, received_at: datetime, external_id: str | None
    ) -> Transaction | None:
        message = RawMessage(
            channel=channel, sender=sender, body=body,
            received_at=received_at, external_id=external_id, parsed_status="pending",
        )
        stored = await self.messages.add_if_new(message)
        if stored is None:
            return None  # idempotent: already ingested

        parsed = self.registry.parse(sender, body, received_at)
        if parsed is None:
            stored.parsed_status = "needs_review"
            return None

        return await self.dedup.ingest(parsed, stored)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_ingestion_service.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/app/ingestion_service.py backend/tests/test_ingestion_service.py
git commit -m "feat: add ingestion service tying raw store, parse, dedup, and review queue"
```

---

### Task A13: Alembic migrations + run full Phase A suite against Postgres

**Files:**
- Create: `backend/alembic.ini`
- Create: `backend/migrations/env.py`
- Create: `backend/migrations/script.py.mako`

- [ ] **Step 1: Initialize Alembic**

Run: `cd backend && alembic init -t async migrations`
Then edit `backend/alembic.ini` line `sqlalchemy.url =` to be empty (URL is injected in env.py).

- [ ] **Step 2: Wire metadata in `backend/migrations/env.py`**

Replace the `target_metadata = None` line and URL handling:

```python
from app.config import settings
from app.db import Base
import app.entities  # noqa: F401  ensures models are registered

target_metadata = Base.metadata
config.set_main_option("sqlalchemy.url", settings.database_url)
```

- [ ] **Step 3: Generate the initial migration**

Run: `cd backend && docker compose -f ../docker-compose.yml up -d db && alembic revision --autogenerate -m "initial schema"`
Expected: a new file under `backend/migrations/versions/`.

- [ ] **Step 4: Apply the migration**

Run: `cd backend && alembic upgrade head`
Expected: `Running upgrade ... initial schema`.

- [ ] **Step 5: Run the entire Phase A test suite**

Run: `cd backend && python -m pytest -v`
Expected: all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/alembic.ini backend/migrations/
git commit -m "feat: add alembic migrations and initial schema"
```

---

# Phase B — Enrichment

### Task B1: Rules-based categorizer

**Files:**
- Create: `backend/app/enrichment/__init__.py`
- Create: `backend/app/enrichment/categorizer.py`
- Create: `backend/tests/test_categorizer.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_categorizer.py`

```python
from app.enrichment.categorizer import RuleCategorizer


def test_categorizer_matches_keyword():
    cat = RuleCategorizer({"food": ["swiggy", "zomato"], "shopping": ["amazon"]})
    assert cat.categorize("SWIGGY*Order") == "food"
    assert cat.categorize("AMAZON PAY") == "shopping"


def test_categorizer_returns_uncategorized_when_no_match():
    cat = RuleCategorizer({"food": ["swiggy"]})
    assert cat.categorize("RANDOM MERCHANT") == "uncategorized"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_categorizer.py -v`
Expected: FAIL with `ModuleNotFoundError`

- [ ] **Step 3: Create `backend/app/enrichment/__init__.py`** (empty) and **`backend/app/enrichment/categorizer.py`**

```python
from __future__ import annotations

from app.parsing.types import normalize_merchant


class RuleCategorizer:
    def __init__(self, rules: dict[str, list[str]]):
        self._rules = {cat: [k.lower() for k in kws] for cat, kws in rules.items()}

    def categorize(self, merchant: str) -> str:
        text = normalize_merchant(merchant)
        for category, keywords in self._rules.items():
            if any(kw in text for kw in keywords):
                return category
        return "uncategorized"
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_categorizer.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/app/enrichment/__init__.py backend/app/enrichment/categorizer.py backend/tests/test_categorizer.py
git commit -m "feat: add rules-based categorizer"
```

---

### Task B2: Default category rules + category repository wiring

**Files:**
- Create: `backend/app/enrichment/default_rules.py`
- Modify: `backend/app/repositories.py`
- Create: `backend/tests/test_category_repository.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_category_repository.py`

```python
from app.repositories import CategoryRepository


async def test_category_get_or_create_is_idempotent(session):
    repo = CategoryRepository(session)
    c1 = await repo.get_or_create("food")
    c2 = await repo.get_or_create("food")
    await session.flush()
    assert c1.id == c2.id
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_category_repository.py -v`
Expected: FAIL with `ImportError: cannot import name 'CategoryRepository'`

- [ ] **Step 3: Append `CategoryRepository` to `backend/app/repositories.py`**

```python
from app.entities import Category  # add to existing imports at top


class CategoryRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def get_or_create(self, name: str) -> Category:
        stmt = select(Category).where(Category.name == name)
        existing = (await self.session.execute(stmt)).scalar_one_or_none()
        if existing is not None:
            return existing
        category = Category(name=name)
        self.session.add(category)
        return category
```

- [ ] **Step 4: Create `backend/app/enrichment/default_rules.py`**

```python
DEFAULT_RULES: dict[str, list[str]] = {
    "food": ["swiggy", "zomato", "restaurant", "cafe", "dominos"],
    "shopping": ["amazon", "flipkart", "myntra", "store"],
    "travel": ["uber", "ola", "irctc", "indigo", "makemytrip"],
    "bills": ["electricity", "recharge", "airtel", "jio", "broadband"],
    "groceries": ["bigbasket", "blinkit", "zepto", "dmart"],
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_category_repository.py -v`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/app/enrichment/default_rules.py backend/app/repositories.py backend/tests/test_category_repository.py
git commit -m "feat: add category repository and default categorization rules"
```

---

### Task B3: Wire categorizer into ingestion

**Files:**
- Modify: `backend/app/dedup.py`
- Modify: `backend/app/ingestion_service.py`
- Create: `backend/tests/test_ingestion_categorizes.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_ingestion_categorizes.py`

```python
from datetime import datetime

from app.enrichment.categorizer import RuleCategorizer
from app.ingestion_service import IngestionService
from app.parsing.registry import default_registry
from app.repositories import CategoryRepository


async def test_new_transaction_is_categorized(session):
    svc = IngestionService(
        session=session, registry=default_registry(), window_hours=36,
        categorizer=RuleCategorizer({"food": ["swiggy"]}),
    )
    txn = await svc.ingest_message(
        channel="sms", sender="AX-AXISBK",
        body="INR 450 spent on AXIS BANK Credit Card no. XX1234 at SWIGGY on 01-06-2026.",
        received_at=datetime(2026, 6, 1, 10, 0), external_id="c1",
    )
    await session.flush()
    food = await CategoryRepository(session).get_or_create("food")
    assert txn.category_id == food.id
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_ingestion_categorizes.py -v`
Expected: FAIL with `TypeError` (unexpected keyword `categorizer`)

- [ ] **Step 3: Update `DedupEngine` in `backend/app/dedup.py`**

Change the constructor and `ingest` to accept and apply a categorizer + category repo:

```python
from app.repositories import AccountRepository, CategoryRepository, TransactionRepository
from app.enrichment.categorizer import RuleCategorizer


class DedupEngine:
    def __init__(self, *, accounts, transactions, categories: CategoryRepository,
                 categorizer: RuleCategorizer, window_hours: int):
        self.accounts = accounts
        self.transactions = transactions
        self.categories = categories
        self.categorizer = categorizer
        self.window_hours = window_hours

    async def ingest(self, pt, source):
        account = await self.accounts.get_or_create(bank=pt.bank, type=pt.account_type, last4=pt.last4)
        fingerprint = pt.fingerprint()
        match = await self.transactions.find_recent_by_fingerprint(
            fingerprint, before=pt.occurred_at, window_hours=self.window_hours
        )
        if match is not None and not self._has_source_from_channel(match, source.channel):
            source.transaction = match
            source.parsed_status = "parsed"
            return match

        category = await self.categories.get_or_create(self.categorizer.categorize(pt.merchant))
        txn = await self.transactions.create(
            amount=pt.amount, direction=pt.direction, merchant=pt.merchant,
            occurred_at=pt.occurred_at, account_id=account.id, fingerprint=fingerprint,
            category_id=category.id,
        )
        source.transaction = txn
        source.parsed_status = "parsed"
        return txn

    @staticmethod
    def _has_source_from_channel(txn, channel):
        return any(s.channel == channel for s in txn.sources)
```

- [ ] **Step 4: Update `IngestionService` in `backend/app/ingestion_service.py`**

```python
from app.enrichment.categorizer import RuleCategorizer
from app.enrichment.default_rules import DEFAULT_RULES
from app.repositories import AccountRepository, CategoryRepository, RawMessageRepository, TransactionRepository


class IngestionService:
    def __init__(self, *, session, registry, window_hours, categorizer: RuleCategorizer | None = None):
        self.session = session
        self.registry = registry
        self.messages = RawMessageRepository(session)
        categorizer = categorizer or RuleCategorizer(DEFAULT_RULES)
        self.dedup = DedupEngine(
            accounts=AccountRepository(session),
            transactions=TransactionRepository(session),
            categories=CategoryRepository(session),
            categorizer=categorizer,
            window_hours=window_hours,
        )
    # ingest_message body unchanged from Task A12
```

- [ ] **Step 5: Run test to verify it passes (and re-run prior dedup/ingestion suites)**

Run: `cd backend && python -m pytest tests/test_ingestion_categorizes.py tests/test_dedup.py tests/test_ingestion_service.py -v`
Expected: all PASS. (Update `_engine` helper in `test_dedup.py` to pass `categories=CategoryRepository(session)` and `categorizer=RuleCategorizer({})`.)

- [ ] **Step 6: Commit**

```bash
git add backend/app/dedup.py backend/app/ingestion_service.py backend/tests/test_ingestion_categorizes.py backend/tests/test_dedup.py
git commit -m "feat: categorize transactions during ingestion"
```

---

### Task B4: Recurring detector

**Files:**
- Create: `backend/app/enrichment/recurring.py`
- Create: `backend/tests/test_recurring.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_recurring.py`

```python
from datetime import datetime

from app.enrichment.recurring import detect_recurring


def test_detects_monthly_subscription():
    charges = [
        ("NETFLIX", 199.0, datetime(2026, 4, 1)),
        ("NETFLIX", 199.0, datetime(2026, 5, 1)),
        ("NETFLIX", 199.0, datetime(2026, 6, 1)),
    ]
    series = detect_recurring(charges)
    assert len(series) == 1
    assert series[0].merchant == "NETFLIX"
    assert 28 <= series[0].cadence_days <= 31


def test_ignores_one_off_charges():
    charges = [("RANDOM", 50.0, datetime(2026, 6, 1))]
    assert detect_recurring(charges) == []
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_recurring.py -v`
Expected: FAIL with `ModuleNotFoundError`

- [ ] **Step 3: Create `backend/app/enrichment/recurring.py`**

```python
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from statistics import mean

from app.parsing.types import normalize_merchant


@dataclass
class DetectedSeries:
    merchant: str
    expected_amount: float
    cadence_days: int
    last_seen_at: datetime
    next_expected_at: datetime


def detect_recurring(charges: list[tuple[str, float, datetime]]) -> list[DetectedSeries]:
    groups: dict[str, list[tuple[float, datetime]]] = {}
    labels: dict[str, str] = {}
    for merchant, amount, when in charges:
        key = normalize_merchant(merchant)
        groups.setdefault(key, []).append((amount, when))
        labels[key] = merchant

    series: list[DetectedSeries] = []
    for key, items in groups.items():
        if len(items) < 3:
            continue
        items.sort(key=lambda x: x[1])
        gaps = [(items[i][1] - items[i - 1][1]).days for i in range(1, len(items))]
        cadence = round(mean(gaps))
        if not (25 <= cadence <= 35):
            continue
        last = items[-1][1]
        series.append(
            DetectedSeries(
                merchant=labels[key],
                expected_amount=round(mean(a for a, _ in items), 2),
                cadence_days=cadence,
                last_seen_at=last,
                next_expected_at=last + timedelta(days=cadence),
            )
        )
    return series
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_recurring.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/app/enrichment/recurring.py backend/tests/test_recurring.py
git commit -m "feat: add recurring charge detector"
```

---

### Task B5: Budget evaluator

**Files:**
- Create: `backend/app/enrichment/budgets.py`
- Create: `backend/tests/test_budgets.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_budgets.py`

```python
from app.enrichment.budgets import evaluate_budget, BudgetStatus


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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_budgets.py -v`
Expected: FAIL with `ModuleNotFoundError`

- [ ] **Step 3: Create `backend/app/enrichment/budgets.py`**

```python
from __future__ import annotations

from dataclasses import dataclass


@dataclass
class BudgetStatus:
    percent: float
    status: str  # "ok" | "warning" | "exceeded"


def evaluate_budget(*, limit: float, spent: float, warn_at: float = 0.8) -> BudgetStatus:
    percent = round((spent / limit) * 100, 2) if limit else 0.0
    if spent >= limit:
        status = "exceeded"
    elif spent >= limit * warn_at:
        status = "warning"
    else:
        status = "ok"
    return BudgetStatus(percent=percent, status=status)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_budgets.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/app/enrichment/budgets.py backend/tests/test_budgets.py
git commit -m "feat: add budget evaluator"
```

---

# Phase C — Ingestion Connectors

### Task C1: SMS webhook payload schema

**Files:**
- Create: `backend/app/schemas.py`
- Create: `backend/tests/test_schemas.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_schemas.py`

```python
from datetime import datetime

from app.schemas import SmsIn


def test_sms_in_parses_iso_timestamp():
    payload = SmsIn(sender="AX-AXISBK", body="x", received_at="2026-06-01T10:00:00", external_id="sms-1")
    assert payload.received_at == datetime(2026, 6, 1, 10, 0)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_schemas.py -v`
Expected: FAIL with `ModuleNotFoundError`

- [ ] **Step 3: Create `backend/app/schemas.py`**

```python
from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel


class SmsIn(BaseModel):
    sender: str
    body: str
    received_at: datetime
    external_id: str


class TransactionOut(BaseModel):
    id: int
    amount: float
    direction: str
    merchant: str
    occurred_at: datetime
    account_id: int
    category_id: int | None

    model_config = {"from_attributes": True}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_schemas.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/app/schemas.py backend/tests/test_schemas.py
git commit -m "feat: add SMS input and transaction output schemas"
```

---

### Task C2: Gmail message-to-ingestion adapter

**Files:**
- Create: `backend/app/connectors/__init__.py`
- Create: `backend/app/connectors/gmail.py`
- Create: `backend/tests/test_gmail_adapter.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_gmail_adapter.py`

```python
import base64
from app.connectors.gmail import gmail_message_to_fields


def test_extracts_sender_body_and_id():
    raw_body = base64.urlsafe_b64encode(b"Spent Rs.10 At SHOP").decode()
    msg = {
        "id": "gmail-123",
        "internalDate": "1748764800000",  # 2026-06-01T08:00:00Z in ms
        "payload": {
            "headers": [{"name": "From", "value": "alerts@axisbank.com"}],
            "body": {"data": raw_body},
        },
    }
    fields = gmail_message_to_fields(msg)
    assert fields["external_id"] == "gmail-123"
    assert fields["sender"] == "alerts@axisbank.com"
    assert "Spent Rs.10" in fields["body"]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_gmail_adapter.py -v`
Expected: FAIL with `ModuleNotFoundError`

- [ ] **Step 3: Create `backend/app/connectors/__init__.py`** (empty) and **`backend/app/connectors/gmail.py`**

```python
from __future__ import annotations

import base64
from datetime import datetime, timezone


def _decode(data: str) -> str:
    return base64.urlsafe_b64decode(data.encode()).decode(errors="replace")


def _extract_body(payload: dict) -> str:
    if payload.get("body", {}).get("data"):
        return _decode(payload["body"]["data"])
    for part in payload.get("parts", []):
        text = _extract_body(part)
        if text:
            return text
    return ""


def gmail_message_to_fields(msg: dict) -> dict:
    headers = {h["name"].lower(): h["value"] for h in msg["payload"].get("headers", [])}
    received = datetime.fromtimestamp(int(msg["internalDate"]) / 1000, tz=timezone.utc).replace(tzinfo=None)
    return {
        "external_id": f"gmail-{msg['id']}" if not msg["id"].startswith("gmail-") else msg["id"],
        "sender": headers.get("from", "unknown"),
        "body": _extract_body(msg["payload"]),
        "received_at": received,
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_gmail_adapter.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/app/connectors/__init__.py backend/app/connectors/gmail.py backend/tests/test_gmail_adapter.py
git commit -m "feat: add gmail message-to-fields adapter"
```

---

### Task C3: Gmail poller (client wrapper)

**Files:**
- Create: `backend/app/connectors/gmail_poller.py`
- Create: `backend/tests/test_gmail_poller.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_gmail_poller.py`

```python
from app.connectors.gmail_poller import poll_messages


class FakeGmail:
    """Mimics the googleapiclient resource chain users().messages()."""
    def __init__(self, ids, bodies):
        self._ids = ids
        self._bodies = bodies

    def users(self):
        return self

    def messages(self):
        return self

    def list(self, userId, q, maxResults):
        return _Exec({"messages": [{"id": i} for i in self._ids]})

    def get(self, userId, id, format):
        return _Exec(self._bodies[id])


class _Exec:
    def __init__(self, value):
        self._value = value

    def execute(self):
        return self._value


def test_poll_returns_field_dicts():
    bodies = {
        "1": {"id": "1", "internalDate": "1748764800000",
              "payload": {"headers": [{"name": "From", "value": "alerts@axisbank.com"}],
                          "body": {"data": "U3BlbnQgUnMuMTA="}}},  # "Spent Rs.10"
    }
    gmail = FakeGmail(["1"], bodies)
    results = poll_messages(gmail, query="from:axisbank.com", max_results=10)
    assert len(results) == 1
    assert results[0]["external_id"] == "gmail-1"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_gmail_poller.py -v`
Expected: FAIL with `ModuleNotFoundError`

- [ ] **Step 3: Create `backend/app/connectors/gmail_poller.py`**

```python
from __future__ import annotations

from app.connectors.gmail import gmail_message_to_fields


def poll_messages(gmail_service, *, query: str, max_results: int = 50) -> list[dict]:
    listing = (
        gmail_service.users().messages()
        .list(userId="me", q=query, maxResults=max_results)
        .execute()
    )
    fields = []
    for ref in listing.get("messages", []):
        msg = (
            gmail_service.users().messages()
            .get(userId="me", id=ref["id"], format="full")
            .execute()
        )
        fields.append(gmail_message_to_fields(msg))
    return fields
```

Note: real OAuth client construction (`build("gmail", "v1", credentials=...)`) is done in the API wiring task (D-series), where credentials are loaded from `token.json`. This module is intentionally client-agnostic so it stays unit-testable with a fake.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_gmail_poller.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/app/connectors/gmail_poller.py backend/tests/test_gmail_poller.py
git commit -m "feat: add gmail poller wrapper"
```

---

# Phase D — REST API + React Web Dashboard

### Task D1: FastAPI app with DB dependency + SMS ingest endpoint

**Files:**
- Create: `backend/app/main.py`
- Create: `backend/app/deps.py`
- Create: `backend/tests/test_api_sms.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_api_sms.py`

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


async def test_post_sms_creates_transaction(client):
    resp = await client.post("/ingest/sms", json={
        "sender": "AX-AXISBK",
        "body": "INR 450 spent on AXIS BANK Credit Card no. XX1234 at SWIGGY on 01-06-2026.",
        "received_at": "2026-06-01T10:00:00",
        "external_id": "api-1",
    })
    assert resp.status_code == 201
    assert resp.json()["amount"] == 450.0


async def test_post_unknown_sms_returns_202_review(client):
    resp = await client.post("/ingest/sms", json={
        "sender": "NOPE", "body": "gibberish", "received_at": "2026-06-01T10:00:00", "external_id": "api-2",
    })
    assert resp.status_code == 202
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_api_sms.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'app.main'`

- [ ] **Step 3: Create `backend/app/deps.py`**

```python
from __future__ import annotations

from collections.abc import AsyncIterator

from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.db import new_session_factory

_engine, _Session = new_session_factory(settings.database_url)


async def get_session() -> AsyncIterator[AsyncSession]:
    async with _Session() as session:
        yield session
```

- [ ] **Step 4: Create `backend/app/main.py`**

```python
from __future__ import annotations

from fastapi import Depends, FastAPI, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.deps import get_session
from app.ingestion_service import IngestionService
from app.parsing.registry import default_registry
from app.schemas import SmsIn, TransactionOut

app = FastAPI(title="Expense Tracker")


def _service(session: AsyncSession) -> IngestionService:
    return IngestionService(session=session, registry=default_registry(), window_hours=settings.dedup_window_hours)


@app.post("/ingest/sms", status_code=status.HTTP_201_CREATED)
async def ingest_sms(payload: SmsIn, response: Response, session: AsyncSession = Depends(get_session)):
    svc = _service(session)
    txn = await svc.ingest_message(
        channel="sms", sender=payload.sender, body=payload.body,
        received_at=payload.received_at, external_id=payload.external_id,
    )
    await session.commit()
    if txn is None:
        response.status_code = status.HTTP_202_ACCEPTED
        return {"status": "review_or_duplicate"}
    return TransactionOut.model_validate(txn)
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_api_sms.py -v`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/app/main.py backend/app/deps.py backend/tests/test_api_sms.py
git commit -m "feat: add FastAPI app with SMS ingest endpoint"
```

---

### Task D2: Read endpoints (transactions, summary, budgets)

**Files:**
- Modify: `backend/app/repositories.py`
- Modify: `backend/app/main.py`
- Create: `backend/tests/test_api_reads.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_api_reads.py`

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


async def _seed(client):
    await client.post("/ingest/sms", json={
        "sender": "AX-AXISBK", "body": "INR 450 spent on AXIS BANK Credit Card no. XX1234 at SWIGGY on 01-06-2026.",
        "received_at": "2026-06-01T10:00:00", "external_id": "r1"})
    await client.post("/ingest/sms", json={
        "sender": "VM-IDFCFB", "body": "Rs.1,250 spent on your IDFC FIRST Bank Credit Card XX9876 at AMAZON on 02-Jun-2026.",
        "received_at": "2026-06-02T10:00:00", "external_id": "r2"})


async def test_list_transactions(client):
    await _seed(client)
    resp = await client.get("/transactions")
    assert resp.status_code == 200
    assert len(resp.json()) == 2


async def test_category_summary(client):
    await _seed(client)
    resp = await client.get("/reports/summary")
    assert resp.status_code == 200
    data = {row["category"]: row["total"] for row in resp.json()}
    assert data["food"] == 450.0
    assert data["shopping"] == 1250.0
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_api_reads.py -v`
Expected: FAIL (404 / missing endpoints)

- [ ] **Step 3: Add query methods to `backend/app/repositories.py`**

Append to `TransactionRepository`:

```python
    async def list_all(self) -> list[Transaction]:
        stmt = select(Transaction).order_by(Transaction.occurred_at.desc())
        return list((await self.session.execute(stmt)).scalars())

    async def category_totals(self) -> list[tuple[str, float]]:
        from app.entities import Category
        stmt = (
            select(Category.name, func.sum(Transaction.amount))
            .join(Category, Transaction.category_id == Category.id)
            .where(Transaction.direction == "debit")
            .group_by(Category.name)
        )
        return [(name, float(total)) for name, total in (await self.session.execute(stmt)).all()]
```

Add `from sqlalchemy import func` to the imports at the top of the file.

- [ ] **Step 4: Add endpoints to `backend/app/main.py`**

```python
from app.repositories import TransactionRepository


@app.get("/transactions", response_model=list[TransactionOut])
async def list_transactions(session: AsyncSession = Depends(get_session)):
    rows = await TransactionRepository(session).list_all()
    return [TransactionOut.model_validate(r) for r in rows]


@app.get("/reports/summary")
async def category_summary(session: AsyncSession = Depends(get_session)):
    totals = await TransactionRepository(session).category_totals()
    return [{"category": name, "total": total} for name, total in totals]
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_api_reads.py -v`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/app/repositories.py backend/app/main.py backend/tests/test_api_reads.py
git commit -m "feat: add transaction list and category summary endpoints"
```

---

### Task D3: Gmail sync endpoint + CORS

**Files:**
- Modify: `backend/app/main.py`
- Create: `backend/tests/test_api_gmail_sync.py`

- [ ] **Step 1: Write the failing test** in `backend/tests/test_api_gmail_sync.py`

```python
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.db import Base, new_session_factory
from app.deps import get_session
from app import main
from app.main import app


@pytest_asyncio.fixture
async def client(monkeypatch):
    engine, Session = new_session_factory("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async def override():
        async with Session() as s:
            yield s

    def fake_poll():
        return [{
            "external_id": "gmail-9", "sender": "alerts@axisbank.com",
            "body": "INR 99 spent on AXIS BANK Credit Card no. XX1234 at BLINKIT on 01-06-2026.",
            "received_at": __import__("datetime").datetime(2026, 6, 1, 10, 0),
        }]

    monkeypatch.setattr(main, "fetch_gmail_fields", fake_poll)
    app.dependency_overrides[get_session] = override
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c
    app.dependency_overrides.clear()
    await engine.dispose()


async def test_gmail_sync_ingests(client):
    resp = await client.post("/sync/gmail")
    assert resp.status_code == 200
    assert resp.json()["ingested"] == 1
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && python -m pytest tests/test_api_gmail_sync.py -v`
Expected: FAIL (404 / `fetch_gmail_fields` missing)

- [ ] **Step 3: Add to `backend/app/main.py`**

```python
from fastapi.middleware.cors import CORSMiddleware

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173"],
    allow_methods=["*"],
    allow_headers=["*"],
)


def fetch_gmail_fields() -> list[dict]:
    """Real implementation builds the Gmail client from token.json and polls.
    Overridden in tests. Lazy imports keep googleapiclient out of unit tests."""
    from google.oauth2.credentials import Credentials
    from googleapiclient.discovery import build

    from app.connectors.gmail_poller import poll_messages

    creds = Credentials.from_authorized_user_file("token.json")
    service = build("gmail", "v1", credentials=creds)
    return poll_messages(
        service,
        query="from:(axisbank.com OR idfcfirstbank.com OR bankofbaroda.co.in)",
        max_results=50,
    )


@app.post("/sync/gmail")
async def sync_gmail(session: AsyncSession = Depends(get_session)):
    svc = _service(session)
    fields = fetch_gmail_fields()
    ingested = 0
    for f in fields:
        txn = await svc.ingest_message(channel="email", **f)
        if txn is not None:
            ingested += 1
    await session.commit()
    return {"ingested": ingested, "fetched": len(fields)}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && python -m pytest tests/test_api_gmail_sync.py -v`
Expected: PASS

- [ ] **Step 5: Run the full backend suite**

Run: `cd backend && python -m pytest -v`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/app/main.py backend/tests/test_api_gmail_sync.py
git commit -m "feat: add gmail sync endpoint and CORS for web client"
```

---

### Task D4: React app scaffold

**Files:**
- Create: `web/package.json`
- Create: `web/vite.config.ts`
- Create: `web/tsconfig.json`
- Create: `web/index.html`
- Create: `web/src/main.tsx`
- Create: `web/src/api.ts`

- [ ] **Step 1: Scaffold with Vite**

Run: `cd web 2>/dev/null || (cd .. && npm create vite@latest web -- --template react-ts)` then `cd web && npm install && npm install recharts`

- [ ] **Step 2: Create `web/src/api.ts`**

```typescript
const BASE = "http://localhost:8000";

export interface Transaction {
  id: number;
  amount: number;
  direction: string;
  merchant: string;
  occurred_at: string;
  account_id: number;
  category_id: number | null;
}

export interface CategoryTotal {
  category: string;
  total: number;
}

export async function fetchTransactions(): Promise<Transaction[]> {
  const res = await fetch(`${BASE}/transactions`);
  if (!res.ok) throw new Error("Failed to load transactions");
  return res.json();
}

export async function fetchSummary(): Promise<CategoryTotal[]> {
  const res = await fetch(`${BASE}/reports/summary`);
  if (!res.ok) throw new Error("Failed to load summary");
  return res.json();
}

export async function syncGmail(): Promise<{ ingested: number; fetched: number }> {
  const res = await fetch(`${BASE}/sync/gmail`, { method: "POST" });
  if (!res.ok) throw new Error("Gmail sync failed");
  return res.json();
}
```

- [ ] **Step 3: Verify build compiles**

Run: `cd web && npm run build`
Expected: build succeeds with no type errors.

- [ ] **Step 4: Commit**

```bash
git add web/ -- ':!web/node_modules'
echo "node_modules/" > web/.gitignore && git add web/.gitignore
git commit -m "chore: scaffold react web app with api client"
```

---

### Task D5: Dashboard components (transaction list + category chart)

**Files:**
- Create: `web/src/components/TransactionList.tsx`
- Create: `web/src/components/CategoryChart.tsx`
- Create: `web/src/App.tsx` (overwrite Vite default)

- [ ] **Step 1: Create `web/src/components/TransactionList.tsx`**

```tsx
import type { Transaction } from "../api";

export function TransactionList({ items }: { items: Transaction[] }) {
  return (
    <table>
      <thead>
        <tr><th>Date</th><th>Merchant</th><th>Amount (₹)</th><th>Type</th></tr>
      </thead>
      <tbody>
        {items.map((t) => (
          <tr key={t.id}>
            <td>{new Date(t.occurred_at).toLocaleDateString("en-IN")}</td>
            <td>{t.merchant}</td>
            <td>{t.amount.toFixed(2)}</td>
            <td>{t.direction}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
```

- [ ] **Step 2: Create `web/src/components/CategoryChart.tsx`**

```tsx
import { PieChart, Pie, Cell, Tooltip, Legend, ResponsiveContainer } from "recharts";
import type { CategoryTotal } from "../api";

const COLORS = ["#4e79a7", "#f28e2b", "#e15759", "#76b7b2", "#59a14f", "#edc948"];

export function CategoryChart({ data }: { data: CategoryTotal[] }) {
  return (
    <ResponsiveContainer width="100%" height={320}>
      <PieChart>
        <Pie data={data} dataKey="total" nameKey="category" outerRadius={120} label>
          {data.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
        </Pie>
        <Tooltip formatter={(v: number) => `₹${v.toFixed(2)}`} />
        <Legend />
      </PieChart>
    </ResponsiveContainer>
  );
}
```

- [ ] **Step 3: Overwrite `web/src/App.tsx`**

```tsx
import { useEffect, useState } from "react";
import { fetchTransactions, fetchSummary, syncGmail } from "./api";
import type { Transaction, CategoryTotal } from "./api";
import { TransactionList } from "./components/TransactionList";
import { CategoryChart } from "./components/CategoryChart";

export default function App() {
  const [txns, setTxns] = useState<Transaction[]>([]);
  const [summary, setSummary] = useState<CategoryTotal[]>([]);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    try {
      setTxns(await fetchTransactions());
      setSummary(await fetchSummary());
    } catch (e) {
      setError((e as Error).message);
    }
  }

  useEffect(() => { load(); }, []);

  return (
    <main style={{ maxWidth: 900, margin: "0 auto", fontFamily: "sans-serif" }}>
      <h1>Expense Tracker</h1>
      {error && <p style={{ color: "red" }}>{error}</p>}
      <button onClick={async () => { await syncGmail(); await load(); }}>Sync Gmail</button>
      <h2>Spending by Category</h2>
      <CategoryChart data={summary} />
      <h2>Transactions</h2>
      <TransactionList items={txns} />
    </main>
  );
}
```

- [ ] **Step 4: Verify build compiles**

Run: `cd web && npm run build`
Expected: build succeeds.

- [ ] **Step 5: Manual smoke test**

Run (two terminals): `cd backend && uvicorn app.main:app --reload` and `cd web && npm run dev`.
Open `http://localhost:5173`. POST a sample SMS via `curl` to `/ingest/sms`, refresh, and confirm it appears in the list and chart.

- [ ] **Step 6: Commit**

```bash
git add web/src/
git commit -m "feat: add dashboard with transaction list and category chart"
```

---

# Phase E — Native Kotlin Android App

### Task E1: Android project scaffold

**Files:**
- Create: `android/` (Android Studio project: package `com.expense.tracker`, min SDK 26, Jetpack Compose)
- Create: `android/app/src/main/AndroidManifest.xml` (permissions)

- [ ] **Step 1: Create the project**

In Android Studio: New Project → Empty Compose Activity → name `ExpenseTracker`, package `com.expense.tracker`, min SDK 26. Confirm it builds and runs the default screen on an emulator.

- [ ] **Step 2: Add SMS + internet permissions to `android/app/src/main/AndroidManifest.xml`**

```xml
<uses-permission android:name="android.permission.RECEIVE_SMS" />
<uses-permission android:name="android.permission.READ_SMS" />
<uses-permission android:name="android.permission.INTERNET" />
```

- [ ] **Step 3: Add Retrofit + Moshi to `android/app/build.gradle.kts` dependencies**

```kotlin
implementation("com.squareup.retrofit2:retrofit:2.11.0")
implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
```

- [ ] **Step 4: Verify build**

Run: `cd android && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add android/ -- ':!android/.gradle' ':!android/build' ':!android/app/build'
printf ".gradle/\nbuild/\nlocal.properties\n" > android/.gitignore && git add android/.gitignore
git commit -m "chore: scaffold android app with sms permissions and retrofit"
```

---

### Task E2: SMS sender filter (unit-testable logic)

**Files:**
- Create: `android/app/src/main/java/com/expense/tracker/SmsFilter.kt`
- Create: `android/app/src/test/java/com/expense/tracker/SmsFilterTest.kt`

- [ ] **Step 1: Write the failing test** in `SmsFilterTest.kt`

```kotlin
package com.expense.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsFilterTest {
    @Test
    fun acceptsKnownBankSenders() {
        assertTrue(SmsFilter.isBankSender("AX-AXISBK"))
        assertTrue(SmsFilter.isBankSender("VM-IDFCFB"))
        assertTrue(SmsFilter.isBankSender("VK-BOBTXN"))
    }

    @Test
    fun rejectsUnknownSenders() {
        assertFalse(SmsFilter.isBankSender("FRIEND"))
    }

    @Test
    fun normalizesSenderId() {
        assertEquals("AXISBK", SmsFilter.normalize("AX-AXISBK"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests com.expense.tracker.SmsFilterTest`
Expected: FAIL — `SmsFilter` unresolved.

- [ ] **Step 3: Create `SmsFilter.kt`**

```kotlin
package com.expense.tracker

object SmsFilter {
    // Axis (card + savings), IDFC First (card), Bank of Baroda (savings)
    private val KNOWN = listOf("AXISBK", "IDFCFB", "BOB")

    fun normalize(sender: String): String =
        sender.substringAfterLast('-').uppercase()

    fun isBankSender(sender: String): Boolean =
        KNOWN.any { normalize(sender).contains(it) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests com.expense.tracker.SmsFilterTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/expense/tracker/SmsFilter.kt android/app/src/test/java/com/expense/tracker/SmsFilterTest.kt
git commit -m "feat: add android sms sender filter with unit tests"
```

---

### Task E3: API client + SMS receiver that forwards to backend

**Files:**
- Create: `android/app/src/main/java/com/expense/tracker/Api.kt`
- Create: `android/app/src/main/java/com/expense/tracker/SmsReceiver.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create `Api.kt`**

```kotlin
package com.expense.tracker

import com.squareup.moshi.JsonClass
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

@JsonClass(generateAdapter = true)
data class SmsIn(
    val sender: String,
    val body: String,
    val received_at: String,
    val external_id: String,
)

interface ExpenseApi {
    @POST("ingest/sms")
    suspend fun ingestSms(@Body sms: SmsIn)

    companion object {
        // Emulator reaches host machine at 10.0.2.2
        fun create(baseUrl: String = "http://10.0.2.2:8000/"): ExpenseApi =
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(MoshiConverterFactory.create())
                .build()
                .create(ExpenseApi::class.java)
    }
}
```

- [ ] **Step 2: Create `SmsReceiver.kt`**

```kotlin
package com.expense.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    private val api = ExpenseApi.create()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        for (sms in Telephony.Sms.Intents.getMessagesFromIntent(intent)) {
            val sender = sms.originatingAddress ?: continue
            if (!SmsFilter.isBankSender(sender)) continue
            val payload = SmsIn(
                sender = SmsFilter.normalize(sender),
                body = sms.messageBody,
                received_at = java.time.LocalDateTime.now().toString(),
                external_id = "sms-${sms.timestampMillis}-${sms.messageBody.hashCode()}",
            )
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { api.ingestSms(payload) }
            }
        }
    }
}
```

- [ ] **Step 3: Register the receiver in `AndroidManifest.xml`** (inside `<application>`)

```xml
<receiver
    android:name=".SmsReceiver"
    android:exported="true"
    android:permission="android.permission.BROADCAST_SMS">
    <intent-filter>
        <action android:name="android.provider.Telephony.SMS_RECEIVED" />
    </intent-filter>
</receiver>
```

- [ ] **Step 4: Build**

Run: `cd android && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/expense/tracker/Api.kt android/app/src/main/java/com/expense/tracker/SmsReceiver.kt android/app/src/main/AndroidManifest.xml
git commit -m "feat: forward bank SMS to backend via broadcast receiver"
```

---

### Task E4: Runtime permission request + transaction viewing screen

**Files:**
- Create: `android/app/src/main/java/com/expense/tracker/MainActivity.kt` (overwrite default)
- Modify: `android/app/src/main/java/com/expense/tracker/Api.kt` (add GET transactions)

- [ ] **Step 1: Add a transactions GET to `Api.kt`**

```kotlin
@JsonClass(generateAdapter = true)
data class TransactionOut(
    val id: Int,
    val amount: Double,
    val merchant: String,
    val direction: String,
    val occurred_at: String,
)

// add to ExpenseApi interface:
@retrofit2.http.GET("transactions")
suspend fun transactions(): List<TransactionOut>
```

- [ ] **Step 2: Overwrite `MainActivity.kt`**

```kotlin
package com.expense.tracker

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val api = ExpenseApi.create()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { /* user grants RECEIVE_SMS / READ_SMS */ }
        permLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))

        setContent {
            var txns by remember { mutableStateOf<List<TransactionOut>>(emptyList()) }
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                scope.launch { runCatching { txns = api.transactions() } }
            }
            LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
                items(txns) { t ->
                    Text("${t.occurred_at.take(10)}  ₹${t.amount}  ${t.merchant} (${t.direction})")
                }
            }
        }
    }
}
```

- [ ] **Step 3: Build and run on emulator**

Run: `cd android && ./gradlew assembleDebug`
Then install on a running emulator: `./gradlew installDebug`.
Expected: app launches, requests SMS permission, shows transactions list (empty until SMS arrive or backend seeded).

- [ ] **Step 4: End-to-end smoke test**

With backend running (`uvicorn app.main:app --reload`), send a test SMS to the emulator from the bank sender format (Android Studio → Extended Controls → Phone → send SMS as `AXISBK` with a sample alert body). Confirm a transaction appears in both the Android list and the web dashboard, and that the same alert arriving by Gmail sync does NOT create a duplicate.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/expense/tracker/MainActivity.kt android/app/src/main/java/com/expense/tracker/Api.kt
git commit -m "feat: add permission request and transactions viewing screen"
```

---

# Phase F — Expanded Scope (from research)

All tasks follow TDD. Tests use the existing in-memory `session` fixture. Run with `.venv/bin/python -m pytest -v`.

### Task F1: Extend entities for expanded scope

**Files:** Modify `backend/app/entities.py`; create `backend/tests/test_entities_extended.py`.

- [ ] **Step 1: Write the failing test** `backend/tests/test_entities_extended.py`:

```python
from datetime import datetime

from app.entities import (
    Account, BalanceSnapshot, BudgetAssignment, MonthlyIncome,
    Transaction, TransactionSplit,
)


async def test_new_columns_and_entities_persist(session):
    acc = Account(bank="CASH", type="cash", last4="0000", currency="INR", current_balance=500.0)
    session.add(acc)
    await session.flush()

    txn = Transaction(
        amount=300.0, direction="debit", merchant="MANUAL", occurred_at=datetime(2026, 6, 1),
        account_id=acc.id, fingerprint="manual|300.0|debit|manual",
        entry_type="manual", note="lunch", balance_after=200.0,
    )
    session.add(txn)
    await session.flush()

    session.add_all([
        TransactionSplit(transaction_id=txn.id, category_id=None, amount=300.0),
        BalanceSnapshot(account_id=acc.id, balance=200.0, captured_at=datetime(2026, 6, 1)),
        BudgetAssignment(category_id=1, month="2026-06", assigned_amount=1000.0),
        MonthlyIncome(month="2026-06", amount=50000.0),
    ])
    await session.flush()
    assert txn.entry_type == "manual" and txn.balance_after == 200.0
    assert acc.current_balance == 500.0
```

- [ ] **Step 2:** Run, expect failure (ImportError for new names).

- [ ] **Step 3: Modify `backend/app/entities.py`** — add to `Account`: `current_balance: Mapped[float | None] = mapped_column(Float, nullable=True)` and document `type` may be `"cash"`. Add to `Transaction`: `entry_type: Mapped[str] = mapped_column(String(8), default="auto")`, `note: Mapped[str | None] = mapped_column(String(512), nullable=True)`, `balance_after: Mapped[float | None] = mapped_column(Float, nullable=True)`. Append new entity classes:

```python
class TransactionSplit(Base):
    __tablename__ = "transaction_splits"
    id: Mapped[int] = mapped_column(primary_key=True)
    transaction_id: Mapped[int] = mapped_column(ForeignKey("transactions.id"))
    category_id: Mapped[int | None] = mapped_column(ForeignKey("categories.id"), nullable=True)
    amount: Mapped[float] = mapped_column(Float)


class BalanceSnapshot(Base):
    __tablename__ = "balance_snapshots"
    id: Mapped[int] = mapped_column(primary_key=True)
    account_id: Mapped[int] = mapped_column(ForeignKey("accounts.id"))
    balance: Mapped[float] = mapped_column(Float)
    captured_at: Mapped[datetime] = mapped_column(DateTime)


class BudgetAssignment(Base):
    __tablename__ = "budget_assignments"
    id: Mapped[int] = mapped_column(primary_key=True)
    category_id: Mapped[int] = mapped_column(ForeignKey("categories.id"))
    month: Mapped[str] = mapped_column(String(7))  # "YYYY-MM"
    assigned_amount: Mapped[float] = mapped_column(Float)


class MonthlyIncome(Base):
    __tablename__ = "monthly_income"
    id: Mapped[int] = mapped_column(primary_key=True)
    month: Mapped[str] = mapped_column(String(7))
    amount: Mapped[float] = mapped_column(Float)
```

- [ ] **Step 4:** Run test → PASS; run full suite → no regressions.
- [ ] **Step 5: Commit** `feat: extend entities for balances, splits, zero-based budgeting, manual entry`.

> NOTE: `ParsedTransaction` (Task A6) must gain `balance_after: float | None = None`, and parsers A8/A9/A9b must extract "Avl Bal"/"Avl Lmt" into it. The ingestion/dedup path (A12) must copy `pt.balance_after` onto the created `Transaction` and call `BalanceService` (F3). Apply these when implementing those tasks.

---

### Task F2: Repositories for new entities

**Files:** Append to `backend/app/repositories.py`; create `backend/tests/test_repositories_extended.py`.

Implement (mirroring existing repository style, async, session-scoped):
- `TransactionSplitRepository`: `add(transaction_id, category_id, amount)`, `list_for(transaction_id)`, `delete_for(transaction_id)`.
- `BalanceSnapshotRepository`: `add(account_id, balance, captured_at)`, `latest_for(account_id)`, `history(account_id)`.
- `BudgetAssignmentRepository`: `set(category_id, month, assigned_amount)` (upsert by (category_id, month)), `list_for_month(month)`, `total_assigned(month)`.
- `MonthlyIncomeRepository`: `set(month, amount)` (upsert), `get(month)`.

- [ ] Write tests asserting: split add+list+delete; snapshot latest returns most recent by `captured_at`; assignment `set` upserts (same category+month updates, not duplicates) and `total_assigned` sums; income `set` upserts.
- [ ] Implement, run tests → PASS, full suite green.
- [ ] **Commit** `feat: add repositories for splits, balances, assignments, income`.

---

### Task F3: BalanceService (balances + net worth)

**Files:** Create `backend/app/enrichment/balances.py`, `backend/tests/test_balances.py`.

Behavior: `BalanceService.apply(account, balance_after, at)` — if `balance_after` is not None, set `account.current_balance = balance_after` and append a `BalanceSnapshot`. `net_worth()` sums `current_balance` across accounts (None treated as 0).

- [ ] **Test** (TDD): applying a balance updates `current_balance` and writes one snapshot; applying None is a no-op; `net_worth()` sums two accounts' balances.
- [ ] Implement; wire `BalanceService` into the dedup/ingestion path so auto transactions update balances when `balance_after` is present.
- [ ] Run → PASS; **commit** `feat: add balance and net-worth service`.

---

### Task F4: Manual & cash entry

**Files:** Append to `backend/app/main.py`; create `backend/tests/test_api_manual_entry.py`. Add a `ManualTxnIn` schema to `backend/app/schemas.py` (`amount, direction, merchant, occurred_at, account_id, category_id?, note?`).

Endpoint `POST /transactions/manual` → creates a `Transaction` with `entry_type="manual"`, no RawMessage source, fingerprint computed from its fields, runs the categorizer if `category_id` omitted, returns `TransactionOut`. A "Cash" account is auto-created via `AccountRepository.get_or_create(bank="CASH", type="cash", last4="0000")` when `account_id` is omitted.

- [ ] **Test** (TDD, ASGI client like D1): POST a manual cash expense → 201, appears in `GET /transactions`, `entry_type=="manual"`.
- [ ] Implement; run → PASS; **commit** `feat: add manual and cash transaction entry`.

---

### Task F5: ZeroBasedBudgetService (supersedes B5 as the budgeting model)

**Files:** Create `backend/app/enrichment/zero_based.py`, `backend/tests/test_zero_based.py`. Reuse `evaluate_budget` (B5) for per-category status.

Behavior for a given `month` ("YYYY-MM"):
- `ready_to_assign(month)` = `MonthlyIncome.get(month)` − `BudgetAssignmentRepository.total_assigned(month)`.
- `category_status(month)` → for each assigned category: `{category, assigned, spent, remaining, status}` where `spent` = sum of that month's debit transactions in that category (using splits when present), `status` from `evaluate_budget(limit=assigned, spent=spent)`.

- [ ] **Test** (TDD): income 50000, assign 1000 to "food"; spend 850 in food that month → ready_to_assign==49000, food status "warning", remaining 150. Spend split across categories counts via splits.
- [ ] Implement; run → PASS; **commit** `feat: add zero-based budgeting service`.

- [ ] **Endpoints** (append to `main.py`, with test): `PUT /budget/income` (set month income), `PUT /budget/assign` (set category+month assignment), `GET /budget/{month}` (returns ready_to_assign + category_status). Commit `feat: add zero-based budget endpoints`.

---

### Task F6: Edit, recategorize, and split

**Files:** Append to `backend/app/main.py` + `backend/app/repositories.py`; create `backend/tests/test_api_edit_split.py`. Schemas: `TxnPatch` (optional amount, merchant, category_id, occurred_at, note), `SplitIn` (list of `{category_id, amount}`).

Endpoints:
- `PATCH /transactions/{id}` — partial update of editable fields; returns updated `TransactionOut`.
- `PUT /transactions/{id}/splits` — replace splits (delete existing via `TransactionSplitRepository.delete_for`, add new); validate Σ split amounts == transaction amount (400 otherwise).

- [ ] **Test** (TDD): patch a transaction's category → reflected; put splits summing to amount → 200 and stored; put splits not summing → 400.
- [ ] Implement; run → PASS; **commit** `feat: add transaction edit, recategorize, and split`.

---

### Task F7: Search & filter

**Files:** Append to `main.py` + `TransactionRepository`; test `backend/tests/test_api_search.py`.

`GET /transactions/search?q=&account_id=&category_id=&start=&end=&min_amount=&max_amount=` — all params optional, AND-combined; `q` matches merchant/note (case-insensitive). Returns `list[TransactionOut]`, newest first.

- [ ] **Test** (TDD): seed several txns; filter by category and by `q` substring and by date range; assert correct subsets.
- [ ] Implement; run → PASS; **commit** `feat: add transaction search and filter`.

---

### Task F8: CSV export

**Files:** Append to `main.py`; test `backend/tests/test_api_export.py`.

`GET /export/transactions.csv` → `text/csv` streaming response with header row `id,occurred_at,account,merchant,category,direction,amount,note` and one row per transaction (use account `bank+last4` and category name; blank when null).

- [ ] **Test** (TDD): seed two txns; GET returns 200, `content-type` startswith `text/csv`, body has header + 2 data rows.
- [ ] Implement; run → PASS; **commit** `feat: add CSV export of transactions`.

---

### Task F9: App lock (PIN/biometric) + auth

**Files:** Create `backend/app/auth.py`; append to `main.py`; test `backend/tests/test_api_auth.py`. Add `ET_APP_PIN_HASH` (sha256 of the PIN) and `ET_AUTH_TOKEN` style handling to config, plus a simple bearer dependency.

Behavior: `POST /auth/unlock {pin}` → if `sha256(pin)` matches `settings.app_pin_hash`, return `{token}` (a static token from settings or derived); else 401. A `require_auth` dependency guards all data endpoints (verifies bearer token). For local single-user use this is a lightweight lock, not full IAM. The Android app stores the token after biometric unlock; the web app after PIN entry. Trust microcopy ("data stays on your own server") is surfaced in the UI (Phase G).

- [ ] **Test** (TDD): wrong PIN → 401; correct PIN → token; calling a guarded endpoint without token → 401, with token → 200.
- [ ] Implement; apply `require_auth` to data endpoints; run → PASS; **commit** `feat: add app-lock PIN auth and bearer guard`.

---

# Phase G — Premium Dark Web UI (revises D4/D5)

**Build these with the `frontend-design` skill** (invoke it at the start of Phase G) so the result avoids generic AI aesthetics. Aesthetic per spec §9a: dark-first canvas, vibrant teal/gradient accents, bold tabular numerals, large rounded glowing cards, generous whitespace, smooth microinteractions, skeleton loaders. Stack: React + Vite + TS + Recharts (+ a charting calendar-heatmap lib or custom). Verify each task with `npm run build` and a manual smoke test against the running backend.

### Task G1: Design system & shell
Establish dark theme tokens (color scale, accent gradient, semantic debit/credit + budget-status colors), typography (Inter, tabular-nums for amounts), spacing, card and button primitives, app shell with nav, and a locked/unlock (PIN) gate screen with trust microcopy. Extend `web/src/api.ts` with all Phase D + F endpoints and the auth token flow. Commit `feat(web): dark design system, app shell, and lock screen`.

### Task G2: Dashboard
Compose the dashboard top→bottom: **hero "Spent this month"** (count-up animation, vs last month delta) → **"Ready to assign"** card → **budget envelopes** (horizontal progress bars, status colors) → **category donut + ranked list** → **daily-spend calendar heatmap** → **recent transactions** → **upcoming recurring** → **neutral insights** strip. Data from `/reports/summary`, `/budget/{month}`, `/transactions`, balances/net-worth. Skeleton loaders + smooth transitions. Commit `feat(web): premium dark dashboard`.

### Task G3: Transactions management
Transactions page: searchable/filterable list (wired to `/transactions/search`), row detail drawer with **edit**, **recategorize**, and **split** UI (split editor validates sum), a **manual/cash add** modal, and the **review queue** for `needs_review` items. CSV export button (`/export/transactions.csv`). Commit `feat(web): transactions management, splits, manual entry, review queue`.

### Task G4: Zero-based budget page
Month selector; "Ready to assign" prominent; per-category assignment editor (set assigned amount), spent-vs-assigned bars; set monthly income. Wired to `/budget/*`. Commit `feat(web): zero-based budgeting UI`.

### Task G5: Net worth & accounts
Accounts list with `current_balance`, net-worth total, and a net-worth-over-time line chart from `BalanceSnapshot` history. Commit `feat(web): accounts and net-worth view`.

> Android (Phase E) gains parallel screens later: it already forwards SMS and views transactions; manual entry, budgets, and net worth can be added mirroring the web API. Keep Phase E as the SMS-capture + viewing client for v1; expand after the web UI is proven.

---

## Final Verification

- [ ] **Backend:** `cd backend && python -m pytest -v` → all PASS.
- [ ] **Web:** `cd web && npm run build` → succeeds.
- [ ] **Android:** `cd android && ./gradlew testDebugUnitTest assembleDebug` → all PASS / BUILD SUCCESSFUL.
- [ ] **End-to-end:** SMS + Gmail for the same transaction collapse to one row (dedup verified); unknown senders land in review; categories, summary chart, and transaction list populate on both web and Android.

---

## Notes on Deferred / Future Work (not in this plan)

- Additional bank parsers beyond the user's current set (e.g. if they open an HDFC/SBI account later) — add a parser class + test + register in `default_registry()`. Same shape as Tasks A8/A9/A9b.
- Recurring-detection (B4) has engine logic; surfacing it as an "upcoming" API/UI is included in Phase G2 (dashboard) — deepen later if needed.
- Account Aggregator (Finvu/Setu) fallback — only if email + SMS coverage proves insufficient, per the spec.
- Future differentiators from the research (investment tracking, savings goals, receipt OCR, AI assistant, shared/family spend, UPI-app notification parsing) — separate plans.
- ML-based categorization to replace/augment the rules engine.
- Gmail push (Pub/Sub watch) instead of polling.
