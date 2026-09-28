"""Socket connection diagnostics, including legacy disconnect callbacks.

This file sorts after test_smoke.py. Each test patches store.DB_PATH to its own
database because smoke tests remove their bootstrap database on module teardown.
"""

from __future__ import annotations

import atexit
import base64
import hashlib
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from nacl.signing import SigningKey


def _unlink_db(path: Path) -> None:
    for suffix in ("", "-wal", "-shm"):
        path.with_name(path.name + suffix).unlink(missing_ok=True)


# Direct execution must also avoid opening the developer's database when app.py
# creates its module-level application.
if "config" not in sys.modules:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    _bootstrap_file = tempfile.NamedTemporaryFile(
        prefix="securemsg-diag-bootstrap-", suffix=".db", delete=False
    )
    _bootstrap_file.close()
    os.environ["SECUREMSG_DB"] = _bootstrap_file.name
    os.environ.setdefault(
        "SECUREMSG_JWT_SECRET", "diag-test-secret-for-unit-tests-32-bytes"
    )
    atexit.register(_unlink_db, Path(_bootstrap_file.name))

import store
from auth import verify_jwt
from app import app, socketio
from sockets import connected_at


app.config["TESTING"] = True


def _b64u(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


class SocketDiagnosticsTest(unittest.TestCase):
    def setUp(self) -> None:
        db_file = tempfile.NamedTemporaryFile(
            prefix="securemsg-diag-test-", suffix=".db", delete=False
        )
        db_file.close()
        self.db_path = Path(db_file.name)
        db_patch = mock.patch.object(store, "DB_PATH", self.db_path)
        db_patch.start()
        self.addCleanup(_unlink_db, self.db_path)
        self.addCleanup(db_patch.stop)
        store.init_schema()

        self.client = app.test_client()
        self.username = "diag_" + hashlib.sha256(
            self._testMethodName.encode()
        ).hexdigest()[:10]
        self.pw_hash = "A" * 43
        with mock.patch("emailer.send_code") as send_code:
            requested = self.client.post(
                "/api/register/email/request",
                json={
                    "username": self.username,
                    "email": f"{self.username}@example.test",
                    "pw_hash": self.pw_hash,
                },
            )
            self.assertEqual(requested.status_code, 200, requested.json)
            code = send_code.call_args.args[2]
        registered = self.client.post(
            "/api/register/email/verify",
            json={"challenge_id": requested.json["challenge_id"], "code": code},
        )
        self.assertEqual(registered.status_code, 200, registered.json)
        key = SigningKey(bytes([7]) * 32)
        device = self.client.post(
            "/api/device-register",
            json={
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "diag-test",
                "pub_key": _b64u(bytes([17]) * 32),
                "sig_pub": _b64u(bytes(key.verify_key)),
            },
        )
        self.assertEqual(device.status_code, 200, device.json)
        self.uid = device.json["uid"]
        self.sid = device.json["sid"]
        self.token = device.json["token"]

    def _connect(self, **auth_extra):
        client = socketio.test_client(
            app, auth={"token": self.token, **auth_extra}
        )
        self.assertTrue(client.is_connected())
        self.addCleanup(self._close_socket, client)
        return client

    @staticmethod
    def _close_socket(client) -> None:
        if client.is_connected():
            client.disconnect()

    @staticmethod
    def _socket_id(client) -> str:
        return socketio.server.manager.sid_from_eio_sid(client.eio_sid, "/")

    def _messages(self, captured) -> list[str]:
        messages = [record.getMessage() for record in captured.records]
        self.assertTrue(messages)
        for message in messages:
            self.assertNotIn(self.token, message)
        return messages

    def test_connect_logs_only_valid_diag_and_socket_id(self):
        cases = (
            ("p=123;up=45;n=2;x=ping_timeout:9;v=42;vis=1", True),
            ("A" * 120, True),
            ("bad@chars", False),
            ("B" * 121, False),
            (self.token, False),
            (123, False),
            ("", False),
        )
        for diag, accepted in cases:
            with self.subTest(diag=diag):
                with self.assertLogs("securemsg.sockets", level="INFO") as captured:
                    client = self._connect(diag=diag)
                sock = self._socket_id(client)
                message = self._messages(captured)[0]
                self.assertIn(f"connect uid={self.uid} sid={self.sid} sock={sock}", message)
                if accepted:
                    self.assertIn(f" diag={diag}", message)
                else:
                    self.assertNotIn(" diag=", message)
                client.disconnect()

    def test_diag_never_echoes_the_auth_token_even_when_short(self):
        short_token = "TOKENONLYFORTEST"
        decoded = verify_jwt(self.token)
        self.assertIsNotNone(decoded)
        with mock.patch("sockets.verify_jwt", return_value=decoded):
            with self.assertLogs("securemsg.sockets", level="INFO") as captured:
                client = socketio.test_client(
                    app, auth={"token": short_token, "diag": "p=" + short_token}
                )
        self.assertTrue(client.is_connected())
        self.addCleanup(self._close_socket, client)
        message = self._messages(captured)[0]
        self.assertNotIn(short_token, message)
        self.assertNotIn(" diag=", message)

    def test_connected_at_is_removed_and_refusal_never_adds_one(self):
        client = self._connect()
        sock = self._socket_id(client)
        self.assertIsInstance(connected_at[sock], float)
        client.disconnect()
        self.assertNotIn(sock, connected_at)

        before = set(connected_at)
        refused = socketio.test_client(
            app, auth={"token": "not-a-token", "diag": "p=123"}
        )
        self.assertFalse(refused.is_connected())
        self.assertEqual(set(connected_at), before)

    def test_disconnect_logs_reason_duration_and_cleans_timestamp(self):
        client = self._connect()
        sock = self._socket_id(client)
        started = connected_at[sock]
        with mock.patch("sockets.time.monotonic", return_value=started + 12.34):
            with self.assertLogs("securemsg.sockets", level="INFO") as captured:
                socketio.server.handlers["/"]["disconnect"](sock, "ping timeout")
        message = self._messages(captured)[0]
        self.assertIn(
            f"disconnect uid={self.uid} sid={self.sid} sock={sock} "
            "reason=ping timeout dur=12.3",
            message,
        )
        self.assertNotIn(sock, connected_at)

    def test_disconnect_accepts_no_reason_and_unknown_socket(self):
        client = self._connect()
        sock = self._socket_id(client)
        handler = socketio.server.handlers["/"]["disconnect"]
        with self.assertLogs("securemsg.sockets", level="INFO") as captured:
            handler(sock)
            handler(sock)
        messages = self._messages(captured)
        self.assertIn(f"sock={sock} reason=- dur=", messages[0])
        self.assertIn(f"sock={sock} reason=- dur=-", messages[1])
        self.assertNotIn(sock, connected_at)

    def test_disconnect_stringifies_and_bounds_reason(self):
        class LongReason:
            def __str__(self) -> str:
                return "ping\ntimeout" + "x" * 130

        client = self._connect()
        sock = self._socket_id(client)
        with self.assertLogs("securemsg.sockets", level="INFO") as captured:
            socketio.server.handlers["/"]["disconnect"](sock, LongReason())
        message = self._messages(captured)[0]
        reason = message.split("reason=", 1)[1].split(" dur=", 1)[0]
        self.assertEqual(len(reason), 120)
        self.assertTrue(reason.startswith("ping timeout"))
        self.assertNotIn("\n", reason)

    def _assert_server_initiated_disconnect(self, cause: str, captured, sock: str):
        messages = self._messages(captured)
        warning = next(
            i for i, message in enumerate(messages)
            if f"server-initiated disconnect cause={cause}" in message
        )
        disconnected = next(
            i for i, message in enumerate(messages)
            if message.startswith("disconnect uid=")
        )
        self.assertLess(warning, disconnected)
        self.assertIn(f"uid={self.uid} sid={self.sid} sock={sock}", messages[warning])
        self.assertIn(f"sock={sock} reason=server disconnect", messages[disconnected])
        self.assertNotIn(sock, connected_at)

    def test_expired_socket_warns_before_disconnect(self):
        client = self._connect()
        sock = self._socket_id(client)
        with mock.patch("sockets.time.time", return_value=time.time() + 10 * 365 * 86400):
            with self.assertLogs("securemsg.sockets", level="INFO") as captured:
                client.emit("message_send", {}, callback=True)
        self.assertFalse(client.is_connected())
        self._assert_server_initiated_disconnect("expired", captured, sock)

    def test_revoked_socket_warns_before_disconnect(self):
        client = self._connect()
        sock = self._socket_id(client)
        with mock.patch("sockets.store.get_device_by_sid", return_value=None):
            with self.assertLogs("securemsg.sockets", level="INFO") as captured:
                client.emit("message_send", {}, callback=True)
        self.assertFalse(client.is_connected())
        self._assert_server_initiated_disconnect("revoked", captured, sock)


if __name__ == "__main__":
    unittest.main()
