from app.enrichment.rails import (
    BANK_TRANSFER, CREDIT_CARD, DEBIT_CARD, OTHER, UPI, classify_rail,
)


def test_credit_card_by_account_type():
    assert classify_rail(account_type="card", merchant="SWIGGY") == CREDIT_CARD


def test_upi_by_vpa_or_token():
    assert classify_rail(account_type="bank", merchant="hotstaronline@ybl") == UPI
    assert classify_rail(account_type="bank", merchant="UPI 320648725455") == UPI


def test_bank_transfer_by_neft():
    assert classify_rail(account_type="bank", merchant="NEFT") == BANK_TRANSFER
    assert classify_rail(account_type="bank", merchant="IMPS to X") == BANK_TRANSFER


def test_debit_card_by_atm():
    assert classify_rail(account_type="bank", merchant="Other Bank ATM") == DEBIT_CARD


def test_bank_debit_to_person_defaults_to_upi():
    # Cleaned payee name on a bank account (UPI token stripped) → UPI in India.
    assert classify_rail(account_type="bank", merchant="SHAKUNTLA KHANDELW") == UPI


def test_other_fallback_for_non_bank_non_card():
    assert classify_rail(account_type="cash", merchant="whatever") == OTHER
