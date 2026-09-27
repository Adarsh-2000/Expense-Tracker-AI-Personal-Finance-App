from datetime import datetime

import pytest

from app.parsing.registry import default_registry

DT = datetime(2025, 4, 10, 9, 27, 12)

REJECT_SAMPLES = [
    (
        "AX-AXISBK",
        "712860 is SECRET OTP for txn of INR 936.61 on Axis Bank card XX9966 "
        "at AMAZON on 10-04-25 09:27:12. OTP valid for 5 mins. Please do not "
        "share this OTP",
    ),
    (
        "IDFCFB",
        "<#>298300 is the OTP to complete your payment to TIRUPATI JEWELLERS. "
        "Please do not share with anyone IDFC FIRST Bank",
    ),
    (
        "AX-AXISBK",
        "E-statement of your Axis Bank Credit Card no. XX9966 has been "
        "generated. Total Amount Due INR  Dr. 37666.3, Minimum Amount Due "
        "INR Dr. 754, Due date 25-04-25",
    ),
    (
        "AX-AXISBK",
        "You have initiated the process of adding Credit Card No. XXXXXX78 to "
        "your UPI app - Axis Bank",
    ),
    (
        "IDFCFB",
        "You have successfully set your UPI PIN on BHIM UPI. Thank you for "
        "banking with IDFC FIRST Bank",
    ),
    (
        "IDFCFB",
        "Dear Customer, Congratulations! Transaction of Rs.6911.00 on your IDFC "
        "FIRST Bank Credit Card ending with XX0684 has been successfully "
        "converted into EMI. Thank you for banking with us.",
    ),
    (
        "IDFCFB",
        "Credit Card Closure Notification. You have crossed 80% limit on your "
        "IDFC First Bank Credit Card. Please pay your dues.",
    ),
    (
        "AX-AXISBK",
        "A transaction attempt for USD 150 on your Axis Bank Credit Card "
        "no. XX9966 at CLAUDE AI on 02-06-26 has been declined. - Axis Bank",
    ),
    # Bill PAYMENT confirmation towards the credit card -> noise, not a spend.
    (
        "alerts@axisbank.com",
        "Payment of INR 37666.30 has been received towards your Axis Bank "
        "Credit Card no. XX9966. Thank you.",
    ),
    (
        "BOBTXN",
        "Payment received towards your Bank of Baroda Credit Card. - BOB",
    ),
    # Video KYC / details-missing nudge.
    (
        "AX-AXISBK",
        "Dear Customer, your video KYC is pending. Some details are missing. "
        "Please complete it to continue using your account. - Axis Bank",
    ),
    # Reward points statement.
    (
        "alerts@axisbank.com",
        "You have earned Reward Points on your Axis Bank Credit Card XX9966 "
        "this month. View your statement for details.",
    ),
    # Scheduled maintenance downtime.
    (
        "AX-AXISBK",
        "Dear Customer, there will be a scheduled maintenance downtime on "
        "03-06-25 from 1AM to 3AM. - Axis Bank",
    ),
    # Card being linked to your UPI app.
    (
        "AX-AXISBK",
        "Your Axis Bank Credit Card no. XXXXXX78 is being linked to your UPI "
        "app. - Axis Bank",
    ),
    # Invalid transaction password.
    (
        "IDFCFB",
        "You entered an invalid transaction password. Please try again. "
        "IDFC FIRST Bank",
    ),
    # E-statement generated (no spend).
    (
        "alerts@axisbank.com",
        "Statement for your Axis Bank Credit Card XX9966 is now available. "
        "Total Amount Due INR 12345.",
    ),
]


@pytest.mark.parametrize("sender,body", REJECT_SAMPLES)
def test_rejection_returns_none(sender, body):
    reg = default_registry()
    assert reg.parse(sender, body, DT) is None
