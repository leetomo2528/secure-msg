"""Server config. All secrets via env, no hardcoded keys."""

from __future__ import annotations

import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent

# SQLite file lives next to this module.
DB_PATH = Path(os.environ.get("SECUREMSG_DB", BASE_DIR / "securemsg.db"))

# JWT signing secret. MUST be set in production.
JWT_SECRET = os.environ.get("SECUREMSG_JWT_SECRET", "")
JWT_ALG = "HS256"
JWT_TTL_SECONDS = int(os.environ.get("SECUREMSG_JWT_TTL") or 604800)  # 7 days

# Abuse limits. Envelopes contain ciphertext and one wrapped key per device, so
# keep both dimensions bounded before serializing or writing to SQLite.
MAX_HTTP_BODY_BYTES = int(os.environ.get("SECUREMSG_MAX_HTTP_BODY", "2097152"))
MAX_ENVELOPE_BYTES = int(os.environ.get("SECUREMSG_MAX_ENVELOPE", "1572864"))
MAX_DEVICES_PER_USER = int(os.environ.get("SECUREMSG_MAX_DEVICES", "20"))

# Flask-SocketIO's threading mode uses simple-websocket and runs under a
# single gthread Gunicorn worker in production (polling + WebSocket compatible).
ASYNC_MODE = os.environ.get("SECUREMSG_ASYNC_MODE", "threading")

# Allow registered origins only (comma-separated). Use the public hostname in prod.
CORS_ORIGINS = [
    o.strip()
    for o in os.environ.get(
        "SECUREMSG_CORS", "http://localhost:5173,http://127.0.0.1:5173"
    ).split(",")
    if o.strip()
]

# Email verification/password recovery. Keep credentials outside the repo.
# Resend is preferred for hosted deployments; SMTP remains available as a
# fallback for self-hosted installations.
EMAIL_PROVIDER = os.environ.get("SECUREMSG_EMAIL_PROVIDER", "resend").strip().lower()
RESEND_API_KEY = os.environ.get("SECUREMSG_RESEND_API_KEY", "")
RESEND_FROM = os.environ.get("SECUREMSG_RESEND_FROM", "")
RESEND_API_URL = os.environ.get("SECUREMSG_RESEND_API_URL", "https://api.resend.com/emails")
SMTP_HOST = os.environ.get("SECUREMSG_SMTP_HOST", "")
SMTP_PORT = int(os.environ.get("SECUREMSG_SMTP_PORT", "587"))
SMTP_USER = os.environ.get("SECUREMSG_SMTP_USER", "")
SMTP_PASSWORD = os.environ.get("SECUREMSG_SMTP_PASSWORD", "")
SMTP_FROM = os.environ.get("SECUREMSG_SMTP_FROM", SMTP_USER)
SMTP_STARTTLS = os.environ.get("SECUREMSG_SMTP_STARTTLS", "1") != "0"
EMAIL_CODE_TTL_SECONDS = int(os.environ.get("SECUREMSG_EMAIL_CODE_TTL", "600"))

LISTEN_HOST = os.environ.get("SECUREMSG_HOST", "0.0.0.0")
LISTEN_PORT = int(os.environ.get("SECUREMSG_PORT", "5050"))


# Enforce a real JWT secret in non-dev mode.
def enforce_secret() -> None:
    production = os.environ.get("SECUREMSG_ENV") == "production"
    if production and len(JWT_SECRET.encode("utf-8")) < 32:
        raise RuntimeError(
            "SECUREMSG_JWT_SECRET must be at least 32 bytes in production"
        )
    if not 300 <= JWT_TTL_SECONDS <= 365 * 24 * 60 * 60:
        raise RuntimeError("SECUREMSG_JWT_TTL must be between 300 seconds and 365 days")
    if not 64 * 1024 <= MAX_ENVELOPE_BYTES <= 8 * 1024 * 1024:
        raise RuntimeError("SECUREMSG_MAX_ENVELOPE must be between 64KB and 8MB")
    if not MAX_ENVELOPE_BYTES <= MAX_HTTP_BODY_BYTES <= 10 * 1024 * 1024:
        raise RuntimeError(
            "SECUREMSG_MAX_HTTP_BODY must cover the envelope and be at most 10MB"
        )
    if not 1 <= MAX_DEVICES_PER_USER <= 100:
        raise RuntimeError("SECUREMSG_MAX_DEVICES must be between 1 and 100")
    if ASYNC_MODE != "threading":
        raise RuntimeError("SECUREMSG_ASYNC_MODE must be threading for this deployment")
    if production and (
        not CORS_ORIGINS
        or "*" in CORS_ORIGINS
        or any(not origin.startswith("https://") for origin in CORS_ORIGINS)
    ):
        raise RuntimeError(
            "SECUREMSG_CORS must contain explicit HTTPS origins in production"
        )
