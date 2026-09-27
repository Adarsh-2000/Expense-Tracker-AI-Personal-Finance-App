"""Layer-1 deterministic keyword rules (merged ruleset, 16-category taxonomy).

Ported from the txn-classifier benchmark winner: the original brand rules + the
expense-tracker production keywords + real-data aliases, ordered specific-first
(first match wins). Validated at ~0.93 precision on a real-transaction holdout.
"""
from __future__ import annotations

import re

# substring (lowercase) -> canonical category. Ordered: more specific first.
KEYWORD_RULES: list[tuple[str, str]] = [
    # food
    ("swiggy instamart", "groceries"),
    ("swiggy", "food_dining"), ("zomato", "food_dining"), ("dominos", "food_dining"),
    ("mcdonald", "food_dining"), ("kfc", "food_dining"), ("pizza hut", "food_dining"),
    # groceries
    ("blinkit", "groceries"), ("zepto", "groceries"), ("bigbasket", "groceries"),
    ("dmart", "groceries"), ("avenue supermarts", "groceries"), ("jiomart", "groceries"),
    ("kirana", "groceries"), ("supermarket", "groceries"), ("provision store", "groceries"),
    # shopping
    ("amazon pay", "shopping"), ("amazon", "shopping"), ("flipkart", "shopping"),
    ("myntra", "shopping"), ("ajio", "shopping"), ("meesho", "shopping"),
    ("nykaa", "shopping"), ("croma", "shopping"), ("decathlon", "shopping"),
    # travel
    ("irctc", "travel"), ("makemytrip", "travel"), ("goibibo", "travel"),
    ("indigo", "travel"), ("interglobe", "travel"), ("redbus", "travel"),
    ("ixigo", "travel"), ("oyo", "travel"), ("airbnb", "travel"), ("easemytrip", "travel"),
    # transport / fuel
    ("uber", "transport_fuel"), ("ola", "transport_fuel"), ("rapido", "transport_fuel"),
    ("fastag", "transport_fuel"), ("petrol", "transport_fuel"), ("iocl", "transport_fuel"),
    ("hpcl", "transport_fuel"), ("bpcl", "transport_fuel"), ("metro rail", "transport_fuel"),
    ("metro card", "transport_fuel"),
    # bills
    ("jio recharge", "bills_utilities"), ("reliance jio", "bills_utilities"),
    ("airtel", "bills_utilities"), ("vi recharge", "bills_utilities"),
    ("vodafone", "bills_utilities"), ("electricity", "bills_utilities"),
    ("tata power", "bills_utilities"), ("adani electricity", "bills_utilities"),
    ("mahanagar gas", "bills_utilities"), ("broadband", "bills_utilities"),
    ("fibernet", "bills_utilities"), ("tata play", "bills_utilities"),
    # entertainment
    ("bookmyshow", "entertainment"), ("pvr", "entertainment"), ("inox", "entertainment"),
    ("netflix", "entertainment"), ("hotstar", "entertainment"), ("spotify", "entertainment"),
    ("sony liv", "entertainment"), ("zee5", "entertainment"),
    # health
    ("pharmacy", "health"), ("pharmeasy", "health"), ("1mg", "health"),
    ("netmeds", "health"), ("hospital", "health"), ("pathlab", "health"),
    ("clinic", "health"), ("cult.fit", "health"), ("cultfit", "health"),
    ("medical store", "health"), ("medplus", "health"), ("apollo", "health"),
    # education
    ("byjus", "education"), ("unacademy", "education"), ("vedantu", "education"),
    ("physics wallah", "education"), ("upgrad", "education"), ("coursera", "education"),
    ("udemy", "education"), ("school fee", "education"), ("university", "education"),
    ("vidyalaya", "education"), ("institute", "education"),
    # investments
    ("zerodha", "investments"), ("groww", "investments"), ("nextbillion", "investments"),
    ("upstox", "investments"), ("rksv", "investments"), ("angel one", "investments"),
    ("mutual fund", "investments"), ("smallcase", "investments"),
    ("indian clearing corp", "investments"), ("etmoney", "investments"),
    # rent / housing
    ("nobroker", "rent_housing"), ("rentpay", "rent_housing"), ("rent payment", "rent_housing"),
    ("nestaway", "rent_housing"), ("stanza living", "rent_housing"),
    ("maintenance", "rent_housing"), ("mygate", "rent_housing"),
    # emi / loans
    ("bajaj fin", "emi_loans"), ("emi", "emi_loans"), ("loan", "emi_loans"),
    ("hdb financial", "emi_loans"), ("kreditbee", "emi_loans"),
    # subscriptions
    ("google one", "subscriptions"), ("icloud", "subscriptions"),
    ("apple.com/bill", "subscriptions"), ("microsoft 365", "subscriptions"),
    ("adobe", "subscriptions"), ("linkedin premium", "subscriptions"),
    ("prime membership", "subscriptions"), ("github", "subscriptions"),
    # atm
    ("atm wdl", "atm_cash"), ("atm cash", "atm_cash"), ("cash wdl", "atm_cash"),
    ("atw-", "atm_cash"), ("nwd fee", "atm_cash"), ("cash withdrawal", "atm_cash"),
    ("cash deposit", "atm_cash"),
    # fees
    ("sms alert", "fees_charges"), ("amc ", "fees_charges"), ("min bal", "fees_charges"),
    ("annual fee", "fees_charges"), ("late payment fee", "fees_charges"),
    ("consolidated charges", "fees_charges"), ("gst debit", "fees_charges"),
    ("neft charges", "fees_charges"), ("imps charges", "fees_charges"),
    # --- merged-in production keywords + real-data brand aliases (appended last so
    # the specific rules above win on overlap).
    ("anthropic", "subscriptions"), ("claude", "subscriptions"), ("openai", "subscriptions"),
    ("chatgpt", "subscriptions"), ("cursor", "subscriptions"), ("vercel", "subscriptions"),
    ("notion", "subscriptions"), ("figma", "subscriptions"), ("canva", "subscriptions"),
    ("midjourney", "subscriptions"), ("youtube premium", "subscriptions"),
    ("prime video", "subscriptions"), ("google", "subscriptions"),
    ("restaurant", "food_dining"), ("cafe", "food_dining"), ("pizza", "food_dining"),
    ("hotel", "food_dining"), ("bakery", "food_dining"), ("biryani", "food_dining"),
    ("blink comm", "groceries"), ("grocery", "groceries"), ("reliance fresh", "groceries"),
    ("more retail", "groceries"), ("instamart", "groceries"), ("grofers", "groceries"),
    ("general store", "groceries"), ("departmental store", "groceries"),
    ("amzn", "shopping"), ("mall", "shopping"), ("lifestyle", "shopping"),
    ("reliance digital", "shopping"), ("adidas", "shopping"), ("nike", "shopping"),
    ("samsung", "shopping"), ("ikea", "shopping"), ("tira", "shopping"), ("puma", "shopping"),
    ("fuel", "transport_fuel"), ("yatra", "travel"),
    ("cred club", "bills_utilities"), ("cred wallet", "bills_utilities"),
    ("cred_wallet", "bills_utilities"), ("billdesk", "bills_utilities"),
    ("credit card payment", "bills_utilities"), ("card payment", "bills_utilities"),
    ("bill payment", "bills_utilities"), ("credit card bill", "bills_utilities"),
    ("card bill", "bills_utilities"), ("creditcard payment", "bills_utilities"),
    ("jio prepaid", "bills_utilities"), ("jio postpaid", "bills_utilities"),
    ("vi postpaid", "bills_utilities"), ("recharge", "bills_utilities"),
    ("postpaid", "bills_utilities"), ("bsnl", "bills_utilities"), ("wifi", "bills_utilities"),
    ("act fibernet", "bills_utilities"), ("hathway", "bills_utilities"), ("dth", "bills_utilities"),
    ("tatasky", "bills_utilities"), ("dish tv", "bills_utilities"), ("jiofiber", "bills_utilities"),
    ("bescom", "bills_utilities"), ("water bill", "bills_utilities"), ("gas bill", "bills_utilities"),
    ("piped gas", "bills_utilities"), ("adani electric", "bills_utilities"),
    ("bijli", "bills_utilities"), ("utility bill", "bills_utilities"),
    ("disney", "entertainment"),
    ("sip", "investments"), ("iccl", "investments"), ("coin", "investments"),
    ("broking", "investments"), ("kuvera", "investments"), ("indmoney", "investments"),
    ("indstocks", "investments"), ("folio", "investments"), ("investment", "investments"),
    ("etf", "investments"), ("nps", "investments"), ("elss", "investments"),
]

P2P_PATTERNS = re.compile(
    r"upi/p2p|imps/p2a|/p2p/|neft dr-|sent to .* upi|ft - dr -", re.IGNORECASE
)


def classify_merged(text: str) -> str | None:
    """First keyword match wins; else P2P regex / bare-VPA -> transfers_p2p; else None."""
    d = (text or "").lower()
    for kw, cat in KEYWORD_RULES:
        if kw in d:
            return cat
    if P2P_PATTERNS.search(text or ""):
        return "transfers_p2p"
    if "@" in (text or ""):
        return "transfers_p2p"
    return None
