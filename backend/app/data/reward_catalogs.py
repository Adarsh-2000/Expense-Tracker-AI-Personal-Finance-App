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
        "offer_categories": [
            {"title": "Airport lounge", "description": "Complimentary domestic lounge visits on milestone spends.", "kind": "lounge"},
            {"title": "Dining", "description": "Discounts at partner restaurants.", "kind": "dining"},
            {"title": "Movies", "description": "Buy-1-Get-1 movie tickets via partner.", "kind": "movies"},
            {"title": "Fuel", "description": "1% fuel surcharge waiver.", "kind": "fuel"},
            {"title": "Online brands", "description": "Accelerated reward points on online spends.", "kind": "shopping"},
            {"title": "Low forex markup", "description": "Reduced markup on international spends.", "kind": "forex"},
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
        "offer_categories": [
            {"title": "Dining Delights", "description": "Savings at partner restaurants.", "kind": "dining"},
            {"title": "Airport lounge", "description": "Complimentary lounge access on eligible cards.", "kind": "lounge"},
            {"title": "Fuel", "description": "Fuel surcharge waiver.", "kind": "fuel"},
            {"title": "EDGE partner brands", "description": "Offers across shopping & travel partners.", "kind": "shopping"},
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


def offer_categories_for(program: str) -> list[dict]:
    cat = _CATALOG.get(program)
    return cat.get("offer_categories", []) if cat else []
