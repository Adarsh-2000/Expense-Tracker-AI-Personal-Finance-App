from app.parsing.holdings import extract_holdings_from_text

# Representative block in the flattened Zerodha layout: a clean equity row, a
# mutual-fund row (INF prefix), a WRAPPED equity row (name continues on the next
# line while the numbers all sit on the ISIN line), and a zero-balance row that
# must be skipped. Columns: ISIN Company Curr Free Pldg Earmark Demat Remat
# Lockin Rate Value.
SAMPLE = """\
                                                  Holdings as on 2026-04-30:
    ISIN Code Company Name Curr. Bal Free Bal Pldg. Bal Earmark Bal Demat     Remat     Lockin     Rate      Value

   INE814H01029 ADANI POWER-EQ2/- 600.000 600.000 0.000 0.000 0.000 0.000 0.000 222.050 133230.000
   INE263A01024 BHARAT 500.000 500.000 0.000 0.000 0.000 0.000 0.000 431.350 215675.000
             ELECTRONIC-EQ
   INF769K01DM9 MIRAE ETSF DPGR 6132.442 816.351 0.000 0.000 0.000 0.000 5316.091 54.841 336309.252
   INE000000000 ZERO HOLDING-EQ 0.000 0.000 0.000 0.000 0.000 0.000 0.000 10.000 0.000
                Total:   7232.442  1916.351  0.000  0.000  0.000  0.000  5316.091           685214.252
"""


def test_extracts_stock_mf_and_wrapped_rows():
    holdings = extract_holdings_from_text(SAMPLE)
    by_isin = {h["isin"]: h for h in holdings}

    # Zero-balance row dropped; three real holdings remain.
    assert set(by_isin) == {"INE814H01029", "INE263A01024", "INF769K01DM9"}

    adani = by_isin["INE814H01029"]
    assert adani["name"] == "ADANI POWER-EQ2/-"
    assert adani["quantity"] == 600.0
    assert adani["rate"] == 222.05
    assert adani["value"] == 133230.0
    assert adani["type"] == "stock"

    # Wrapped row: numbers read off the ISIN line correctly regardless of the
    # name continuation on the following line.
    bharat = by_isin["INE263A01024"]
    assert bharat["quantity"] == 500.0
    assert bharat["rate"] == 431.35
    assert bharat["value"] == 215675.0
    assert bharat["type"] == "stock"

    # INF prefix => mutual fund.
    mirae = by_isin["INF769K01DM9"]
    assert mirae["type"] == "mutual_fund"
    assert mirae["quantity"] == 6132.442
    assert mirae["value"] == 336309.252


def test_zero_quantity_rows_skipped():
    holdings = extract_holdings_from_text(SAMPLE)
    assert all(h["quantity"] > 0 for h in holdings)


def test_total_line_not_parsed_as_holding():
    holdings = extract_holdings_from_text(SAMPLE)
    assert all("Total" not in h["name"] for h in holdings)
