from __future__ import annotations

from app.enrichment.balances import BalanceService
from app.enrichment.cascade_categorizer import CascadeCategorizer
from app.entities import RawMessage, Transaction
from app.parsing.types import ParsedTransaction
from app.repositories import AccountRepository, CategoryRepository, TransactionRepository


class DedupEngine:
    def __init__(self, *, accounts: AccountRepository, transactions: TransactionRepository,
                 categories: CategoryRepository, categorizer: CascadeCategorizer,
                 balances: BalanceService, window_hours: int, amount_window_hours: int):
        self.accounts = accounts
        self.transactions = transactions
        self.categories = categories
        self.categorizer = categorizer
        self.balances = balances
        self.window_hours = window_hours
        self.amount_window_hours = amount_window_hours

    async def ingest(self, pt: ParsedTransaction, source: RawMessage) -> Transaction:
        # Messages that omit a card/account number (e.g. BoB UPI credit SMS)
        # carry last4=None; map to "" so the account identity unique constraint
        # (NULLs compare unequal in SQL) still collapses them to one account.
        account_last4 = pt.last4 or ""
        account = await self.accounts.get_or_create(bank=pt.bank, type=pt.account_type, last4=account_last4)
        fingerprint = pt.fingerprint()
        # Prefer the precise datetime parsed from the body over the message
        # received_at when available.
        occurred_at = pt.txn_time or pt.occurred_at

        # TIER 1: exact fingerprint match within the wide window. A match from a
        # DIFFERENT channel is the same purchase (e.g. SMS + email, same merchant
        # name) -> attach as another source, no new row. Same-channel repeat is a
        # genuine second purchase and falls through to create a new row.
        # NOTE: redelivery protection relies on RawMessage.external_id (deduped
        # upstream in IngestionService); both connectors always supply external_id.
        match = await self.transactions.find_recent_by_fingerprint(
            fingerprint, before=occurred_at, window_hours=self.window_hours
        )
        if match is not None and not await self.transactions.has_source_from_channel(match.id, source.channel):
            source.transaction = match
            source.parsed_status = "parsed"
            return match

        # TIER 2: no exact fingerprint match. Catch the same purchase reported with
        # DIFFERENT merchant names across channels (e.g. SMS "Zomato Ltd" + email
        # "ETERNAL LIMITED") by matching account+amount+direction within a TIGHTER
        # window. The same-channel guard still applies so genuine same-channel
        # repeats (two coffees of the same price) stay separate.
        amount_match = await self.transactions.find_recent_amount_match(
            account_id=account.id, amount=pt.amount, direction=pt.direction,
            before=occurred_at, window_hours=self.amount_window_hours,
        )
        if amount_match is not None and not await self.transactions.has_source_from_channel(amount_match.id, source.channel):
            source.transaction = amount_match
            source.parsed_status = "parsed"
            return amount_match

        category = await self.categories.get_or_create(self.categorizer.categorize(pt.merchant, pt.is_transfer))
        await self.categories.session.flush()
        txn = await self.transactions.create(
            amount=pt.amount, direction=pt.direction, merchant=pt.merchant,
            occurred_at=occurred_at, account_id=account.id, fingerprint=fingerprint,
            category_id=category.id, entry_type="auto", balance_after=pt.balance_after,
            currency=pt.currency, original_amount=pt.original_amount,
            txn_ref=pt.txn_ref,
        )
        source.transaction = txn
        source.parsed_status = "parsed"
        # Balance is recorded only when CREATING a transaction. On a cross-channel
        # collapse we intentionally do not re-apply balance (avoids duplicate snapshots);
        # tradeoff: a balance_after present only on the later channel is not captured.
        await self.balances.apply(account, pt.balance_after, occurred_at)
        return txn
