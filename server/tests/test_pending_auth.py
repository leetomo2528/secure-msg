"""Regressions for the unauthenticated and pending-device edges of auth.py.

A pending browser has no socket and no application access; the 401 it polls is
the only channel the relay has for telling it apart from an expired session,
and /password-reset/confirm plus the limiter behind it are what an anonymous
caller can reach without any token at all.
"""

from __future__ import annotations

import atexit
import base64
import hashlib
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from nacl.signing import SigningKey


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
        prefix="securemsg-pending-bootstrap-", suffix=".db", delete=False
    )
    _bootstrap_file.close()
    os.environ["SECUREMSG_DB"] = _bootstrap_file.name
    os.environ.setdefault(
        "SECUREMSG_JWT_SECRET", "pending-test-secret-for-unit-tests-32-bytes"
    )
    atexit.register(_unlink_db, Path(_bootstrap_file.name))

import auth
import rate_limit
import store
from app import app

app.config["TESTING"] = True


def _b64u(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


class AccountFixture(unittest.TestCase):
    """One account with one approved bootstrap device, on its own database."""

    def setUp(self) -> None:
        db_file = tempfile.NamedTemporaryFile(
            prefix="securemsg-pending-test-", suffix=".db", delete=False
        )
        db_file.close()
        self.db_path = Path(db_file.name)
        db_patch = mock.patch.object(store, "DB_PATH", self.db_path)
        db_patch.start()
        self.addCleanup(_unlink_db, self.db_path)
        self.addCleanup(db_patch.stop)
        store.init_schema()

        self.client = app.test_client()
        self.username = "pend_" + hashlib.sha256(
            self._testMethodName.encode()
        ).hexdigest()[:10]
        self.email = f"{self.username}@example.test"
        self.pw_hash = "A" * 43
        registered = self._register_account()
        self.assertEqual(registered.status_code, 200, registered.json)
        self.uid = registered.json["uid"]
        self.approved_key = SigningKey(bytes([11]) * 32)
        self.approved = self._register_device("bootstrap", 11, self.approved_key)
        self.approved_headers = {"Authorization": f"Bearer {self.approved['token']}"}

    def _register_account(self):
        with mock.patch("emailer.send_code") as send_code:
            requested = self.client.post(
                "/api/register/email/request",
                json={
                    "username": self.username,
                    "email": self.email,
                    "pw_hash": self.pw_hash,
                },
            )
            self.assertEqual(requested.status_code, 200, requested.json)
            code = send_code.call_args.args[2]
        return self.client.post(
            "/api/register/email/verify",
            json={"challenge_id": requested.json["challenge_id"], "code": code},
        )

    def _register_device(self, name: str, seed: int, key: SigningKey) -> dict:
        response = self.client.post(
            "/api/device-register",
            json={
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": name,
                "pub_key": _b64u(bytes([(seed + 73) % 256]) * 32),
                "sig_pub": _b64u(bytes(key.verify_key)),
            },
        )
        self.assertEqual(response.status_code, 200, response.json)
        return response.json


class PendingDeviceRevocationTest(AccountFixture):
    def _new_pending(self) -> tuple[dict, dict[str, str]]:
        pending = self._register_device("pending", 29, SigningKey(bytes([29]) * 32))
        self.assertEqual(pending["trust_state"], "pending")
        return pending, {"Authorization": f"Bearer {pending['token']}"}

    def test_a_rejected_browser_is_told_its_device_was_revoked(self):
        """Without the code the browser polls a bare 401 forever.

        It only discards its local keypair on ``device_revoked``; every other
        401 means "your session ended", which must not destroy a key.
        """
        pending, pending_headers = self._new_pending()
        rejected = self.client.post(
            "/api/device-reject-pending",
            headers=self.approved_headers,
            json={
                "sid": pending["sid"],
                "challenge": pending["challenge"],
                "parent_epoch": store.get_user(self.uid)["security_epoch"],
            },
        )
        self.assertEqual(rejected.status_code, 200, rejected.json)

        polled = self.client.get(
            "/api/device-pending-status", headers=pending_headers
        )
        self.assertEqual(polled.status_code, 401, polled.json)
        self.assertEqual(polled.json["code"], auth.DEVICE_REVOKED_CODE)
        # The browser's own cancel button takes the same answer, so it can
        # finish forgetting the device instead of stalling on an opaque 401.
        cancelled = self.client.post(
            "/api/device-pending-revoke", headers=pending_headers
        )
        self.assertEqual(cancelled.status_code, 401, cancelled.json)
        self.assertEqual(cancelled.json["code"], auth.DEVICE_REVOKED_CODE)

    def test_a_self_cancelled_pending_device_is_told_the_same(self):
        pending, pending_headers = self._new_pending()
        cancelled = self.client.post(
            "/api/device-pending-revoke", headers=pending_headers
        )
        self.assertEqual(cancelled.status_code, 200, cancelled.json)

        polled = self.client.get(
            "/api/device-pending-status", headers=pending_headers
        )
        self.assertEqual(polled.status_code, 401, polled.json)
        self.assertEqual(polled.json["code"], auth.DEVICE_REVOKED_CODE)

    def test_a_stale_session_is_not_reported_as_a_revocation(self):
        """The split is the point: an expired or rotated session says nothing
        about trust state, and acting on it destructively would throw away a
        perfectly good keypair."""
        pending, _headers = self._new_pending()
        stale = auth.issue_jwt(
            self.uid,
            pending["sid"],
            store.get_device_by_sid(pending["sid"])["session_version"] + 1,
        )
        polled = self.client.get(
            "/api/device-pending-status",
            headers={"Authorization": f"Bearer {stale}"},
        )
        self.assertEqual(polled.status_code, 401, polled.json)
        self.assertNotIn("code", polled.json)

    def test_another_accounts_token_never_names_a_trust_state(self):
        stranger = self.client.post(
            "/api/device-register",
            json={
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "pending",
                "pub_key": _b64u(bytes([31]) * 32),
                "sig_pub": _b64u(bytes(SigningKey(bytes([31]) * 32).verify_key)),
            },
        )
        self.assertEqual(stranger.status_code, 200, stranger.json)
        forged = auth.issue_jwt(
            self.uid + 1000,
            stranger.json["sid"],
            store.get_device_by_sid(stranger.json["sid"])["session_version"],
        )
        polled = self.client.get(
            "/api/device-pending-status",
            headers={"Authorization": f"Bearer {forged}"},
        )
        self.assertEqual(polled.status_code, 401, polled.json)
        self.assertNotIn("code", polled.json)


class PasswordResetConfirmTest(AccountFixture):
    def _confirm(self, username: str, email: str):
        return self.client.post(
            "/api/password-reset/confirm",
            json={
                "username": username,
                "email": email,
                "challenge_id": "Zm9vYmFyYmF6cXV1eA",
                "code": "000000",
                "pw_hash": "B" * 43,
            },
        )

    def test_confirm_does_not_answer_whether_a_pair_is_an_account(self):
        """/password-reset/request hides account existence behind a detached
        thread and a canned body; confirm used to give the same answer away in
        prose, for free, to anyone with a guess."""
        linked = self._confirm(self.username, self.email)
        unlinked = self._confirm(self.username, "stranger@example.test")
        unknown = self._confirm("nobody_at_all", "stranger@example.test")

        self.assertEqual(linked.status_code, 400, linked.json)
        for other in (unlinked, unknown):
            self.assertEqual(other.status_code, linked.status_code)
            self.assertEqual(other.get_data(), linked.get_data())

    def test_a_valid_reset_still_completes(self):
        with mock.patch("auth._detach", lambda task: task()), mock.patch(
            "emailer.send_code"
        ) as send_code:
            requested = self.client.post(
                "/api/password-reset/request",
                json={"username": self.username, "email": self.email},
            )
            self.assertEqual(requested.status_code, 200, requested.json)
            code = send_code.call_args.args[2]
        confirmed = self.client.post(
            "/api/password-reset/confirm",
            json={
                "username": self.username,
                "email": self.email,
                "challenge_id": requested.json["challenge_id"],
                "code": code,
                "pw_hash": "B" * 43,
            },
        )
        self.assertEqual(confirmed.status_code, 200, confirmed.json)
        login = self.client.post(
            "/api/login", json={"username": self.username, "pw_hash": "B" * 43}
        )
        self.assertEqual(login.status_code, 200, login.json)


class RateLimiterKeyPressureTest(unittest.TestCase):
    """The limiter is the only bound on unauthenticated CPU and outbound mail."""

    def setUp(self) -> None:
        was_testing = app.testing
        app.testing = False
        rate_limit._buckets.clear()
        self.addCleanup(rate_limit._buckets.clear)
        self.addCleanup(setattr, app, "testing", was_testing)

    @staticmethod
    def _spend(ip: str, scope: str, identity: str, limit: int, window: int = 3600):
        with app.test_request_context(environ_base={"REMOTE_ADDR": ip}):
            return rate_limit.check(scope, identity, limit, window)

    def test_an_identity_flood_cannot_flush_the_budgets_it_shares_a_table_with(self):
        """Rotating the caller-supplied identity is free for the caller.

        Every new identity used to insert a bucket and evict the least recently
        used one, so a flood reset the per-IP mail and bcrypt caps of every
        other client — and of the flooder itself, which is what turns
        /register/email/request back into an open mailer.
        """
        flooder, bystander = "198.51.100.10", "198.51.100.20"
        with mock.patch.object(rate_limit, "_MAX_KEYS", 20), mock.patch.object(
            rate_limit, "_MAX_KEYS_PER_IP", 5
        ):
            self.assertIsNone(self._spend(flooder, "mail-ip", "", 1))
            self.assertIsNone(self._spend(bystander, "mail-ip", "", 1))

            denials = [
                self._spend(flooder, "login", f"drone{index:04d}", 20, 60)
                for index in range(200)
            ]
            # The flood also has to meet a limit of its own: past the ceiling
            # the unseen identities share one bucket instead of each getting a
            # fresh budget.
            self.assertTrue(any(retry is not None for retry in denials))

            self.assertIsNotNone(self._spend(flooder, "mail-ip", "", 1))
            self.assertIsNotNone(self._spend(bystander, "mail-ip", "", 1))

    def test_clearing_the_table_gives_a_flooded_ip_its_own_buckets_back(self):
        """Other test modules reset the limiter by clearing the table."""
        flooder = "198.51.100.30"
        with mock.patch.object(rate_limit, "_MAX_KEYS_PER_IP", 5):
            for index in range(50):
                self._spend(flooder, "login", f"drone{index:04d}", 20, 60)
            self.assertIsNotNone(self._spend(flooder, "login", "victim", 20, 60))
            rate_limit._buckets.clear()
            self.assertIsNone(self._spend(flooder, "login", "victim", 20, 60))


if __name__ == "__main__":
    unittest.main()
