"""Firebase Cloud Messaging (FCM) push sender.

Initialises the Firebase Admin SDK from a service-account JSON and sends push
notifications to registered device tokens. If the credentials file is absent
(e.g. local dev before setup, or the test suite), every call degrades to a no-op
so the rest of the app keeps working — mirroring the Gmail token.json pattern.
"""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass

from app.config import settings

logger = logging.getLogger("push")

_app = None          # cached firebase_admin.App
_init_attempted = False


def _get_app():
    """Lazily initialise (once) the Firebase Admin app from the service-account
    file. Returns None when credentials are unavailable or invalid."""
    global _app, _init_attempted
    if _app is not None:
        return _app
    if _init_attempted:
        return None
    _init_attempted = True
    path = settings.firebase_credentials_path
    if not path or not os.path.exists(path):
        logger.info("FCM disabled: no service-account file at %s", path)
        return None
    try:
        import firebase_admin
        from firebase_admin import credentials
        cred = credentials.Certificate(path)
        _app = firebase_admin.initialize_app(cred, name="expense-tracker-push")
        logger.info("FCM initialised from %s", path)
    except Exception:
        logger.exception("FCM init failed")
        _app = None
    return _app


def is_enabled() -> bool:
    return _get_app() is not None


@dataclass
class PushResult:
    sent: int
    failed: int
    invalid_tokens: list[str]   # tokens FCM rejected (unregistered/invalid) — prune these


def send_push(tokens: list[str], *, title: str, body: str,
              data: dict | None = None) -> PushResult:
    """Send one notification to many device tokens. No-op (sent=0) when FCM is
    disabled. Returns which tokens FCM rejected so the caller can prune them."""
    app = _get_app()
    if app is None or not tokens:
        return PushResult(sent=0, failed=0, invalid_tokens=[])

    from firebase_admin import messaging

    # Data values must be strings for FCM. Always include title/body so the client
    # can render even from the data payload (background handler).
    payload = {k: str(v) for k, v in (data or {}).items()}
    payload.setdefault("title", title)
    payload.setdefault("body", body)

    sent = failed = 0
    invalid: list[str] = []
    for token in tokens:
        message = messaging.Message(
            token=token,
            notification=messaging.Notification(title=title, body=body),
            data=payload,
            android=messaging.AndroidConfig(priority="high"),
        )
        try:
            messaging.send(message, app=app)
            sent += 1
        except Exception as exc:  # noqa: BLE001 — FCM raises several typed errors
            failed += 1
            name = type(exc).__name__
            if name in ("UnregisteredError", "SenderIdMismatchError", "InvalidArgumentError"):
                invalid.append(token)
            else:
                logger.warning("FCM send failed for a token: %s", name)
    return PushResult(sent=sent, failed=failed, invalid_tokens=invalid)


async def dispatch_new_pushes(session) -> dict:
    """Push every NEW notification (any of the ~18 event types from
    build_notifications) to all registered devices, exactly once per stable id.

    Server-side dedup via PushedNotification means a notification is pushed once,
    not on every poll/scan. Invalid tokens reported by FCM are pruned. Safe no-op
    when FCM is disabled or no devices are registered."""
    from app.repositories import DeviceTokenRepository, PushedNotificationRepository
    from app.services.notifications import build_notifications

    items = await build_notifications(session)
    if not items:
        return {"pushed": 0, "devices": 0}

    pushed_repo = PushedNotificationRepository(session)
    fresh_ids = set(await pushed_repo.filter_unpushed([i["id"] for i in items]))
    fresh = [i for i in items if i["id"] in fresh_ids]
    if not fresh:
        return {"pushed": 0, "devices": 0}

    tokens = await DeviceTokenRepository(session).list_tokens()
    # Even with no devices yet, mark them pushed so we don't backlog-blast later when
    # a device first registers — only genuinely new events should alert.
    if tokens and is_enabled():
        dev_repo = DeviceTokenRepository(session)
        for item in fresh:
            result = send_push(
                tokens, title=item["title"], body=item["body"],
                data={"id": item["id"], "type": item.get("type", ""),
                      "route": item.get("route", ""), "severity": item.get("severity", "info"),
                      "anchor": str(item.get("anchor") or "")})
            for bad in result.invalid_tokens:
                await dev_repo.delete(bad)

    await pushed_repo.mark_pushed([i["id"] for i in fresh])
    return {"pushed": len(fresh), "devices": len(tokens)}
