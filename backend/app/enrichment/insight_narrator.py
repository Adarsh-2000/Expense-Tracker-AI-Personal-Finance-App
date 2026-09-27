"""Optional LLM narration layer for the Insight screen.

The deterministic engine (`insights_engine.py`) computes every number; this module
only *rewrites* the resulting cards into warmer, friendlier prose via a local LLM
(OpenAI-compatible endpoint, e.g. `mlx_lm.server`). Two safety rails make it safe to
ship behind a flag:

  1. Number-fidelity guard — every digit-run present in the card's title/detail must
     survive in the narration; if any number drifts, the original card is kept. This
     is the failure mode benchmarking flagged (an LLM asked to *compute* hallucinated a
     3x-too-large total) — so the LLM never computes here, and we verify it never
     silently altered a figure either.
  2. Graceful degradation — any error / timeout / LLM-down returns the card unchanged.

When narration succeeds, the card gains a `"narration"` field; `title`/`detail` (and
their exact numbers) are left untouched so the UI can always fall back to them.
"""
from __future__ import annotations

import asyncio
import re

import httpx

from app.config import settings

_NUM_RE = re.compile(r"\d+")
_MAX_CONCURRENCY = 4  # don't overwhelm a single-process local LLM server

_PROMPT = (
    "Rewrite this personal-finance insight as ONE warm, friendly sentence for the user. "
    "Keep every number, currency symbol, and percentage EXACTLY as written — do not "
    "change, round, add, or omit any of them. Reply with only the sentence.\n"
    "Insight: {text}"
)


def _digits(text: str) -> set[str]:
    """Digit-runs with grouping separators removed, so '₹3,39,803' == '339803'."""
    return set(_NUM_RE.findall(text.replace(",", "")))


def _card_numbers(card: dict) -> set[str]:
    return _digits(f"{card.get('title', '')} {card.get('detail', '')}")


async def _narrate_one(client: httpx.AsyncClient, sem: asyncio.Semaphore, card: dict) -> dict:
    source = f"{card.get('title', '')}. {card.get('detail', '')}".strip()
    if not source:
        return card
    want = _card_numbers(card)
    try:
        async with sem:
            resp = await client.post(
                f"{settings.llm_url}/v1/chat/completions",
                json={
                    "model": settings.llm_model,
                    "temperature": 0,
                    "max_tokens": 80,
                    "messages": [{"role": "user", "content": _PROMPT.format(text=source)}],
                },
            )
        resp.raise_for_status()
        narration = resp.json()["choices"][0]["message"]["content"].strip()
    except Exception:
        return card  # LLM down / timeout / malformed response -> keep exact card

    # number-fidelity guard: every source number must reappear, none may be invented
    if not narration or not want.issubset(_digits(narration)):
        return card
    return {**card, "narration": narration}


async def narrate_cards(cards: list[dict]) -> list[dict]:
    """Return the cards with an added `narration` field where the LLM faithfully
    rewrote them. No-op (returns cards unchanged) when disabled or on any failure."""
    if not settings.insights_narrate or not cards:
        return cards
    sem = asyncio.Semaphore(_MAX_CONCURRENCY)
    try:
        async with httpx.AsyncClient(timeout=20.0) as client:
            return await asyncio.gather(*[_narrate_one(client, sem, c) for c in cards])
    except Exception:
        return cards  # never let narration break the insights feed
