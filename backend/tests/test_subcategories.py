from app.enrichment.subcategories import subcategorize


def test_food_delivery_vs_restaurant():
    assert subcategorize("food", "ZOMATO") == "Food Delivery"
    assert subcategorize("food", "Truffles Cafe") == "Restaurants & Cafes"


def test_transfers_split():
    assert subcategorize("transfers", "WANMEI KONYAK M") == "People (P2P)"
    assert subcategorize("transfers", "UPI 320648725455") == "Bank Transfers"


def test_investments_split():
    assert subcategorize("investments", "ICCL ZERODHA") == "Stocks & Broking"
    assert subcategorize("investments", "Groww SIP") == "SIP"
    assert subcategorize("investments", "MIRAE ETF Mutual Fund") == "Mutual Funds"


def test_bill_payments_split():
    assert subcategorize("bill payments", "CRED Club") == "Credit Card Bills"
    assert subcategorize("bill payments", "Airtel Postpaid") == "Mobile & Recharge"
    assert subcategorize("bill payments", "ACT Fibernet") == "Internet & Wifi"
    assert subcategorize("bill payments", "Tata Play DTH") == "DTH"
    assert subcategorize("bill payments", "BESCOM Electricity") == "Utilities"


def test_shopping_electronics_vs_fashion():
    assert subcategorize("shopping", "Croma") == "Electronics"
    assert subcategorize("shopping", "ADIDAS") == "Fashion & Apparel"
    assert subcategorize("shopping", "AMAZON") == "Marketplace"


def test_subscriptions_ai_tools():
    assert subcategorize("subscriptions", "ANTHROPIC") == "AI Tools"
    assert subcategorize("subscriptions", "NETFLIX") == "Streaming & Music"


def test_unknown_falls_back():
    assert subcategorize("food", "Some Random Stall") == "Other Food"
    assert subcategorize("uncategorized", "whatever") == "Uncategorized"
