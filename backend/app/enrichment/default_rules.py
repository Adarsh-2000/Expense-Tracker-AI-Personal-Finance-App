DEFAULT_RULES: dict[str, list[str]] = {
    # Software/SaaS subscriptions. Placed before food/groceries so e.g. a
    # "GITHUB" or "GOOGLE" charge buckets here rather than a stray keyword.
    "subscriptions": [
        "anthropic", "claude", "openai", "chatgpt", "cursor", "github",
        "netflix", "spotify", "hotstar", "prime", "google", "vercel",
        "notion", "figma", "youtube premium", "icloud", "apple.com/bill",
        "adobe", "canva", "linkedin", "midjourney",
    ],
    "food": ["swiggy", "zomato", "restaurant", "cafe", "dominos", "kfc", "mcdonald", "pizza", "hotel", "bakery", "biryani"],
    "groceries": ["bigbasket", "blinkit", "blink comm", "zepto", "dmart", "grocery", "kirana", "reliance fresh", "more retail", "instamart", "grofers"],
    "shopping": ["amazon", "amzn", "flipkart", "myntra", "ajio", "store", "mall", "lifestyle", "nykaa", "meesho", "croma", "reliance digital", "adidas", "nike", "samsung", "decathlon", "ikea", "tira", "puma"],
    "travel": ["uber", "ola", "rapido", "irctc", "indigo", "makemytrip", "redbus", "ixigo", "goibibo", "yatra", "petrol", "fuel", "hpcl", "iocl", "bpcl"],
    # Bill Payments: settlements of recurring bills — credit-card bills (CRED /
    # billdesk / "credit card payment"), mobile & postpaid recharges, internet /
    # wifi / broadband, DTH, and utilities (electricity / water / gas). Placed
    # before "entertainment"/"transfers". Note: credit-card bill payments double
    # the underlying card transactions in gross outflow — kept here (not transfers)
    # because the user wants them visible as their own Bill Payments category.
    "bill payments": [
        # credit-card bills
        "cred club", "cred wallet", "cred_wallet", "billdesk", "credit card payment",
        "card payment", "bill payment", "credit card bill", "card bill", "creditcard payment",
        # mobile / telecom
        "airtel", "jio prepaid", "jio postpaid", "jio recharge", "vodafone", "vi postpaid",
        "recharge", "postpaid", "bsnl",
        # internet / wifi / broadband / dth
        "broadband", "wifi", "fibernet", "act fibernet", "hathway", "dth",
        "tata play", "tatasky", "dish tv", "jiofiber",
        # utilities
        "electricity", "bescom", "water bill", "gas bill", "piped gas", "tata power",
        "adani electric", "bijli", "utility bill",
    ],
    "entertainment": ["bookmyshow", "pvr", "inox", "disney", "prime video"],
    # Investments: SIPs, mutual funds, and broking platforms — wealth movement.
    # Placed before "transfers" so a SIP/MF debit buckets here, not as a generic
    # transfer.
    "investments": [
        "sip", "mutual fund", "zerodha", "iccl", "groww", "coin", "broking",
        "kuvera", "indmoney", "indstocks", "nav ", "folio", "smallcase",
        "investment", "etf", "nps", "elss",
    ],
    # Non-bill money movement: P2P/UPI transfers, NEFT/IMPS/RTGS.
    "transfers": ["upi transfer", "neft", "imps", "rtgs", "@ybl", "@oksbi", "@okhdfcbank", "@okaxis", "@okicici", "@paytm", "@ibl", "@axl", "_payt", "transferred"],
}
