from __future__ import annotations

from datetime import datetime

from sqlalchemy.ext.asyncio import AsyncSession

from app.dedup import DedupEngine
from app.enrichment.balances import BalanceService
from app.enrichment.cascade_categorizer import CascadeCategorizer
from app.entities import RawMessage, Transaction
from app.parsing.bills import parse_card_bill
from app.repositories import (
    AccountRepository, BalanceSnapshotRepository, CardBillRepository,
    CategoryRepository, RawMessageRepository, TransactionRepository,
)


class IngestionService:
    def __init__(self, *, session: AsyncSession, registry, window_hours: int, amount_window_hours: int | None = None, categorizer: CascadeCategorizer | None = None):
        self.session = session
        self.registry = registry
        self.messages = RawMessageRepository(session)
        categorizer = categorizer or CascadeCategorizer()
        if amount_window_hours is None:
            from app.config import settings
            amount_window_hours = settings.dedup_amount_window_hours
        accounts = AccountRepository(session)
        self.dedup = DedupEngine(
            accounts=accounts,
            transactions=TransactionRepository(session),
            categories=CategoryRepository(session),
            categorizer=categorizer,
            balances=BalanceService(
                accounts=accounts, snapshots=BalanceSnapshotRepository(session)
            ),
            window_hours=window_hours,
            amount_window_hours=amount_window_hours,
        )

    async def ingest_message(self, *, channel: str, sender: str, body: str, received_at: datetime, external_id: str | None) -> Transaction | None:
        message = RawMessage(
            channel=channel, sender=sender, body=body,
            received_at=received_at, external_id=external_id, parsed_status="pending",
        )
        stored = await self.messages.add_if_new(message)
        if stored is None:
            return None
        try:
            parsed = self.registry.parse(sender, body, received_at)
        except Exception:
            parsed = None
        if parsed is None:
            # Not a transaction: try the credit-card bill path (statements /
            # payment reminders) before sending the message to needs_review.
            try:
                bill = parse_card_bill(sender, body, received_at)
            except Exception:
                bill = None
            if bill is not None:
                await CardBillRepository(self.session).upsert(
                    bank=bill["bank"], last4=bill["last4"], due_date=bill["due_date"],
                    total_due=bill["total_due"], min_due=bill["min_due"],
                    statement_period=bill["statement_period"], received_at=received_at,
                )
                stored.parsed_status = "bill"
                return None
            stored.parsed_status = "needs_review"
            return None
        return await self.dedup.ingest(parsed, stored)
