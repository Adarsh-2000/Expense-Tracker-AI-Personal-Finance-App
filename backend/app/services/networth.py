from __future__ import annotations

from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.services import gmail


async def resolved_balance(session, acc) -> dict:
    """Pick the freshest balance for a savings account: the user's manual entry
    if its date is >= the latest SMS-scanned balance date, else the SMS figure."""
    from app.enrichment.balance_scan import latest_balance_for
    scanned = await latest_balance_for(session, bank=acc.bank, last4=acc.last4 or "")
    sms_bal, sms_as_of = (scanned if scanned else (None, None))
    candidates = []
    if sms_bal is not None:
        candidates.append((sms_as_of, sms_bal, "sms"))
    if acc.manual_balance is not None:
        candidates.append((acc.manual_balance_at or "9999-12-31", acc.manual_balance, "manual"))
    if not candidates:
        return {"balance": acc.current_balance, "as_of": None, "source": "none"}
    as_of, bal, source = max(candidates, key=lambda c: c[0] or "")
    return {"balance": bal, "as_of": as_of, "source": source}


async def networth_components(session: AsyncSession) -> dict:
    """Net-worth building blocks across all asset types (all INR)."""
    from app.repositories import (
        AccountRepository, FixedDepositRepository, HoldingRepository,
        UsPortfolioRepository,
    )
    # Savings: freshest resolved balance per real bank account.
    accs = [a for a in await AccountRepository(session).list_all()
            if a.type == "bank" and (a.last4 or "").strip()]
    savings = 0.0
    for a in accs:
        rb = await resolved_balance(session, a)
        if rb["balance"] is not None:
            savings += rb["balance"]
    # Indian equity/MF holdings.
    holdings = await HoldingRepository(session).list_all()
    indian_equity = sum(h.value for h in holdings)
    # US stocks aggregate (INR-converted).
    us = await UsPortfolioRepository(session).get()
    fx = settings.fx_rates_to_inr.get("USD", 86.0)
    us_inr = (us.current_usd * fx) if us else 0.0
    # Fixed deposits (principal as current value).
    fds = await FixedDepositRepository(session).list_all()
    fd_total = sum(f.principal for f in fds)
    return {
        "savings": round(savings, 2),
        "fixed_deposits": round(fd_total, 2),
        "indian_equity": round(indian_equity, 2),
        "us_stocks": round(us_inr, 2),
    }


def us_stocks_block(us) -> dict | None:
    """Shape the stored US aggregate into the API block (USD + INR-converted)."""
    if us is None:
        return None
    fx = settings.fx_rates_to_inr.get("USD", 86.0)
    return {
        "broker": us.broker,
        "account_last4": us.account_last4,
        "invested_usd": us.invested_usd,
        "current_usd": us.current_usd,
        "current_inr": round(us.current_usd * fx, 2),
        "invested_inr": round(us.invested_usd * fx, 2) if us.invested_usd is not None else None,
        "returns_pct": us.returns_pct,
        "fx_rate": fx,
        "as_of": us.as_of,
    }


async def sync_us_portfolio(session: AsyncSession) -> dict | None:
    """Fetch + store the INDmoney US aggregate. Returns a status dict or None."""
    from app.parsing.us_portfolio import parse_us_portfolio
    from app.repositories import UsPortfolioRepository

    fetched = gmail.fetch_latest_us_analytics_data()
    if not fetched:
        return None
    html, as_of = fetched
    parsed = parse_us_portfolio(gmail.strip_html(html))
    if not parsed:
        return None
    await UsPortfolioRepository(session).upsert(
        invested_usd=parsed["invested_usd"], current_usd=parsed["current_usd"],
        returns_pct=parsed["returns_pct"], account_last4=parsed["account_last4"],
        as_of=as_of, broker="INDmoney")
    return {"current_usd": parsed["current_usd"], "as_of": as_of}


async def sync_investments(session: AsyncSession) -> dict:
    """Refresh all investment sources used by net worth: the US aggregate (parsed
    from email) and, when a PAN is set, the Indian holdings statement. Reusable by
    both the explicit /sync/portfolio route and the main /sync/gmail pipeline.
    Commits incrementally per source (US first, then holdings) — not atomic. Returns a status dict."""
    from app.parsing.holdings import parse_holding_statement
    from app.repositories import HoldingRepository, ProfileRepository

    # US aggregate is independent of PAN (parsed from the email body).
    us_result = await sync_us_portfolio(session)

    profile = await ProfileRepository(session).get()
    pan = profile.pan if profile else None
    if not pan:
        await session.commit()
        return {"status": "ok" if us_result else "no_pan", "us_stocks": us_result}

    pdf_bytes = gmail.fetch_latest_holding_pdf_bytes()
    if not pdf_bytes:
        await session.commit()
        return {"status": "no_statement", "us_stocks": us_result}

    parsed = parse_holding_statement(pdf_bytes, pan)
    if not parsed or not parsed.get("holdings"):
        await session.commit()
        return {"status": "parse_failed", "us_stocks": us_result}

    holdings = parsed["holdings"]
    as_of = parsed.get("as_of") or ""
    await HoldingRepository(session).replace_all(holdings, as_of)
    await session.commit()
    return {
        "status": "ok",
        "holdings": len(holdings),
        "total_value": round(sum(h["value"] for h in holdings), 2),
        "as_of": as_of,
        "us_stocks": us_result,
    }
