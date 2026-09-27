from __future__ import annotations

import json
import os

# State file lives in backend/ (two levels up from app/connectors/).
_STATE_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "gmail_sync_state.json",
)


def state_path() -> str:
    return _STATE_PATH


def read_last_sync(path: str | None = None) -> int:
    """Return the last synced internalDate in ms, or 0 if no/invalid state."""
    path = path or _STATE_PATH
    try:
        with open(path) as f:
            data = json.load(f)
        return int(data.get("last_internal_date_ms", 0))
    except (FileNotFoundError, ValueError, TypeError, json.JSONDecodeError):
        return 0


def write_last_sync(ms: int, path: str | None = None) -> None:
    path = path or _STATE_PATH
    with open(path, "w") as f:
        json.dump({"last_internal_date_ms": int(ms)}, f)


def after_query_suffix(last_ms: int) -> str:
    """Build the Gmail ``after:`` clause from a ms timestamp.

    Gmail's ``after:`` operator takes epoch *seconds*. Returns e.g.
    " after:1700000000" or "" when there is no prior sync.
    """
    if not last_ms or last_ms <= 0:
        return ""
    return f" after:{int(last_ms) // 1000}"


def max_internal_date(fields: list[dict]) -> int:
    """Largest internalDate (ms) across fetched message dicts, else 0.

    Accepts dicts that carry an ``internal_date_ms`` key (added by the poller).
    """
    best = 0
    for f in fields:
        ms = int(f.get("internal_date_ms", 0) or 0)
        if ms > best:
            best = ms
    return best
