from app.config import Settings


def test_settings_have_defaults():
    s = Settings(_env_file=None)
    assert s.dedup_window_hours == 36
    assert s.default_currency == "INR"


def test_settings_fx_rates_to_inr_defaults():
    s = Settings(_env_file=None)
    assert s.fx_rates_to_inr["USD"] == 86.0
    assert s.fx_rates_to_inr["EUR"] == 93.0
    assert s.fx_rates_to_inr["GBP"] == 109.0
