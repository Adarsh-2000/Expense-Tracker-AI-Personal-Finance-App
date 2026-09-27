from __future__ import annotations

import io
import re

# A Zerodha "Monthly Demat Transaction with Holding statement" embeds a holdings
# table whose columns are:
#   ISIN Code | Company Name | Curr. Bal | Free Bal | Pldg. Bal | Earmark Bal |
#   Demat | Remat | Lockin | Rate | Value
#
# When the PDF text is flattened the company name occasionally wraps onto a
# following line, but the ROW with the numbers always begins with the ISIN and
# carries all of Curr.Bal ... Value on that single line. We therefore key off the
# ISIN line and read the numeric columns positionally: the first numeric token is
# Curr. Bal, the last is Value, the second-to-last is Rate.

# ISIN: 2-letter country (IN) + security-type letter (E=equity, F=MF) + 9
# alphanumerics. INF prefix => mutual fund, INE prefix => stock/equity.
_ISIN_RE = re.compile(r"\b(IN[EF][0-9A-Z]{9})\b")
_AS_OF_RE = re.compile(r"Holdings as on\s+(\d{4}-\d{2}-\d{2})")
# Numeric tokens in the table are always rendered with a decimal point.
_NUM_RE = re.compile(r"-?\d[\d,]*\.\d+")


def _classify(isin: str) -> str:
    return "mutual_fund" if isin.startswith("INF") else "stock"


def _to_float(token: str) -> float:
    return float(token.replace(",", ""))


def extract_holdings_from_text(text: str) -> list[dict]:
    """Pure row-extraction over the (decrypted, flattened) statement text.

    Scans the "Holdings as on ..." section line by line. Each line that begins
    with an ISIN and carries the full numeric row (>=9 numeric columns) is one
    holding. Wrapped continuation lines (name fragments, no ISIN) are skipped.
    Rows with a zero/blank Curr. Bal are dropped.
    """
    holdings: list[dict] = []
    lines = text.splitlines()

    # Restrict to the holdings section if present, so transaction-ledger lines
    # above it (which can also contain ISINs) are never mistaken for holdings.
    start = 0
    for i, line in enumerate(lines):
        if "Holdings as on" in line:
            start = i
            break

    seen: set[str] = set()
    for line in lines[start:]:
        if "Total:" in line:
            break
        m = _ISIN_RE.search(line)
        if not m:
            continue
        isin = m.group(1)
        # Numbers AFTER the ISIN are the data columns; an ISIN can contain digits
        # so only look past where it ends.
        tail = line[m.end():]
        nums = _NUM_RE.findall(tail)
        if len(nums) < 9:
            # Numbers wrapped off this line — not a complete row we can trust.
            continue
        quantity = _to_float(nums[0])
        if quantity == 0:
            continue
        if isin in seen:
            continue
        seen.add(isin)
        rate = _to_float(nums[-2])
        value = _to_float(nums[-1])
        # Company name is the text between the ISIN and the first number.
        name_part = tail[: tail.find(nums[0])].strip()
        name = re.sub(r"\s+", " ", name_part).strip() or isin
        holdings.append({
            "isin": isin,
            "name": name,
            "quantity": quantity,
            "rate": rate,
            "value": value,
            "type": _classify(isin),
        })
    return holdings


def _decrypted_text(pdf_bytes: bytes, password: str) -> str | None:
    """Open the (possibly encrypted) PDF and return its flattened layout text.

    Returns None if the PDF cannot be opened/decrypted. Uses pdfplumber with
    layout=True so wrapped rows keep their column alignment; google/heavy imports
    stay local so importing this module is cheap.
    """
    import pdfplumber
    from pypdf import PdfReader

    # First decrypt with pypdf (handles Zerodha's PAN password), then re-serialize
    # an un-encrypted copy for pdfplumber, whose layout extraction we rely on.
    try:
        reader = PdfReader(io.BytesIO(pdf_bytes))
        if reader.is_encrypted:
            if reader.decrypt(password) == 0:
                return None
        from pypdf import PdfWriter

        writer = PdfWriter()
        for page in reader.pages:
            writer.add_page(page)
        buf = io.BytesIO()
        writer.write(buf)
        decrypted_bytes = buf.getvalue()
    except Exception:
        return None

    try:
        with pdfplumber.open(io.BytesIO(decrypted_bytes)) as pdf:
            return "\n".join((p.extract_text(layout=True) or "") for p in pdf.pages)
    except Exception:
        # Fall back to pdfplumber opening the original directly with the password.
        try:
            with pdfplumber.open(io.BytesIO(pdf_bytes), password=password) as pdf:
                return "\n".join((p.extract_text(layout=True) or "") for p in pdf.pages)
        except Exception:
            return None


def parse_holding_statement(pdf_bytes: bytes, password: str) -> dict | None:
    """Decrypt a Zerodha monthly holding statement and extract its holdings.

    Returns {"as_of": "YYYY-MM-DD"|None, "holdings": [...]} or None if the PDF
    could not be opened/decrypted.
    """
    text = _decrypted_text(pdf_bytes, password)
    if text is None:
        return None
    as_of_m = _AS_OF_RE.search(text)
    as_of = as_of_m.group(1) if as_of_m else None
    return {"as_of": as_of, "holdings": extract_holdings_from_text(text)}
