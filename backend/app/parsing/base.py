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
