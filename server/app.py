"""Flask application factory + SocketIO bootstrap.

Run:
    python app.py                # dev (single worker)
    gunicorn -k gthread -w 1 --threads 100 app:app   # prod

ENV:
    SECUREMSG_ENV=production
    SECUREMSG_JWT_SECRET=<32+ random bytes>
    SECUREMSG_CORS=https://msg.yourdomain.com
    SECUREMSG_PORT=5000
"""

from __future__ import annotations

import logging
import secrets

import config
import store
from auth import bp as auth_bp
from blocklist import bp as blocklist_bp
from conversations import bp as conv_bp
from flask import Flask
from flask_socketio import SocketIO
from sockets import attach_socketio

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
log = logging.getLogger("securemsg.app")


def create_app() -> tuple[Flask, SocketIO]:
    config.enforce_secret()
    # In dev, allow JWT_SECRET to be unset and generate one in-memory
    # (tokens won't survive restart, but that's fine for local dev).
    global _dev_secret
    if not config.JWT_SECRET:
        _dev_secret = secrets.token_hex(32)
        config.JWT_SECRET = _dev_secret
        log.warning(
            "SECUREMSG_JWT_SECRET unset — using ephemeral dev secret. "
            "DO NOT use in production."
        )

    app = Flask(__name__)
    app.config["SECRET_KEY"] = config.JWT_SECRET
    app.config["MAX_CONTENT_LENGTH"] = config.MAX_HTTP_BODY_BYTES

    socketio = SocketIO(
        app,
        async_mode=config.ASYNC_MODE,
        cors_allowed_origins=config.CORS_ORIGINS,
        cors_credentials=True,
        ping_interval=20,
        ping_timeout=25,
        max_http_buffer_size=config.MAX_HTTP_BODY_BYTES,
    )

    store.init_schema()
    app.register_blueprint(auth_bp)
    app.register_blueprint(conv_bp)
    app.register_blueprint(blocklist_bp)
    attach_socketio(app, socketio)

    @app.get("/health")
    def health() -> tuple:
        try:
            with store.conn_ctx() as connection:
                connection.execute("SELECT 1").fetchone()
            return {"ok": True}
        except Exception:
            log.exception("health check database failure")
            return {"ok": False, "error": "database unavailable"}, 503

    @app.errorhandler(413)
    def body_too_large(_error):
        return {"ok": False, "error": "request body too large"}, 413

    @app.errorhandler(404)
    def not_found(_error):
        return {"ok": False, "error": "not found"}, 404

    @app.errorhandler(500)
    def internal_error(error):
        log.error(
            "unhandled request error",
            exc_info=(type(error), error, error.__traceback__),
        )
        return {"ok": False, "error": "internal server error"}, 500

    return app, socketio


app, socketio = create_app()


if __name__ == "__main__":
    socketio.run(
        app,
        host=config.LISTEN_HOST,
        port=config.LISTEN_PORT,
        debug=False,
        allow_unsafe_werkzeug=True,
    )
