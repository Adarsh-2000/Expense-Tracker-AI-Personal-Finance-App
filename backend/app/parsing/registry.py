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


def default_registry() -> "BankParserRegistry":
    from app.parsing.banks.axis import AxisParser
    from app.parsing.banks.bob import BobParser
    from app.parsing.banks.idfc import IdfcParser

    return BankParserRegistry([AxisParser(), IdfcParser(), BobParser()])
