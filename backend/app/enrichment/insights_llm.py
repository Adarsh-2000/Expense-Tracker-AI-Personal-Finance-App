"""LLM insight generation under the 'arrange facts, never derive them' contract.

The analytics layer pre-computes EVERYTHING numeric — totals, shares, the
weekend/weekday comparison verdict, and savings figures — and hands the LLM a payload
of only *_display strings. The model selects and phrases; it never calculates or
compares. A digit-normalized grounding guard then strips any sentence containing a
number that isn't present in the payload, so fabricated figures can't reach the screen.

Endpoint-swappable: uses settings.llm_url/llm_model, so the same code runs against a
local 7B now or a frontier model (hybrid) with no change.
"""
from __future__ import annotations

import json
import re
from collections import defaultdict

import httpx

from app.config import settings

# money-movement = credit-card repayments + transfers + investments (NOT spending)
_MOVE_RE = re.compile(r"cred club|cred wallet|cred_wallet|credit card|card payment|card bill|billdesk", re.I)
_MOVE_CATS = {"transfers_p2p", "atm_cash", "investments", "transfers", "bill payments"}


def _is_movement(merchant: str, category: str) -> bool:
    return category in _MOVE_CATS or bool(_MOVE_RE.search(merchant or ""))


def _inr(n: float) -> str:
    return "Rs" + f"{round(n):,}"


def build_payload(rows: list[dict], period_label: str) -> dict:
    """rows: [{merchant, amount, occurred_at(datetime), category}] -> a payload of
    ONLY pre-formatted display strings (no raw operands for the model to recombine)."""
    cons = [r for r in rows if not _is_movement(r["merchant"], r["category"])]
    move = [r for r in rows if _is_movement(r["merchant"], r["category"])]
    ctot = sum(r["amount"] for r in cons) or 0.0
    mtot = sum(r["amount"] for r in move) or 0.0

    cat = defaultdict(float)
    for r in cons:
        cat[r["category"]] += r["amount"]
    top_cats = sorted(cat.items(), key=lambda kv: -kv[1])[:5]

    agg: dict[str, list] = defaultdict(lambda: [0.0, 0])
    for r in cons:
        agg[r["merchant"]][0] += r["amount"]
        agg[r["merchant"]][1] += 1
    top_m = sorted(agg.items(), key=lambda kv: -kv[1][0])[:6]

    wk = sum(r["amount"] for r in cons if r["occurred_at"].weekday() >= 5)
    wd = ctot - wk
    if wd >= wk:
        pct = round((wd - wk) / wk * 100) if wk else 0
        cmp_display = f"Weekday spending ({_inr(wd)}) is higher than weekend ({_inr(wk)})"
        cmp_pct = f"{pct}% higher on weekdays" if wk else "nearly all spending is on weekdays"
    else:
        pct = round((wk - wd) / wd * 100) if wd else 0
        cmp_display = f"Weekend spending ({_inr(wk)}) is higher than weekday ({_inr(wd)})"
        cmp_pct = f"{pct}% higher on weekends" if wd else "nearly all spending is on weekends"

    # pre-computed savings suggestions (the LLM never multiplies). Two payload bugs
    # fixed here: (1) state a realistic saving (~15% of the merchant's spend), not the
    # full total, so it can't be read as "save everything"; (2) only suggest batching
    # for genuine small-basket habits (groceries/food) — never a subscription, where
    # "fewer larger orders" is nonsense. uncategorized is skipped as a trim target.
    _merchant_cat = {}
    for r in cons:
        _merchant_cat.setdefault(r["merchant"], r["category"])
    _BATCHABLE = {"groceries", "food_dining"}

    sugg: list[str] = []
    real_cat = next(((c, a) for c, a in top_cats if c != "uncategorized"), None)
    if real_cat:
        c0, a0 = real_cat
        sugg.append(f"Trimming {c0} by 10% would save about {_inr(a0 * 0.10)}")
    batchable = [(m, v) for m, v in sorted(agg.items(), key=lambda kv: -kv[1][1])
                 if v[1] >= 5 and _merchant_cat.get(m) in _BATCHABLE]
    if batchable:
        m, (mt, mc) = batchable[0]
        sugg.append(f"{m}: {mc} small orders ({_inr(mt)} total); batching into fewer "
                    f"trips could save roughly {_inr(mt * 0.15)}")

    return {
        "period": period_label,
        "consumption_total_display": _inr(ctot),
        "consumption_txn_count_display": f"{len(cons)} spending transactions",
        "money_movement_total_display_NOT_SPENDING": _inr(mtot),
        "money_movement_note": "credit-card repayments, transfers and investments — NOT spending",
        "spend_comparison_display": cmp_display,
        "spend_comparison_pct_display": cmp_pct,
        "top_categories": [
            {"name": c, "amount_display": _inr(a),
             "share_display": f"{round(a / ctot * 100)}% of spending"} for c, a in top_cats
        ] if ctot else [],
        "top_merchants": [
            {"merchant": m, "total_display": _inr(v[0]),
             "count_display": f"{v[1]} transactions", "avg_display": f"{_inr(v[0] / v[1])} per transaction"}
            for m, v in top_m
        ],
        "savings_suggestions_display": sugg,
    }


