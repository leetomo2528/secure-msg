"""Deployment guards in config.enforce_secret().

create_app() calls this at import, so every rule here is what stands between a
mistyped environment variable and a relay that runs with a guessable JWT
secret, prints verification codes to a log, or accepts plaintext origins. None
of the production branches had ever executed under test.
"""

from __future__ import annotations

import atexit
import contextlib
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


def _unlink_db(path: Path) -> None:
    for suffix in ("", "-wal", "-shm"):
        path.with_name(path.name + suffix).unlink(missing_ok=True)


# Discovery loads this module before test_smoke.py, and store.py binds
# config.DB_PATH by value at import: whichever module imports config first
# decides which file the whole process talks to, and without this the answer
# would be the developer's own securemsg.db. Every module loaded afterwards
# shares the file, so it can only be removed once the process is done with it.
if "config" not in sys.modules:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    _bootstrap_file = tempfile.NamedTemporaryFile(
        prefix="securemsg-config-bootstrap-", suffix=".db", delete=False
    )
    _bootstrap_file.close()
    os.environ["SECUREMSG_DB"] = _bootstrap_file.name
    os.environ.setdefault(
        "SECUREMSG_JWT_SECRET", "config-test-secret-for-unit-tests-32-bytes"
    )
    atexit.register(_unlink_db, Path(_bootstrap_file.name))

import config


class EnforceSecretTest(unittest.TestCase):
    """enforce_secret() reads module attributes, so overrides patch them.

    Reloading config instead would leave store.DB_PATH — bound by value at
    import — pointing at the previous module object's database for the rest of
    this single-process suite.
    """

    PRODUCTION = {
        "JWT_SECRET": "s" * 32,
        "EMAIL_PROVIDER": "resend",
        "CORS_ORIGINS": ["https://msg.example.test"],
    }

    def enforce(self, *, environment: str = "production", **overrides) -> None:
        values = dict(self.PRODUCTION, **overrides)
        with contextlib.ExitStack() as stack:
            stack.enter_context(
                mock.patch.dict(os.environ, {"SECUREMSG_ENV": environment})
            )
            for name, value in values.items():
                stack.enter_context(mock.patch.object(config, name, value))
            config.enforce_secret()

    def test_a_production_deployment_with_valid_settings_starts(self):
        self.enforce()

    def test_production_refuses_a_jwt_secret_under_32_bytes(self):
        with self.assertRaisesRegex(RuntimeError, "at least 32 bytes"):
            self.enforce(JWT_SECRET="short")
        # 32 characters of multi-byte text are fewer than 32 bytes of entropy
        # only if measured in characters; the rule counts encoded bytes.
        self.enforce(JWT_SECRET="비" * 11)

    def test_development_still_boots_without_a_secret(self):
        """app.py generates an ephemeral dev secret when this is empty."""
        self.enforce(
            environment="development",
            JWT_SECRET="",
            EMAIL_PROVIDER="console",
            CORS_ORIGINS=["http://localhost:5173"],
        )

    def test_production_refuses_the_console_email_provider(self):
        """The console provider writes verification codes to a local file."""
        with self.assertRaisesRegex(RuntimeError, "never run in production"):
            self.enforce(EMAIL_PROVIDER="console")

    def test_production_requires_explicit_https_origins(self):
        for origins in ([], ["*"], ["http://msg.example.test"],
                        ["https://msg.example.test", "http://staging.example.test"]):
            with self.subTest(origins=origins):
                with self.assertRaisesRegex(RuntimeError, "HTTPS origins"):
                    self.enforce(CORS_ORIGINS=origins)

    def test_a_session_cap_below_the_token_ttl_is_refused(self):
        """A cap under the TTL is inert: the token outlives the refusal."""
        with self.assertRaisesRegex(RuntimeError, "SECUREMSG_SESSION_MAX_AGE"):
            self.enforce(
                JWT_TTL_SECONDS=604800, SESSION_MAX_AGE_SECONDS=604799
            )
        self.enforce(JWT_TTL_SECONDS=604800, SESSION_MAX_AGE_SECONDS=604800)
        with self.assertRaisesRegex(RuntimeError, "SECUREMSG_SESSION_MAX_AGE"):
            self.enforce(SESSION_MAX_AGE_SECONDS=3 * 365 * 24 * 60 * 60)

    def test_token_ttl_stays_between_five_minutes_and_a_year(self):
        for ttl in (299, 366 * 24 * 60 * 60):
            with self.subTest(ttl=ttl):
                with self.assertRaisesRegex(RuntimeError, "SECUREMSG_JWT_TTL"):
                    self.enforce(
                        JWT_TTL_SECONDS=ttl,
                        SESSION_MAX_AGE_SECONDS=2 * 365 * 24 * 60 * 60,
                    )

    def test_the_body_cap_must_cover_the_envelope_cap(self):
        """A body cap under the envelope cap 413s every message that fills it."""
        with self.assertRaisesRegex(RuntimeError, "SECUREMSG_MAX_HTTP_BODY"):
            self.enforce(
                MAX_ENVELOPE_BYTES=1572864, MAX_HTTP_BODY_BYTES=1048576
            )
        for envelope in (32 * 1024, 9 * 1024 * 1024):
            with self.subTest(envelope=envelope):
                with self.assertRaisesRegex(RuntimeError, "SECUREMSG_MAX_ENVELOPE"):
                    self.enforce(MAX_ENVELOPE_BYTES=envelope)

    def test_remaining_numeric_ranges_are_enforced(self):
        for attribute, value, message in (
            ("MAX_DEVICES_PER_USER", 0, "SECUREMSG_MAX_DEVICES"),
            ("MAX_DEVICES_PER_USER", 101, "SECUREMSG_MAX_DEVICES"),
            ("MAX_BLOCK_RULES", 9, "SECUREMSG_MAX_BLOCK_RULES"),
            ("MAX_BLOCK_RULES", 10_001, "SECUREMSG_MAX_BLOCK_RULES"),
            ("CHALLENGE_RETENTION_SECONDS", 3599, "SECUREMSG_CHALLENGE_RETENTION"),
            ("CHALLENGE_RETENTION_SECONDS", 31 * 24 * 60 * 60, "SECUREMSG_CHALLENGE_RETENTION"),
            ("ASYNC_MODE", "eventlet", "SECUREMSG_ASYNC_MODE"),
        ):
            with self.subTest(attribute=attribute, value=value):
                with self.assertRaisesRegex(RuntimeError, message):
                    self.enforce(**{attribute: value})

    def test_numeric_rules_apply_outside_production_too(self):
        """A dev box that boots on a broken cap hides the misconfiguration."""
        with self.assertRaisesRegex(RuntimeError, "SECUREMSG_MAX_DEVICES"):
            self.enforce(environment="development", MAX_DEVICES_PER_USER=0)


if __name__ == "__main__":
    unittest.main()
