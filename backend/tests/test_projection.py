from app.enrichment.projection import project_net_worth


def test_growth_only_no_sip_compounds_each_bucket():
    # 1 year, no SIP: each bucket grows by its annual rate (monthly compounded).
    r = project_net_worth(
        equity=100000, fd=100000, savings=100000,
        equity_rate=12.0, fd_rate=7.0, savings_rate=3.5,
        sip=0.0, sip_after=0.0, step_after_month=12,
        years=1, start_year=2026, start_month=1,
    )
    # year 0 snapshot is today
    assert r["series"][0]["year"] == 2026
    assert r["series"][0]["total"] == 300000
    y1 = r["series"][1]
    # monthly compounding: 100000 * (1 + r/12)^12
    assert abs(y1["equity"] - 100000 * (1 + 0.12 / 12) ** 12) < 1.0
    assert abs(y1["fd"] - 100000 * (1 + 0.07 / 12) ** 12) < 1.0
    assert abs(y1["savings"] - 100000 * (1 + 0.035 / 12) ** 12) < 1.0


def test_sip_adds_to_equity_monthly():
    # No growth, pure SIP: 12 months * 10000 added to equity.
    r = project_net_worth(
        equity=0, fd=0, savings=0,
        equity_rate=0.0, fd_rate=0.0, savings_rate=0.0,
        sip=10000, sip_after=10000, step_after_month=12,
        years=1, start_year=2026, start_month=1,
    )
    assert abs(r["series"][1]["equity"] - 120000) < 1.0


def test_sip_step_up_switches_amount():
    # 24 months, step after month 6: 6*1000 + 18*2000 = 6000 + 36000 = 42000.
    r = project_net_worth(
        equity=0, fd=0, savings=0,
        equity_rate=0.0, fd_rate=0.0, savings_rate=0.0,
        sip=1000, sip_after=2000, step_after_month=6,
        years=2, start_year=2026, start_month=1,
    )
    assert abs(r["series"][2]["equity"] - 42000) < 1.0


def test_series_length_and_assumptions_echoed():
    r = project_net_worth(
        equity=2618000, fd=500000, savings=601307,
        equity_rate=12.0, fd_rate=7.0, savings_rate=3.5,
        sip=75000, sip_after=100000, step_after_month=7,
        years=10, start_year=2026, start_month=6,
    )
    assert len(r["series"]) == 11  # year 0 .. year 10
    assert r["series"][0]["total"] == 2618000 + 500000 + 601307
    # 10-year total should comfortably exceed 1 crore with this SIP.
    assert r["series"][-1]["total"] > 10_000_000
    assert r["assumptions"]["equity_rate"] == 12.0