_SYSTEM = (
    "You are a personal-finance writer. The user message is a JSON payload whose fields are "
    "ALREADY-FORMATTED display strings. Your ONLY job is to choose which facts to mention and "
    "phrase them warmly in 3 short sections: Spending Patterns, How To Save More, Money Radar.\n"
    "STRICT RULES:\n"
    "- Every number, currency amount, percentage and comparison in your reply MUST be copied "
    "from a *_display field in the payload. Do NOT compute, add, multiply, average, or compare "
    "anything yourself.\n"
    "- To state which is higher (weekday vs weekend), copy spend_comparison_display.\n"
    "- For savings tips, use only savings_suggestions_display. Never invent a percentage or amount.\n"
    "- Items under money_movement are NOT spending."
)


def _digits(s) -> set[str]:
    return set(re.findall(r"\d+", str(s).replace(",", "")))


def _payload_digits(payload) -> set[str]:
    acc: set[str] = set()

    def walk(x):
        if isinstance(x, dict):
            for v in x.values():
                walk(v)
        elif isinstance(x, list):
            for v in x:
                walk(v)
        else:
            acc.update(_digits(x))

    walk(payload)
    return acc


def ground_guard(text: str, payload: dict) -> tuple[str, list[str]]:
    """Strip any sentence containing a number whose digits are absent from the payload.
    Header lines and number-free sentences pass through untouched."""
    allowed = _payload_digits(payload)
    kept, stripped = [], []
    for line in text.split("\n"):
        if not line.strip() or line.lstrip().startswith("#") or line.lstrip().startswith("**"):
            kept.append(line)
            continue
        out_sents = []
        for sent in re.split(r"(?<=[.!?])\s+", line):
            if _digits(sent) - allowed:
                stripped.append(sent.strip())
            else:
                out_sents.append(sent)
        kept.append(" ".join(out_sents))
    cleaned = "\n".join(k for k in kept).strip()
    cleaned = re.sub(r"\n{3,}", "\n\n", cleaned)
    return cleaned, stripped


async def generate_insights(rows: list[dict], period_label: str) -> dict:
    """Build the payload, ask the LLM to phrase it, and guard the output. Returns
    {text, payload, stripped, ok}; text is empty and ok False on any LLM failure."""
    payload = build_payload(rows, period_label)
    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            resp = await client.post(
                f"{settings.llm_url}/v1/chat/completions",
                json={"model": settings.llm_model, "temperature": 0.2, "max_tokens": 400,
                      "messages": [{"role": "system", "content": _SYSTEM},
                                   {"role": "user", "content": json.dumps(payload)}]},
            )
        resp.raise_for_status()
        raw = resp.json()["choices"][0]["message"]["content"].strip()
    except Exception:
        return {"text": "", "payload": payload, "stripped": [], "ok": False}
    text, stripped = ground_guard(raw, payload)
    return {"text": text, "payload": payload, "raw": raw, "stripped": stripped, "ok": True}


_SECTION_HEADERS = re.compile(
    r"^[ \t]*(?:#{1,6}[ \t]*|\*\*[ \t]*)?"
    r"(spending patterns|how to save more|money radar)"
    r"[ \t]*:?[ \t]*\*{0,2}[ \t]*$",
    re.IGNORECASE | re.MULTILINE,
)
_LABEL_TO_KEY = {
    "spending patterns": "spending_patterns",
    "how to save more": "how_to_save",
    "money radar": "money_radar",
}


def split_sections(text: str) -> dict[str, str]:
    """Split the LLM's 3-section markdown into {spending_patterns, how_to_save,
    money_radar}. Accepts '### Header' or '**Header:**'. Missing header -> ''."""
    out = {"spending_patterns": "", "how_to_save": "", "money_radar": ""}
    matches = list(_SECTION_HEADERS.finditer(text or ""))
    for i, m in enumerate(matches):
        key = _LABEL_TO_KEY[m.group(1).lower()]
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        out[key] = text[m.end():end].strip()
    return out
