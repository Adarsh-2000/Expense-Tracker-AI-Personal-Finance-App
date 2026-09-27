"""The 16-category transaction taxonomy and helpers for living alongside legacy data.

The classifier (rules + model + LLM) emits these 16 fine categories. Historical rows
still carry the old 9 names, so the consumption/money-movement sets below include BOTH
spellings — nothing here triggers a destructive data migration.
"""
from __future__ import annotations

# Canonical 16-category taxonomy emitted by the classifier.
CANONICAL: list[str] = [
    "food_dining", "groceries", "shopping", "travel", "transport_fuel",
    "bills_utilities", "entertainment", "health", "education", "investments",
    "rent_housing", "emi_loans", "transfers_p2p", "atm_cash", "subscriptions",
    "fees_charges",
]

UNCATEGORIZED = "uncategorized"

# Old 9-name spellings -> canonical, used only when reading historical rows uniformly.
LEGACY_TO_CANONICAL: dict[str, str] = {
    "food": "food_dining",
    "bills": "bills_utilities",
    "bill payments": "bills_utilities",
    "transfers": "transfers_p2p",
    # groceries / shopping / travel / entertainment / subscriptions / investments
    # keep the same name across both taxonomies.
}

# Money-movement / savings categories excluded from "spend" and consumption math.
# Includes legacy AND canonical names so a mix of old and new rows both behave.
NON_CONSUMPTION: set[str] = {
    "transfers", "bill payments", "investments",          # legacy
    "transfers_p2p", "atm_cash",                          # canonical money-movement
}


def to_canonical(name: str | None) -> str:
    """Normalize any category name (legacy or canonical) to its canonical form."""
    n = (name or UNCATEGORIZED).strip().lower()
    return LEGACY_TO_CANONICAL.get(n, n)
