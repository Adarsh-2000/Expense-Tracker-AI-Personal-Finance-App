from __future__ import annotations


def project_net_worth(
    *,
    equity: float,
    fd: float,
    savings: float,
    equity_rate: float,
    fd_rate: float,
    savings_rate: float,
    sip: float,
    sip_after: float,
    step_after_month: int,
    years: int,
    start_year: int,
    start_month: int,
) -> dict:
    """Month-by-month net-worth projection, snapshotted yearly.

    Each bucket compounds monthly at its annual rate / 12. A monthly SIP is added
    to the equity bucket: `sip` for the first `step_after_month` months, then
    `sip_after` (step-up). Returns yearly snapshots [year 0 (today) .. year N].

    All figures are nominal (future rupees, not inflation-adjusted).
    """
    e_m = equity_rate / 100.0 / 12.0
    f_m = fd_rate / 100.0 / 12.0
    s_m = savings_rate / 100.0 / 12.0

    eq, fdv, sav = float(equity), float(fd), float(savings)

    def snapshot(year: int) -> dict:
        return {
            "year": year,
            "equity": round(eq, 2),
            "fd": round(fdv, 2),
            "savings": round(sav, 2),
            "total": round(eq + fdv + sav, 2),
        }

    series = [snapshot(start_year)]
    months = years * 12
    for m in range(1, months + 1):
        contribution = sip if m <= step_after_month else sip_after
        eq = eq * (1 + e_m) + contribution
        fdv = fdv * (1 + f_m)
        sav = sav * (1 + s_m)
        if m % 12 == 0:
            series.append(snapshot(start_year + m // 12))

    return {
        "assumptions": {
            "equity_rate": equity_rate,
            "fd_rate": fd_rate,
            "savings_rate": savings_rate,
            "sip": sip,
            "sip_after": sip_after,
            "step_after_month": step_after_month,
            "years": years,
        },
        "current_total": series[0]["total"],
        "projected_total": series[-1]["total"],
        "series": series,
    }
