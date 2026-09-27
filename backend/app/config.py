from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="ET_")

    database_url: str = "postgresql+asyncpg://expense:expense@localhost:5432/expense"
    dedup_window_hours: int = 36
    dedup_amount_window_hours: int = 12
    default_currency: str = "INR"
    # Approximate, configurable FX rates used to normalize foreign-currency card
    # transactions to an INR-equivalent for aggregation. Bank messages do not
    # include the INR amount for the txn, so this is an honest approximation.
    fx_rates_to_inr: dict[str, float] = {"USD": 86.0, "EUR": 93.0, "GBP": 109.0}
    app_pin_hash: str | None = None
    app_pin_salt: str = ""
    device_key: str = ""
    allowed_email: str = ""
    google_web_client_id: str = ""
    # Path to the Firebase Admin SDK service-account JSON used to send FCM pushes.
    # When the file is absent, push sending degrades gracefully to a no-op (the rest
    # of the app and the test suite run without it), mirroring the Gmail token.json.
    firebase_credentials_path: str = "firebase-service-account.json"
    # Optional LLM narration of insight cards (off by default). When enabled, cards
    # from the deterministic insights engine are rewritten into friendlier prose by a
    # local OpenAI-compatible LLM at `llm_url`; numbers are re-verified and the original
    # card is kept if any number drifts or the LLM is unreachable. `llm_model` must match
    # the id the server was launched with (e.g. `mlx_lm.server --model <id>`).
    insights_narrate: bool = False
    # The per-month "AI Insights" screen (GET /insights/summary). ON by default — it is
    # cached (one generation per month per data change) so it is safe to leave enabled,
    # unlike `insights_narrate` above which rewrites the deterministic cards on every
    # /insights load. When on, the screen still shows a graceful placeholder if no LLM is
    # reachable at `llm_url` (the response carries available=false rather than erroring).
    insights_summary_enabled: bool = True
    llm_url: str = "http://localhost:8081"
    llm_model: str = "mlx-community/Qwen2.5-7B-Instruct-4bit"


settings = Settings()
