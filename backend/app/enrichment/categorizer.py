from __future__ import annotations

from app.parsing.types import normalize_merchant


class RuleCategorizer:
    def __init__(self, rules: dict[str, list[str]]):
        self._rules = {cat: [k.lower() for k in kws] for cat, kws in rules.items()}

    def categorize(self, merchant: str, is_transfer: bool = False) -> str:
        # Keyword categorization runs first so payments to merchant VPAs
        # (e.g. hotstaronline@ybl) still bucket by merchant keyword.
        text = normalize_merchant(merchant)
        for category, keywords in self._rules.items():
            if any(kw in text for kw in keywords):
                return category
        # Only when nothing matched does the transfer flag decide the bucket.
        return "transfers" if is_transfer else "uncategorized"
