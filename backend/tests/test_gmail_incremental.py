import os

from app.connectors.gmail_state import (
    after_query_suffix,
    max_internal_date,
    read_last_sync,
    write_last_sync,
)


def test_after_query_suffix_converts_ms_to_seconds():
    assert after_query_suffix(1700000000000) == " after:1700000000"


def test_after_query_suffix_empty_when_no_prior_sync():
    assert after_query_suffix(0) == ""
    assert after_query_suffix(-5) == ""


def test_max_internal_date():
    fields = [
        {"internal_date_ms": 100},
        {"internal_date_ms": 999},
        {"internal_date_ms": 42},
        {},  # missing key -> treated as 0
    ]
    assert max_internal_date(fields) == 999


def test_max_internal_date_empty():
    assert max_internal_date([]) == 0


def test_read_write_roundtrip(tmp_path):
    path = os.path.join(tmp_path, "gmail_sync_state.json")
    assert read_last_sync(path) == 0  # missing file
    write_last_sync(1748764800000, path)
    assert read_last_sync(path) == 1748764800000


def test_read_last_sync_handles_corrupt_file(tmp_path):
    path = os.path.join(tmp_path, "gmail_sync_state.json")
    with open(path, "w") as f:
        f.write("not json")
    assert read_last_sync(path) == 0
