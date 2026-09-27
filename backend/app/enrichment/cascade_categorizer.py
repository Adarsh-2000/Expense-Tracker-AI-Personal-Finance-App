"""Inline transaction categorizer: merged rules -> supervised model.

Drop-in replacement for the old RuleCategorizer (same `categorize(merchant,
is_transfer)` signature, now emitting the 16-category taxonomy). The LLM is NOT in
this hot path — low-confidence rows are left `uncategorized` for the opt-in
`llm_backfill` pass, keeping ingestion fast.

The scikit-learn model is lazy-loaded (like the PDF/portfolio deps): if sklearn or the
model file is unavailable, the categorizer degrades to rules-only rather than failing.
"""
from __future__ import annotations

import re
import threading
from pathlib import Path

from app.enrichment.merged_rules import classify_merged

MODEL_PATH = Path(__file__).parent / "model" / "model.joblib"
CONFIDENCE_THRESHOLD = 0.70

_REF_RE = re.compile(r"\d{6,}")
_NOISE_RE = re.compile(r"[^a-z0-9@./ -]")
_MULTISPACE_RE = re.compile(r"\s+")

_pipe = None
_load_failed = False
_lock = threading.Lock()


def _normalize(text: str) -> str:
    """Match the training-time preprocessing (strip ref numbers + noise)."""
    t = (text or "").lower()
    t = _REF_RE.sub(" <ref> ", t)
    t = _NOISE_RE.sub(" ", t)
    return _MULTISPACE_RE.sub(" ", t).strip()


def _get_pipe():
    """Lazy, thread-safe, fault-tolerant model load. Returns None if unavailable."""
    global _pipe, _load_failed
    if _pipe is not None or _load_failed:
        return _pipe
    with _lock:
        if _pipe is None and not _load_failed:
            try:
                import warnings

                import joblib
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore")  # sklearn version-skew notices
                    # Trusted first-party artifact produced by this project's own
                    # train.py (not external input) — safe to unpickle.
                    _pipe = joblib.load(MODEL_PATH)
            except Exception:
                _load_failed = True  # sklearn/model missing -> rules-only
    return _pipe


def _model_predict(merchant: str) -> tuple[str | None, float]:
    pipe = _get_pipe()
    if pipe is None:
        return None, 0.0
    try:
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            proba = pipe.predict_proba([_normalize(merchant)])[0]
        i = int(proba.argmax())
        return str(pipe.classes_[i]), float(proba[i])
    except Exception:
        return None, 0.0


class CascadeCategorizer:
    """rules -> model cascade. `categorize` keeps the legacy string return for the
    ingestion call sites; `classify` exposes confidence + source for backfill."""

    def __init__(self, confidence_threshold: float = CONFIDENCE_THRESHOLD):
        self._threshold = confidence_threshold

    def classify(self, merchant: str, is_transfer: bool = False) -> dict:
        rc = classify_merged(merchant)
        if rc:
            return {"category": rc, "confidence": 0.97, "source": "rules"}
        if is_transfer:
            return {"category": "transfers_p2p", "confidence": 0.95, "source": "rules"}
        cat, conf = _model_predict(merchant)
        if cat and conf >= self._threshold:
            return {"category": cat, "confidence": round(conf, 3), "source": "model"}
        return {"category": "uncategorized", "confidence": round(conf, 3),
                "source": "model_low_confidence" if cat else "none"}

    def categorize(self, merchant: str, is_transfer: bool = False) -> str:
        return self.classify(merchant, is_transfer)["category"]
