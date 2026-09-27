from __future__ import annotations

from app.parsing.types import normalize_merchant

# Sub-category taxonomy: top-level category -> {subcategory: [merchant keywords]}.
# Derived at query time from the merchant string (no schema/migration needed),
# mirroring how RuleCategorizer assigns the top-level category. Keep keywords
# lowercase; first match wins within a category.
SUBCATEGORY_RULES: dict[str, dict[str, list[str]]] = {
    "food": {
        "Food Delivery": ["swiggy", "zomato", "eternal limited", "eternal"],
        "Restaurants & Cafes": [
            "restaurant", "cafe", "coffee", "starbucks", "barista", "chai",
            "dominos", "kfc", "mcdonald", "pizza", "biryani", "bakery", "hotel",
            "dhaba", "kitchen", "foods", "eat",
        ],
    },
    "groceries": {
        "Quick Commerce": ["blinkit", "zepto", "instamart", "grofers"],
        "Supermarket": [
            "bigbasket", "dmart", "d mart", "reliance fresh", "more retail",
            "spencer", "star bazaar", "smart bazaar",
        ],
        "Kirana & Local": ["kirana", "grocery", "general store", "provision"],
    },
    "shopping": {
        "Electronics": ["croma", "reliance digital", "samsung", "vijay sales", "apple store"],
        "Fashion & Apparel": [
            "myntra", "ajio", "adidas", "nike", "puma", "lifestyle",
            "decathlon", "zara", "h&m", "uniqlo", "tira", "westside",
        ],
        "Marketplace": ["amazon", "amzn", "flipkart", "meesho"],
        "Beauty & Personal Care": ["nykaa", "sephora", "purplle"],
    },
    "travel": {
        "Cabs & Auto": ["uber", "ola", "rapido"],
        "Flights": [
            "indigo", "makemytrip", "make my trip", "goibibo", "yatra", "ixigo",
            "vistara", "spicejet", "air india", "akasa",
        ],
        "Trains & Bus": ["irctc", "redbus", "abhibus"],
        "Fuel": ["petrol", "fuel", "hpcl", "iocl", "bpcl", "shell", "indian oil"],
        "Hotels & Stays": ["oyo", "treebo", "fabhotel", "airbnb", "stay"],
    },
    "bill payments": {
        "Credit Card Bills": ["cred", "billdesk", "card payment", "credit card payment",
                              "creditcard payment", "bill payment", "credit card bill", "card bill"],
        "Mobile & Recharge": ["airtel", "jio prepaid", "jio postpaid", "jio recharge",
                              "vodafone", "vi ", "recharge", "postpaid", "bsnl"],
        "Internet & Wifi": ["broadband", "wifi", "fibernet", "act fibernet", "hathway", "jiofiber"],
        "DTH": ["dth", "tata play", "tatasky", "dish tv"],
        "Utilities": ["electricity", "bescom", "water", "gas", "piped", "tata power",
                      "adani electric", "bijli", "utility"],
    },
    "entertainment": {
        "Movies & Events": ["bookmyshow", "pvr", "inox", "district", "cinema"],
        "Streaming": ["disney", "prime video"],
    },
    "subscriptions": {
        "AI Tools": ["anthropic", "claude", "openai", "chatgpt", "cursor", "midjourney", "perplexity"],
        "Streaming & Music": ["netflix", "spotify", "hotstar", "prime", "youtube", "jiocinema"],
        "Productivity & Dev": ["github", "notion", "figma", "vercel", "adobe", "canva", "linkedin"],
        "Cloud & Storage": ["google", "icloud", "apple.com", "dropbox"],
    },
    "investments": {
        "SIP": ["sip", "systematic"],
        "Mutual Funds": ["mutual fund", "elss", "folio", "nav ", "amc"],
        "Stocks & Broking": ["zerodha", "iccl", "groww", "coin", "broking",
                             "indmoney", "indstocks", "smallcase", "etf"],
        "Retirement": ["nps", "pension", "ppf"],
    },
    "transfers": {
        "Bank Transfers": ["neft", "imps", "rtgs", "upi transfer"],
    },
    "health": {
        "Hospital & Clinic": ["hospital", "marengo", "apollo", "clinic", "medanta", "fortis", "max health"],
        "Pharmacy": ["pharmacy", "pharmeasy", "1mg", "netmeds", "medplus", "opd pharmacy"],
        "Diagnostics": ["diagnostic", "pathology", "dr lal", "metropolis", " lab"],
    },
}


def subcategorize(category: str, merchant: str) -> str:
    """Derive a sub-category from the top-level category + merchant string.

    Returns a specific sub-category when a keyword matches; otherwise a sensible
    default. For transfers with an unmatched named payee, buckets as "People
    (P2P)" (a real person/handle) so money sent to individuals is grouped.
    """
    text = normalize_merchant(merchant or "")
    rules = SUBCATEGORY_RULES.get(category, {})
    for subcat, keywords in rules.items():
        if any(kw in text for kw in keywords):
            return subcat

    if category == "transfers":
        # Channel-prefixed ("upi <ref>", "neft...") or any numeric ref → a bank
        # transfer; a bare alphabetic name → money sent to a person.
        if text.startswith(("upi", "neft", "imps", "rtgs", "ach")) or any(ch.isdigit() for ch in text):
            return "Bank Transfers"
        if text:
            return "People (P2P)"
        return "Bank Transfers"
    if category == "uncategorized":
        return "Uncategorized"
    return f"Other {category.title()}"
