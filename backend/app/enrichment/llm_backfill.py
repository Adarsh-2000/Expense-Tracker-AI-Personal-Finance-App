"""Opt-in LLM backfill for transactions the rules+model cascade left uncategorized.

Kept OUT of the ingestion hot path (the LLM is ~1s/txn). Triggered manually via
`POST /admin/llm-backfill`, it classifies still-uncategorized transactions by sending
their RAW SMS/email body (not the bare merchant) to a local LLM at `settings.llm_url`
— benchmarks showed the raw body nearly doubles LLM accuracy vs the merchant string.

Graceful: any error / LLM-down yields `None` for that row (left uncategorized), never
raising into the request.
"""
from __future__ import annotations

import asyncio
import re

import httpx

from app.config import settings
from app.enrichment.taxonomy import CANONICAL

_MAX_CONCURRENCY = 4

_PROMPT = (
    "Classify this Indian bank transaction into exactly one category.\n"
    "Categories: " + ", ".join(CANONICAL) + "\n"
    "Transaction: {text}\n"
    "Reply with only the category name. If you are genuinely unsure, reply exactly: unknown"
)


def _parse(answer: str | None) -> str | None:
    if not answer or not answer.strip():
        return None
    token = re.split(r"[\s,.!\"']+", answer.strip().lower())[0]
    return token if token in CANONICAL else None


async def _classify_one(client: httpx.AsyncClient, sem: asyncio.Semaphore, text: str) -> str | None:
    if not text or not text.strip():
        return None
    try:
        async with sem:
            resp = await client.post(
                f"{settings.llm_url}/v1/chat/completions",
                json={
                    "model": settings.llm_model,
                    "temperature": 0,
                    "max_tokens": 8,
                    "messages": [{"role": "user", "content": _PROMPT.format(text=text)}],
                },
            )
        resp.raise_for_status()
        return _parse(resp.json()["choices"][0]["message"]["content"])
    except Exception:
        return None


async def classify_texts(texts: list[str]) -> list[str | None]:
    """Canonical category (or None) for each text, via the local LLM. All-None on
    failure so callers can simply skip the rows that stayed unresolved."""
    if not texts:
        return []
    sem = asyncio.Semaphore(_MAX_CONCURRENCY)
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            return await asyncio.gather(*[_classify_one(client, sem, t) for t in texts])
    except Exception:
        return [None] * len(texts)
