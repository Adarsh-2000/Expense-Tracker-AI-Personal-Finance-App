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
