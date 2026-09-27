from app.parsing.us_portfolio import parse_us_portfolio

# Trimmed body matching the INDmoney "US Stocks Analytics Report" email shape.
ANALYTICS_BODY = (
    "Check your US Stock Analytics Hi Harsh, We have updated your US Stock A/C "
    "ending with XXXXXXXX1347. Here's your latest US stock portfolio summary. "
    "Portfolio Overview Invested Value: $1.15 K Current Value: $1.65 K "
    "Absolute Returns: 43.72% Check your analytics and get a full breakdown."
)


def test_parses_aggregate_values():
    r = parse_us_portfolio(ANALYTICS_BODY)
    assert r is not None
    assert r["invested_usd"] == 1150.0       # $1.15 K
    assert r["current_usd"] == 1650.0        # $1.65 K
    assert r["returns_pct"] == 43.72
    assert r["account_last4"] == "1347"


def test_handles_plain_dollars_no_suffix():
    body = ("US Stock A/C ending with XXXX9988. Invested Value: $2,340.50 "
            "Current Value: $3,100.00 Absolute Returns: 32.45%")
    r = parse_us_portfolio(body)
    assert r is not None
    assert r["invested_usd"] == 2340.50
    assert r["current_usd"] == 3100.00
    assert r["returns_pct"] == 32.45
    assert r["account_last4"] == "9988"


def test_handles_millions_suffix():
    body = "Invested Value: $1.2 M Current Value: $1.5 M Absolute Returns: 25%"
    r = parse_us_portfolio(body)
    assert r["invested_usd"] == 1_200_000.0
    assert r["current_usd"] == 1_500_000.0


def test_negative_returns():
    body = "Invested Value: $1.00 K Current Value: $0.80 K Absolute Returns: -20.00%"
    r = parse_us_portfolio(body)
    assert r["current_usd"] == 800.0
    assert r["returns_pct"] == -20.0


def test_returns_none_without_current_value():
    assert parse_us_portfolio("Some unrelated promotional email about US stocks.") is None
