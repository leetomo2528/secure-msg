import hashlib
import base64
import os
import sqlite3
import sys
import tempfile
import threading
import unittest
from contextlib import closing
from pathlib import Path
from unittest import mock

from nacl.signing import SigningKey

with tempfile.NamedTemporaryFile(
    prefix="securemsg-test-",
    suffix=".db",
    delete=False,
) as _db_file:
    _db_path = Path(_db_file.name)
os.environ["SECUREMSG_DB"] = str(_db_path)
os.environ["SECUREMSG_JWT_SECRET"] = "test-secret-for-unit-tests-32-bytes-minimum"
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import app, socketio
from auth import approval_statement, revoke_statement, device_login_statement
import store

app.config["TESTING"] = True


def tearDownModule():
    for suffix in ("", "-wal", "-shm"):
        _db_path.with_name(_db_path.name + suffix).unlink(missing_ok=True)


class ServerSmokeTest(unittest.TestCase):
    def setUp(self):
        self.client = app.test_client()
        self.pw_hash = "A" * 43
        self.signing_key = SigningKey(bytes([7]) * 32)
        self.sig_pub = base64.urlsafe_b64encode(
            bytes(self.signing_key.verify_key)
        ).decode("ascii").rstrip("=")
        self.device_signing_keys = {}
        method_id = hashlib.sha256(self._testMethodName.encode()).hexdigest()[:12]
        self.username = "alice_" + method_id
        register = self.client.post(
            "/api/register",
            json={"username": self.username, "pw_hash": self.pw_hash},
        )
        self.assertEqual(register.status_code, 200, register.json)
        device = self.client.post(
            "/api/device-register",
            json={
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "test",
                "pub_key": "A" * 43,
                "sig_pub": self.sig_pub,
            },
        )
        self.assertEqual(device.status_code, 200, device.json)
        self.token = device.json["token"]
        self.sid = device.json["sid"]
        self.uid = device.json["uid"]
        self.device_signing_keys[self.sid] = self.signing_key

    @property
    def headers(self):
        return {"Authorization": f"Bearer {self.token}"}

    def create_conversation(self, name="+821012345678"):
        created = self.client.post(
            "/api/conversation",
            headers=self.headers,
            json={"members": [self.username], "name": name},
        )
        self.assertEqual(created.status_code, 200, created.json)
        return created.json

    def register_and_approve_device(self, payload):
        """Register another device and cross-sign it with the bootstrap device."""
        payload = dict(payload)
        seed = hashlib.sha256(
            f"{self._testMethodName}:{payload['device_name']}".encode()
        ).digest()[:32]
        device_signing_key = SigningKey(seed)
        payload["sig_pub"] = base64.urlsafe_b64encode(
            bytes(device_signing_key.verify_key)
        ).decode("ascii").rstrip("=")
        response = self.client.post("/api/device-register", json=payload)
        self.assertEqual(response.status_code, 200, response.json)
        self.device_signing_keys[response.json["sid"]] = device_signing_key
        if response.json.get("trust_state") == "pending":
            approved = self.client.post(
                "/api/device-approve",
                headers=self.headers,
                json=self.approval_payload(response),
            )
            self.assertEqual(approved.status_code, 200, approved.json)
        return response

    def test_email_registration_and_password_reset_codes(self):
        username = "mail_" + self._testMethodName[-8:]
        email = f"{username}@example.test"
        with mock.patch("emailer.send_code") as send_code:
            requested = self.client.post(
                "/api/register/email/request",
                json={"username": username, "email": email, "pw_hash": self.pw_hash},
            )
            self.assertEqual(requested.status_code, 200, requested.json)
            self.assertEqual(send_code.call_args.args[0], email)
            code = send_code.call_args.args[2]
            verified = self.client.post(
                "/api/register/email/verify",
                json={"challenge_id": requested.json["challenge_id"], "code": code},
            )
            self.assertEqual(verified.status_code, 200, verified.json)
            self.assertEqual(verified.json["username"], username)
            self.assertEqual(store.get_user_by_email(email)["email_verified_at"] > 0, True)

            reset_requested = self.client.post(
                "/api/password-reset/request",
                json={"username": username, "email": email},
            )
            self.assertEqual(reset_requested.status_code, 200, reset_requested.json)
            reset_code = send_code.call_args.args[2]
            reset = self.client.post(
                "/api/password-reset/confirm",
                json={
                    "username": username,
                    "email": email,
                    "challenge_id": reset_requested.json["challenge_id"],
                    "code": reset_code,
                    "pw_hash": "B" * 43,
                },
            )
            self.assertEqual(reset.status_code, 200, reset.json)

    def test_password_reset_revokes_every_device_session(self):
        """A completed password reset must immediately invalidate every device.

        Covers the recovery-hardening invariant end to end: all device JWTs
        stop authenticating REST calls and live Socket.IO connects are refused
        with the structured auth_rejected code (session_version is rotated
        for the whole account, not just one device).
        """
        second = self.register_and_approve_device(
            {
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "reset-second-device",
                "pub_key": "B" * 43,
            }
        )
        second_token = second.json["token"]
        second_headers = {"Authorization": f"Bearer {second_token}"}
        email = f"{self.username}@example.test"
        # setUp registers without an email; attach a verified one directly so
        # password-reset/request can issue a real challenge.
        with store.conn_ctx() as c:
            c.execute(
                "UPDATE users SET email = ?, email_verified_at = 1 WHERE id = ?",
                (email, self.uid),
            )
        live = socketio.test_client(app, auth={"token": self.token})
        self.assertTrue(live.is_connected())
        self.assertEqual(self.client.get("/api/devices", headers=self.headers).status_code, 200)
        self.assertEqual(self.client.get("/api/devices", headers=second_headers).status_code, 200)

        with mock.patch("emailer.send_code") as send_code:
            requested = self.client.post(
                "/api/password-reset/request",
                json={"username": self.username, "email": email},
            )
            self.assertEqual(requested.status_code, 200, requested.json)
            reset = self.client.post(
                "/api/password-reset/confirm",
                json={
                    "username": self.username,
                    "email": email,
                    "challenge_id": requested.json["challenge_id"],
                    "code": send_code.call_args.args[2],
                    "pw_hash": "C" * 43,
                },
            )
            self.assertEqual(reset.status_code, 200, reset.json)

        # Old password no longer works, both device tokens are dead for REST…
        stale_login = self.client.post(
            "/api/login",
            json={"username": self.username, "pw_hash": self.pw_hash},
        )
        self.assertEqual(stale_login.status_code, 401, stale_login.json)
        self.assertEqual(self.client.get("/api/devices", headers=self.headers).status_code, 401)
        self.assertEqual(self.client.get("/api/devices", headers=second_headers).status_code, 401)
        # …and new socket connections with them are refused outright.
        refused = socketio.test_client(app, auth={"token": self.token})
        self.assertFalse(refused.is_connected())
        refused_second = socketio.test_client(app, auth={"token": second_token})
        self.assertFalse(refused_second.is_connected())

    def test_resend_email_provider_posts_server_side_api_request(self):
        import emailer
        from urllib.request import Request

        original = {
            "provider": emailer.config.EMAIL_PROVIDER,
            "key": emailer.config.RESEND_API_KEY,
            "from": emailer.config.RESEND_FROM,
        }
        try:
            emailer.config.EMAIL_PROVIDER = "resend"
            emailer.config.RESEND_API_KEY = "re_test"
            emailer.config.RESEND_FROM = "SecureMsg <no-reply@example.test>"
            with mock.patch("emailer.urlopen") as opener:
                response = mock.MagicMock(status=200)
                response.__enter__.return_value = response
                opener.return_value = response
                emailer.send_code("user@example.test", "subject", "123456", "가입 인증")
                request = opener.call_args.args[0]
                self.assertIsInstance(request, Request)
                self.assertEqual(request.get_header("Authorization"), "Bearer re_test")
                self.assertIn(b"123456", request.data)
        finally:
            emailer.config.EMAIL_PROVIDER = original["provider"]
            emailer.config.RESEND_API_KEY = original["key"]
            emailer.config.RESEND_FROM = original["from"]

    def approval_payload(self, registration):
        subject = store.get_device_by_sid(registration.json["sid"])
        statement = approval_statement(
            self.uid, subject, registration.json["security_epoch"]
        )
        signature = base64.urlsafe_b64encode(
            bytes(self.signing_key.sign(statement.encode("utf-8")).signature)
        ).decode("ascii").rstrip("=")
        return {
            "subject_sid": registration.json["sid"],
            "parent_epoch": registration.json["security_epoch"],
            "signature": signature,
        }

    def device_login(self, sid=None, signing_key=None, **final_overrides):
        sid = sid or self.sid
        signing_key = signing_key or self.device_signing_keys[sid]
        common = {"username": self.username, "pw_hash": self.pw_hash, "sid": sid}
        challenge = self.client.post("/api/device-login", json=common)
        self.assertEqual(challenge.status_code, 200, challenge.json)
        statement = device_login_statement(
            challenge.json["uid"], sid, challenge.json["challenge_id"],
            challenge.json["challenge"], challenge.json["session_version"],
        )
        proof = base64.urlsafe_b64encode(
            signing_key.sign(statement.encode()).signature
        ).decode().rstrip("=")
        return self.client.post("/api/device-login", json={
            **common, "challenge_id": challenge.json["challenge_id"],
            "challenge": challenge.json["challenge"], "proof": proof,
            **final_overrides,
        })

    def revoke_payload(self, subject_sid, actor_sid):
        subject = store.get_device_by_sid(subject_sid)
        parent_epoch = store.get_user(self.uid)["security_epoch"]
        statement = revoke_statement(self.uid, subject, actor_sid, parent_epoch)
        signature = base64.urlsafe_b64encode(
            bytes(
                self.device_signing_keys[actor_sid]
                .sign(statement.encode("utf-8"))
                .signature
            )
        ).decode("ascii").rstrip("=")
        return {
            "sid": subject_sid,
            "parent_epoch": parent_epoch,
            "reason": "user_revoked",
            "signature": signature,
        }

    def test_sms_conversation_name_is_returned(self):
        self.create_conversation()
        listed = self.client.get("/api/conversations", headers=self.headers)
        self.assertEqual(listed.status_code, 200, listed.json)
        self.assertEqual(listed.json["conversations"][0]["name"], "+821012345678")
        self.assertEqual(
            listed.json["conversations"][0]["synced_contact_name"], ""
        )

    def test_existing_database_migrates_synced_contact_name(self):
        import store

        with tempfile.NamedTemporaryFile(
            prefix="securemsg-legacy-contact-", suffix=".db", delete=False
        ) as legacy_file:
            legacy_path = Path(legacy_file.name)
        try:
            # sqlite3.Connection's context manager commits/rolls back but does
            # not close the handle; closing() prevents Python 3.13+ warnings.
            with closing(sqlite3.connect(legacy_path)) as connection:
                connection.execute(
                    "CREATE TABLE conversations ("
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    "cid TEXT NOT NULL UNIQUE, "
                    "name TEXT NOT NULL DEFAULT '', "
                    "created_at INTEGER NOT NULL)"
                )
                connection.execute(
                    "INSERT INTO conversations(cid, name, created_at) VALUES (?, ?, ?)",
                    ("legacy-contact", "+821012345678", 1),
                )
                connection.commit()
            with mock.patch.object(store, "DB_PATH", legacy_path):
                store.init_schema()
                with store.conn_ctx() as connection:
                    columns = {
                        row[1]
                        for row in connection.execute(
                            "PRAGMA table_info(conversations)"
                        ).fetchall()
                    }
                    row = connection.execute(
                        "SELECT name, synced_contact_name FROM conversations "
                        "WHERE cid = 'legacy-contact'"
                    ).fetchone()
            self.assertIn("synced_contact_name", columns)
            self.assertEqual(row["name"], "+821012345678")
            self.assertEqual(row["synced_contact_name"], "")
        finally:
            for suffix in ("", "-wal", "-shm"):
                legacy_path.with_name(legacy_path.name + suffix).unlink(missing_ok=True)

    def test_sms_conversation_creation_is_idempotent(self):
        first = self.create_conversation("+821099988877")
        second = self.create_conversation("+821099988877")
        self.assertEqual(second["cid"], first["cid"])
        self.assertFalse(second["created"])
        listed = self.client.get("/api/conversations", headers=self.headers)
        matches = [
            row
            for row in listed.json["conversations"]
            if row["name"] == "+821099988877"
        ]
        self.assertEqual(len(matches), 1)

    def test_message_sequence_is_monotonic(self):
        import store

        conv_id = store.create_conversation("seq-test", "+821011111111")
        user = store.get_user_by_name(self.username)
        store.add_member(conv_id, user["id"])
        _, first, _ = store.insert_message(conv_id, user["id"], self.sid, "{}")
        _, second, _ = store.insert_message(conv_id, user["id"], self.sid, "{}")
        self.assertEqual((first, second), (1, 2))

    def test_delivery_cursor_never_regresses(self):
        import store

        created = self.create_conversation()
        device = store.get_device_by_sid(self.sid)
        store.set_cursor(device["id"], created["conv_id"], 9)
        store.set_cursor(device["id"], created["conv_id"], 3)
        self.assertEqual(store.get_cursor(device["id"], created["conv_id"]), 9)

    def test_revoked_device_token_is_rejected(self):
        second = self.register_and_approve_device(
            {
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "revocation-authorizer",
                "pub_key": "C" * 43,
                "sig_pub": "D" * 43,
            }
        )
        revoked = self.client.post(
            "/api/device-revoke",
            headers={"Authorization": f"Bearer {second.json['token']}"},
            json=self.revoke_payload(self.sid, second.json["sid"]),
        )
        self.assertEqual(revoked.status_code, 200, revoked.json)
        after = self.client.get("/api/devices", headers=self.headers)
        self.assertEqual(after.status_code, 401, after.json)

    def test_logout_revokes_rest_socket_reconnect_and_connected_socket(self):
        connected = socketio.test_client(app, auth={"token": self.token})
        self.assertTrue(connected.is_connected())

        logged_out = self.client.post("/api/logout", headers=self.headers)
        self.assertEqual(logged_out.status_code, 200, logged_out.json)
        self.assertTrue(logged_out.json["logged_out"])

        # The device and its public keys remain registered, but this token can
        # no longer authorize either transport.
        denied = self.client.get("/api/devices", headers=self.headers)
        self.assertEqual(denied.status_code, 401, denied.json)
        reconnect = socketio.test_client(app, auth={"token": self.token})
        self.assertFalse(reconnect.is_connected())

        import sockets
        import store

        user = store.get_user_by_name(self.username)
        sockets.emit_to_user_devices(user["id"], "session_probe", {"value": 1})
        pushed = [e for e in connected.get_received() if e["name"] == "session_probe"]
        self.assertEqual(pushed, [])

        # A socket authenticated before logout is checked again for every
        # event; it is disconnected before its event can be processed.
        connected.emit("message_send", {}, callback=True)
        self.assertFalse(connected.is_connected())

        self.assertIsNotNone(store.get_device_by_sid(self.sid))
        relogin = self.device_login()
        self.assertEqual(relogin.status_code, 200, relogin.json)
        restored = self.client.get(
            "/api/devices",
            headers={"Authorization": f"Bearer {relogin.json['token']}"},
        )
        self.assertEqual(restored.status_code, 200, restored.json)

    def test_device_login_rotates_only_that_devices_session(self):
        second = self.register_and_approve_device(
            {
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "second-session",
                "pub_key": "C" * 43,
                "sig_pub": "D" * 43,
            },
        )
        second_headers = {
            "Authorization": f"Bearer {second.json['token']}"
        }
        first_socket = socketio.test_client(app, auth={"token": self.token})
        second_socket = socketio.test_client(
            app, auth={"token": second.json["token"]}
        )
        self.assertTrue(first_socket.is_connected())
        self.assertTrue(second_socket.is_connected())
        first_socket.get_received()
        second_socket.get_received()

        relogin = self.device_login()
        self.assertEqual(relogin.status_code, 200, relogin.json)
        self.assertNotEqual(relogin.json["token"], self.token)

        old_denied = self.client.get("/api/devices", headers=self.headers)
        self.assertEqual(old_denied.status_code, 401, old_denied.json)
        new_allowed = self.client.get(
            "/api/devices",
            headers={"Authorization": f"Bearer {relogin.json['token']}"},
        )
        self.assertEqual(new_allowed.status_code, 200, new_allowed.json)
        other_allowed = self.client.get("/api/devices", headers=second_headers)
        self.assertEqual(other_allowed.status_code, 200, other_allowed.json)

        import sockets
        import store

        user = store.get_user_by_name(self.username)
        sockets.emit_to_user_devices(user["id"], "rotation_probe", {"value": 1})
        self.assertEqual(
            [e for e in first_socket.get_received() if e["name"] == "rotation_probe"],
            [],
        )
        second_events = [
            e for e in second_socket.get_received() if e["name"] == "rotation_probe"
        ]
        self.assertEqual(len(second_events), 1, second_events)
        self.assertTrue(second_socket.is_connected())
        first_socket.emit("message_send", {}, callback=True)
        self.assertFalse(first_socket.is_connected())
        second_socket.disconnect()

    def test_device_login_cannot_issue_token_after_concurrent_revoke(self):
        original_consume = store.consume_device_login_challenge

        def revoke_then_consume(challenge_id, challenge, device_id, user_id, sid, session_version):
            with store.conn_ctx() as conn:
                conn.execute(
                    "UPDATE devices SET trust_state='revoked', revoked_at=?, "
                    "session_version=session_version+1 WHERE id=? AND user_id=?",
                    (store.now(), device_id, user_id),
                )
            return original_consume(challenge_id, challenge, device_id, user_id, sid, session_version)

        with mock.patch.object(
            store, "consume_device_login_challenge", side_effect=revoke_then_consume
        ), mock.patch("auth.issue_jwt", wraps=__import__("auth").issue_jwt) as issue:
            relogin = self.device_login()

        self.assertEqual(relogin.status_code, 403, relogin.json)
        self.assertEqual(relogin.json["error"], "device revoked")
        issue.assert_not_called()

    def test_device_login_requires_valid_one_time_device_key_proof(self):
        common = {"username": self.username, "pw_hash": self.pw_hash, "sid": self.sid}
        proofless = self.client.post("/api/device-login", json={**common, "challenge_id": "x"})
        self.assertEqual(proofless.status_code, 400, proofless.json)

        challenge = self.client.post("/api/device-login", json=common).json
        statement = device_login_statement(
            self.uid, self.sid, challenge["challenge_id"], challenge["challenge"], challenge["session_version"]
        )
        wrong = SigningKey(bytes([99]) * 32)
        wrong_proof = base64.urlsafe_b64encode(wrong.sign(statement.encode()).signature).decode().rstrip("=")
        denied = self.client.post("/api/device-login", json={
            **common, "challenge_id": challenge["challenge_id"], "challenge": challenge["challenge"], "proof": wrong_proof,
        })
        self.assertEqual(denied.status_code, 401, denied.json)

        valid_proof = base64.urlsafe_b64encode(self.signing_key.sign(statement.encode()).signature).decode().rstrip("=")
        body = {**common, "challenge_id": challenge["challenge_id"], "challenge": challenge["challenge"], "proof": valid_proof}
        accepted = self.client.post("/api/device-login", json=body)
        self.assertEqual(accepted.status_code, 200, accepted.json)
        replay = self.client.post("/api/device-login", json=body)
        self.assertIn(replay.status_code, (401, 409), replay.json)

    def test_device_login_rejects_tampered_challenge_and_stale_session(self):
        common = {"username": self.username, "pw_hash": self.pw_hash, "sid": self.sid}
        challenge = self.client.post("/api/device-login", json=common).json
        statement = device_login_statement(
            self.uid, self.sid, challenge["challenge_id"], challenge["challenge"], challenge["session_version"]
        )
        proof = base64.urlsafe_b64encode(self.signing_key.sign(statement.encode()).signature).decode().rstrip("=")
        tampered = "A" * 43
        denied = self.client.post("/api/device-login", json={
            **common, "challenge_id": challenge["challenge_id"], "challenge": tampered, "proof": proof,
        })
        self.assertEqual(denied.status_code, 401, denied.json)
        store.rotate_device_session(store.get_device_by_sid(self.sid)["id"], self.uid)
        stale = self.client.post("/api/device-login", json={
            **common, "challenge_id": challenge["challenge_id"], "challenge": challenge["challenge"], "proof": proof,
        })
        self.assertEqual(stale.status_code, 401, stale.json)

    def test_message_delivered_rejects_bool_and_malformed_sequences(self):
        client_socket = socketio.test_client(app, auth={"token": self.token})
        self.assertTrue(client_socket.is_connected())

        with mock.patch.object(store, "set_cursor") as set_cursor:
            for invalid_seq in (True, False, 1.5, "1.0", " 1", {}, None):
                client_socket.emit(
                    "message_delivered",
                    {"cid": "any-conversation", "seq": invalid_seq},
                )

        set_cursor.assert_not_called()
        client_socket.disconnect()

    def test_logout_rejects_missing_malformed_and_old_format_tokens(self):
        missing = self.client.post("/api/logout")
        self.assertEqual(missing.status_code, 401, missing.json)
        malformed = self.client.post(
            "/api/logout", headers={"Authorization": "Bearer not-a-jwt"}
        )
        self.assertEqual(malformed.status_code, 401, malformed.json)

        import time

        import config
        import jwt

        old_format = jwt.encode(
            {
                "uid": 1,
                "sid": self.sid,
                "iat": int(time.time()),
                "exp": int(time.time()) + 60,
            },
            config.JWT_SECRET,
            algorithm=config.JWT_ALG,
        )
        missing_version = self.client.post(
            "/api/logout",
            headers={"Authorization": f"Bearer {old_format}"},
        )
        self.assertEqual(missing_version.status_code, 401, missing_version.json)

    def test_registration_token_version_and_malformed_socket_claims(self):
        import time

        import config
        import jwt
        import store
        from auth import verify_jwt

        device = store.get_device_by_sid(self.sid)
        decoded = verify_jwt(self.token)
        self.assertEqual(decoded["sv"], device["session_version"])

        base_claims = {
            "uid": device["user_id"],
            "sid": self.sid,
            "iat": int(time.time()),
            "exp": int(time.time()) + 60,
        }
        for session_version in (None, "invalid", device["session_version"] + 1):
            claims = dict(base_claims)
            if session_version is not None:
                claims["sv"] = session_version
            token = jwt.encode(
                claims, config.JWT_SECRET, algorithm=config.JWT_ALG
            )
            rejected = socketio.test_client(app, auth={"token": token})
            self.assertFalse(rejected.is_connected())

    def test_existing_database_migrates_device_session_version(self):
        import sqlite3
        from contextlib import closing
        from unittest.mock import patch

        import store

        with tempfile.TemporaryDirectory() as tmp:
            legacy_path = Path(tmp) / "legacy.db"
            with closing(sqlite3.connect(legacy_path)) as conn:
                conn.executescript(
                    """
                    CREATE TABLE users (
                        id INTEGER PRIMARY KEY, username TEXT UNIQUE NOT NULL,
                        pw_hash TEXT NOT NULL, created_at INTEGER NOT NULL
                    );
                    CREATE TABLE devices (
                        id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL,
                        sid TEXT UNIQUE NOT NULL, name TEXT NOT NULL,
                        kind TEXT NOT NULL DEFAULT 'web', pub_key TEXT NOT NULL,
                        sig_pub TEXT NOT NULL, created_at INTEGER NOT NULL,
                        last_seen INTEGER NOT NULL,
                        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                    );
                    INSERT INTO users VALUES (1, 'legacy', 'hash', 1);
                    INSERT INTO devices VALUES
                        (1, 1, 'legacy01', 'old', 'web', 'pub', 'sig', 1, 1);
                    """
                )
            with patch.object(store, "DB_PATH", legacy_path):
                store.init_schema()
                with store.conn_ctx() as conn:
                    columns = {
                        row[1] for row in conn.execute("PRAGMA table_info(devices)")
                    }
                    version = conn.execute(
                        "SELECT session_version FROM devices WHERE id = 1"
                    ).fetchone()[0]
            self.assertIn("session_version", columns)
            with store.conn_ctx() as connection:
                challenge_columns = {
                    row[1] for row in connection.execute(
                        "PRAGMA table_info(device_login_challenges)"
                    ).fetchall()
                }
            self.assertEqual(
                {"challenge_id", "user_id", "device_id", "sid", "challenge", "session_version", "expires_at", "consumed_at", "created_at"},
                challenge_columns,
            )
            self.assertEqual(version, 1)

    def test_history_payload_is_a_json_object(self):
        import json

        import store

        created = self.create_conversation()
        user = store.get_user_by_name(self.username)
        payload = {
            "ct": "A" * 22,
            "nonce": "A" * 32,
            "keys": {self.sid: {"ek": "A" * 64, "n": "A" * 32}},
        }
        store.insert_message(
            created["conv_id"],
            user["id"],
            self.sid,
            json.dumps(payload, separators=(",", ":")),
        )
        response = self.client.get(
            f"/api/conversation/{created['cid']}/messages?since=0&limit=20",
            headers=self.headers,
        )
        self.assertEqual(response.status_code, 200, response.json)
        self.assertIsInstance(response.json["messages"][0]["payload"], dict)
        self.assertEqual(response.json["messages"][0]["payload"], payload)

    def test_invalid_history_query_returns_400(self):
        created = self.create_conversation()
        response = self.client.get(
            f"/api/conversation/{created['cid']}/messages?since=nope",
            headers=self.headers,
        )
        self.assertEqual(response.status_code, 400, response.json)

    def test_username_is_ascii_only(self):
        response = self.client.post(
            "/api/register",
            json={"username": "사용자123", "pw_hash": self.pw_hash},
        )
        self.assertEqual(response.status_code, 400, response.json)

    def test_non_string_fields_return_400_instead_of_500(self):
        registration = self.client.post(
            "/api/register",
            json={"username": ["alice"], "pw_hash": self.pw_hash},
        )
        self.assertEqual(registration.status_code, 400, registration.json)
        conversation = self.client.post(
            "/api/conversation",
            headers=self.headers,
            json={"members": [self.username], "name": ["not", "text"]},
        )
        self.assertEqual(conversation.status_code, 400, conversation.json)
        list_body = self.client.post("/api/login", json=["not", "an", "object"])
        self.assertEqual(list_body.status_code, 400, list_body.json)

    def test_device_public_key_is_decoded_and_validated(self):
        response = self.client.post(
            "/api/device-register",
            json={
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "bad-key",
                "pub_key": "_" * 43,
                "sig_pub": "not-base64",
            },
        )
        self.assertEqual(response.status_code, 400, response.json)

    def test_only_one_android_sms_gateway_per_account(self):
        first = self.register_and_approve_device(
            {
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "android-one",
                "device_kind": "android_gateway",
                "pub_key": "C" * 43,
                "sig_pub": "D" * 43,
            },
        )
        second = self.client.post(
            "/api/device-register",
            json={
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "android-two",
                "device_kind": "android_gateway",
                "pub_key": "E" * 43,
                "sig_pub": "F" * 43,
            },
        )
        self.assertEqual(second.status_code, 200, second.json)
        self.assertEqual(second.json["trust_state"], "pending")
        rejected = self.client.post(
            "/api/device-approve",
            headers=self.headers,
            json=self.approval_payload(second),
        )
        self.assertEqual(rejected.status_code, 409, rejected.json)
        self.assertEqual(
            rejected.json["error"], "an Android SMS gateway is already approved"
        )

    def test_socket_fanout_is_once_per_device(self):
        second = self.register_and_approve_device(
            {
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "second",
                "pub_key": "C" * 43,
                "sig_pub": "D" * 43,
            },
        )
        created = self.create_conversation()

        first_socket = socketio.test_client(app, auth={"token": self.token})
        second_socket = socketio.test_client(app, auth={"token": second.json["token"]})
        self.assertTrue(first_socket.is_connected())
        self.assertTrue(second_socket.is_connected())
        first_socket.get_received()
        second_socket.get_received()

        payload = {
            "ct": "A" * 22,
            "nonce": "A" * 32,
            "keys": {
                self.sid: {"ek": "A" * 64, "n": "A" * 32},
                second.json["sid"]: {"ek": "B" * 64, "n": "B" * 32},
            },
        }
        ack = first_socket.emit(
            "message_send",
            {"cid": created["cid"], "mid": "test-message-id-0001", "payload": payload},
            callback=True,
        )
        self.assertTrue(ack["ok"], ack)
        received = [
            event
            for event in second_socket.get_received()
            if event["name"] == "message_new"
        ]
        self.assertEqual(len(received), 1, received)

        # A retry must still acknowledge the original message after the device
        # set changes. Requiring a newly wrapped key here would make a lost ACK
        # impossible to recover safely.
        third = self.register_and_approve_device(
            {
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "third-after-send",
                "pub_key": "E" * 43,
                "sig_pub": "F" * 43,
            },
        )
        duplicate_ack = first_socket.emit(
            "message_send",
            {"cid": created["cid"], "mid": "test-message-id-0001", "payload": payload},
            callback=True,
        )
        self.assertTrue(duplicate_ack["ok"], duplicate_ack)
        self.assertTrue(duplicate_ack["duplicate"], duplicate_ack)
        self.assertEqual(duplicate_ack["seq"], ack["seq"])
        duplicate_events = [
            event
            for event in second_socket.get_received()
            if event["name"] == "message_new"
        ]
        self.assertEqual(duplicate_events, [])
        first_socket.disconnect()
        second_socket.disconnect()

    def test_socket_fanout_skips_device_revoked_after_message_commit(self):
        second = self.register_and_approve_device(
            {
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "revoke-race-recipient",
                "pub_key": "C" * 43,
                "sig_pub": "D" * 43,
            },
        )
        created = self.create_conversation()
        sender_socket = socketio.test_client(app, auth={"token": self.token})
        revoked_socket = socketio.test_client(
            app, auth={"token": second.json["token"]}
        )
        self.assertTrue(sender_socket.is_connected())
        self.assertTrue(revoked_socket.is_connected())
        sender_socket.get_received()
        revoked_socket.get_received()

        payload = {
            "ct": "A" * 22,
            "nonce": "A" * 32,
            "keys": {
                self.sid: {"ek": "A" * 64, "n": "A" * 32},
                second.json["sid"]: {"ek": "B" * 64, "n": "B" * 32},
            },
        }
        committed = threading.Event()
        revoked = threading.Event()
        original_insert = store.insert_message

        def insert_then_wait_for_revoke(*args, **kwargs):
            result = original_insert(*args, **kwargs)
            committed.set()
            if not revoked.wait(timeout=5):
                raise AssertionError("revoke barrier timed out")
            return result

        result = {}

        def send_message():
            result["ack"] = sender_socket.emit(
                "message_send",
                {
                    "cid": created["cid"],
                    "mid": "revoke-race-message-0001",
                    "payload": payload,
                },
                callback=True,
            )

        with mock.patch(
            "sockets.store.insert_message", side_effect=insert_then_wait_for_revoke
        ):
            send_thread = threading.Thread(target=send_message)
            send_thread.start()
            self.assertTrue(committed.wait(timeout=5), "message commit barrier timed out")
            with store.conn_ctx() as connection:
                connection.execute(
                    "UPDATE devices SET trust_state='revoked', revoked_at=?, "
                    "session_version=session_version+1 WHERE sid=?",
                    (store.now(), second.json["sid"]),
                )
            revoked.set()
            send_thread.join(timeout=5)

        self.assertFalse(send_thread.is_alive(), "message sender thread timed out")
        self.assertTrue(result["ack"]["ok"], result["ack"])
        revoked_events = [
            event
            for event in revoked_socket.get_received()
            if event["name"] == "message_new"
        ]
        self.assertEqual(revoked_events, [])
        sender_events = [
            event
            for event in sender_socket.get_received()
            if event["name"] == "message_new"
        ]
        self.assertEqual(len(sender_events), 1, sender_events)
        sender_socket.disconnect()
        revoked_socket.disconnect()

    def test_message_retry_rejects_changed_payload_or_cid(self):
        created = self.create_conversation()
        other = self.create_conversation("+821012345679")
        socket_client = socketio.test_client(app, auth={"token": self.token})
        payload = {
            "ct": "A" * 22,
            "nonce": "A" * 32,
            "keys": {self.sid: {"ek": "A" * 64, "n": "A" * 32}},
        }
        first = socket_client.emit(
            "message_send",
            {
                "cid": created["cid"],
                "mid": "retry-binding-test-0001",
                "payload": payload,
            },
            callback=True,
        )
        self.assertTrue(first["ok"], first)

        # JSON object ordering is not part of the encrypted envelope identity.
        reordered = {
            "keys": {self.sid: {"n": "A" * 32, "ek": "A" * 64}},
            "nonce": "A" * 32,
            "ct": "A" * 22,
        }
        identical = socket_client.emit(
            "message_send",
            {
                "cid": created["cid"],
                "mid": "retry-binding-test-0001",
                "payload": reordered,
            },
            callback=True,
        )
        self.assertTrue(identical["ok"], identical)
        self.assertTrue(identical["duplicate"], identical)
        self.assertEqual(identical["id"], first["id"])

        changed_payload = dict(payload)
        changed_payload["ct"] = "B" * 22
        for cid, candidate in (
            (created["cid"], changed_payload),
            (other["cid"], payload),
        ):
            conflict = socket_client.emit(
                "message_send",
                {"cid": cid, "mid": "retry-binding-test-0001", "payload": candidate},
                callback=True,
            )
            self.assertEqual(
                conflict,
                {"ok": False, "error": "message id conflicts with original message"},
            )

        socket_client.disconnect()

    def test_insert_rejects_stale_device_snapshot_atomically(self):
        import json

        import store

        created = self.create_conversation()
        user = store.get_user_by_name(self.username)
        with self.assertRaisesRegex(ValueError, "payload keys do not match"):
            store.insert_message(
                created["conv_id"],
                user["id"],
                self.sid,
                json.dumps({"ct": "AA", "nonce": "A" * 32, "keys": {}}),
                client_mid="stale-device-test-0001",
                expected_recipient_sids={"missing-device"},
            )
        with store.conn_ctx() as connection:
            count = connection.execute(
                "SELECT COUNT(*) AS count FROM messages WHERE conv_id = ?",
                (created["conv_id"],),
            ).fetchone()["count"]
        self.assertEqual(count, 0)

    def test_health_and_http_errors_are_json(self):
        import config

        health = self.client.get("/health")
        self.assertEqual(health.status_code, 200, health.json)
        self.assertEqual(health.json, {"ok": True})

        missing = self.client.get("/api/does-not-exist")
        self.assertEqual(missing.status_code, 404, missing.json)
        self.assertEqual(missing.json["error"], "not found")

        too_large = self.client.post(
            "/api/login",
            data=b"x" * (config.MAX_HTTP_BODY_BYTES + 1),
            content_type="application/json",
        )
        self.assertEqual(too_large.status_code, 413, too_large.json)
        self.assertEqual(too_large.json["error"], "request body too large")

    def test_sender_key_snapshot_survives_device_revocation(self):
        import json

        import store

        second = self.register_and_approve_device(
            {
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "history-reader",
                "pub_key": "C" * 43,
                "sig_pub": "D" * 43,
            },
        )
        created = self.create_conversation()
        user = store.get_user_by_name(self.username)
        payload = {
            "ct": "A" * 22,
            "nonce": "A" * 32,
            "keys": {
                self.sid: {"ek": "A" * 64, "n": "A" * 32},
                second.json["sid"]: {"ek": "B" * 64, "n": "B" * 32},
            },
        }
        store.insert_message(
            created["conv_id"],
            user["id"],
            self.sid,
            json.dumps(payload, separators=(",", ":")),
        )
        second_headers = {"Authorization": f"Bearer {second.json['token']}"}
        revoked = self.client.post(
            "/api/device-revoke",
            headers=second_headers,
            json=self.revoke_payload(self.sid, second.json["sid"]),
        )
        self.assertEqual(revoked.status_code, 200, revoked.json)
        history = self.client.get(
            f"/api/conversation/{created['cid']}/messages?since=0&limit=20",
            headers=second_headers,
        )
        self.assertEqual(history.status_code, 200, history.json)
        self.assertEqual(history.json["messages"][0]["sender_pub_key"], "A" * 43)

    def test_carrier_status_requires_gateway_and_is_persisted(self):
        gateway = self.register_and_approve_device(
            {
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "android-gateway",
                "device_kind": "android_gateway",
                "pub_key": "C" * 43,
                "sig_pub": "D" * 43,
            },
        )
        created = self.create_conversation()
        gateway_socket = socketio.test_client(
            app, auth={"token": gateway.json["token"]}
        )
        web_socket = socketio.test_client(app, auth={"token": self.token})
        self.assertTrue(gateway_socket.is_connected())
        self.assertTrue(web_socket.is_connected())
        gateway_socket.get_received()
        web_socket.get_received()

        payload = {
            "ct": "A" * 22,
            "nonce": "A" * 32,
            "keys": {
                self.sid: {"ek": "A" * 64, "n": "A" * 32},
                gateway.json["sid"]: {"ek": "B" * 64, "n": "B" * 32},
            },
        }
        ack = web_socket.emit(
            "message_send",
            {
                "cid": created["cid"],
                "mid": "carrier-status-test-001",
                "payload": payload,
            },
            callback=True,
        )
        self.assertTrue(ack["ok"], ack)

        for invalid_seq in (True, False, 1.5, "1.0", " 1", {}, None):
            malformed = gateway_socket.emit(
                "carrier_status",
                {
                    "cid": created["cid"],
                    "seq": invalid_seq,
                    "status": "sent",
                },
                callback=True,
            )
            self.assertEqual(
                malformed,
                {"ok": False, "error": "invalid sequence"},
            )

        rejected = web_socket.emit(
            "carrier_status",
            {"cid": created["cid"], "seq": ack["seq"], "status": "sent"},
            callback=True,
        )
        self.assertFalse(rejected["ok"], rejected)

        status = gateway_socket.emit(
            "carrier_status",
            {"cid": created["cid"], "seq": ack["seq"], "status": "sent"},
            callback=True,
        )
        self.assertTrue(status["ok"], status)
        self.assertEqual(status["carrier_status"], "sent")

        history = self.client.get(
            f"/api/conversation/{created['cid']}/messages?since=0&limit=20",
            headers=self.headers,
        )
        self.assertEqual(history.status_code, 200, history.json)
        self.assertEqual(history.json["messages"][0]["carrier_status"], "sent")
        gateway_socket.disconnect()
        web_socket.disconnect()

    def test_participant_gateway_cannot_mutate_carrier_status(self):
        alice_gateway = self.register_and_approve_device(
            {
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "alice-carrier-gateway",
                "device_kind": "android_gateway",
                "pub_key": "C" * 43,
                "sig_pub": "D" * 43,
            },
        )
        bob_username = "bob_" + hashlib.sha256(
            self._testMethodName.encode()
        ).hexdigest()[:12]
        registered = self.client.post(
            "/api/register",
            json={"username": bob_username, "pw_hash": self.pw_hash},
        )
        self.assertEqual(registered.status_code, 200, registered.json)
        bob_gateway = self.client.post(
            "/api/device-register",
            json={
                "username": bob_username,
                "pw_hash": self.pw_hash,
                "device_name": "bob-carrier-gateway",
                "device_kind": "android_gateway",
                "pub_key": "E" * 43,
                "sig_pub": "F" * 43,
            },
        )
        self.assertEqual(bob_gateway.status_code, 200, bob_gateway.json)
        self.assertEqual(bob_gateway.json["trust_state"], "approved")

        multi = self.client.post(
            "/api/conversation",
            headers=self.headers,
            json={"members": [bob_username], "name": "+821055500000"},
        )
        self.assertEqual(multi.status_code, 200, multi.json)
        alice_socket = socketio.test_client(app, auth={"token": self.token})
        bob_gateway_socket = socketio.test_client(
            app, auth={"token": bob_gateway.json["token"]}
        )
        payload = {
            "ct": "A" * 22,
            "nonce": "A" * 32,
            "keys": {
                self.sid: {"ek": "A" * 64, "n": "A" * 32},
                alice_gateway.json["sid"]: {"ek": "B" * 64, "n": "B" * 32},
                bob_gateway.json["sid"]: {"ek": "C" * 64, "n": "C" * 32},
            },
        }
        sent = alice_socket.emit(
            "message_send",
            {
                "cid": multi.json["cid"],
                "mid": "participant-gateway-test-01",
                "payload": payload,
            },
            callback=True,
        )
        self.assertTrue(sent["ok"], sent)

        rejected = bob_gateway_socket.emit(
            "carrier_status",
            {"cid": multi.json["cid"], "seq": sent["seq"], "status": "sent"},
            callback=True,
        )
        self.assertFalse(rejected["ok"], rejected)
        self.assertEqual(
            rejected["error"], "gateway does not own a self-only conversation"
        )
        history = self.client.get(
            f"/api/conversation/{multi.json['cid']}/messages?since=0&limit=20",
            headers=self.headers,
        )
        self.assertEqual(history.status_code, 200, history.json)
        self.assertEqual(history.json["messages"][0]["carrier_status"], "none")

        owned = self.create_conversation("+821055500001")
        owned_payload = {
            "ct": "A" * 22,
            "nonce": "A" * 32,
            "keys": {
                self.sid: {"ek": "A" * 64, "n": "A" * 32},
                alice_gateway.json["sid"]: {"ek": "B" * 64, "n": "B" * 32},
            },
        }
        owned_sent = alice_socket.emit(
            "message_send",
            {
                "cid": owned["cid"],
                "mid": "owned-gateway-status-test-01",
                "payload": owned_payload,
            },
            callback=True,
        )
        self.assertTrue(owned_sent["ok"], owned_sent)
        alice_gateway_socket = socketio.test_client(
            app, auth={"token": alice_gateway.json["token"]}
        )
        accepted = alice_gateway_socket.emit(
            "carrier_status",
            {"cid": owned["cid"], "seq": owned_sent["seq"], "status": "sent"},
            callback=True,
        )
        self.assertTrue(accepted["ok"], accepted)
        self.assertEqual(accepted["carrier_status"], "sent")

        alice_gateway_socket.disconnect()
        bob_gateway_socket.disconnect()
        alice_socket.disconnect()

    def test_terminal_carrier_status_cannot_regress(self):
        import json

        import store

        created = self.create_conversation()
        user = store.get_user_by_name(self.username)
        _, seq, _ = store.insert_message(
            created["conv_id"],
            user["id"],
            self.sid,
            json.dumps(
                {
                    "ct": "AA",
                    "nonce": "A" * 32,
                    "keys": {self.sid: {"ek": "A" * 64, "n": "A" * 32}},
                }
            ),
        )
        delivered = store.update_carrier_status(created["conv_id"], seq, "delivered")
        late = store.update_carrier_status(
            created["conv_id"], seq, "sent", "late callback"
        )
        self.assertEqual(delivered["status"], "delivered")
        self.assertEqual(late["status"], "delivered")
        self.assertIsNone(late["error"])

    def test_connected_socket_is_closed_when_jwt_expires(self):
        import time
        from unittest.mock import patch

        client = socketio.test_client(app, auth={"token": self.token})
        self.assertTrue(client.is_connected())
        with patch(
            "sockets.time.time", return_value=time.time() + 10 * 365 * 24 * 60 * 60
        ):
            client.emit("message_send", {}, callback=True)
        self.assertFalse(client.is_connected())

    def test_carrier_error_is_bounded_and_non_string_is_dropped(self):
        import json

        import store

        created = self.create_conversation()
        user = store.get_user_by_name(self.username)
        _, first_seq, _ = store.insert_message(
            created["conv_id"],
            user["id"],
            self.sid,
            json.dumps(
                {
                    "ct": "AA",
                    "nonce": "A" * 32,
                    "keys": {self.sid: {"ek": "A" * 64, "n": "A" * 32}},
                }
            ),
        )
        first = store.update_carrier_status(
            created["conv_id"], first_seq, "failed", "x" * 500
        )
        self.assertEqual(len(first["error"]), 300)

        _, second_seq, _ = store.insert_message(
            created["conv_id"],
            user["id"],
            self.sid,
            json.dumps(
                {
                    "ct": "AA",
                    "nonce": "A" * 32,
                    "keys": {self.sid: {"ek": "A" * 64, "n": "A" * 32}},
                }
            ),
        )
        second = store.update_carrier_status(
            created["conv_id"], second_seq, "failed", 123
        )
        self.assertIsNone(second["error"])

    def test_login_unknown_user_returns_401_and_known_user_logs_in(self):
        ok = self.client.post(
            "/api/login",
            json={"username": self.username, "pw_hash": self.pw_hash},
        )
        self.assertEqual(ok.status_code, 200, ok.json)
        self.assertEqual(ok.json["username"], self.username)
        # Unknown usernames must still cost one bcrypt verification (timing
        # equalizer) and answer 401 — no account-existence oracle in the timing.
        missing = self.client.post(
            "/api/login",
            json={"username": "ghost_user_xyz", "pw_hash": self.pw_hash},
        )
        self.assertEqual(missing.status_code, 401, missing.json)

    def test_client_ip_uses_proxy_appended_forwarded_entry(self):
        import rate_limit

        spoofed = app.test_request_context(
            "/", headers={"X-Forwarded-For": "9.9.9.9, 203.0.113.7"}
        )
        with spoofed:
            # Leftmost entries are client-controlled; Caddy appends the real
            # client IP as the rightmost entry.
            self.assertEqual(rate_limit.client_ip(), "203.0.113.7")
        direct = app.test_request_context(
            "/", environ_base={"REMOTE_ADDR": "127.0.0.1"}
        )
        with direct:
            self.assertEqual(rate_limit.client_ip(), "127.0.0.1")

    def test_rate_limiter_denies_after_window_limit(self):
        import rate_limit

        was_testing = app.testing
        app.testing = False
        try:
            with app.test_request_context("/"):
                for _ in range(3):
                    self.assertIsNone(
                        rate_limit.check("unit-limiter", "id", 3, 60)
                    )
                retry_after = rate_limit.check("unit-limiter", "id", 3, 60)
                self.assertIsNotNone(retry_after)
                self.assertGreaterEqual(retry_after, 1)
        finally:
            app.testing = was_testing

    def test_typing_reaches_the_senders_other_devices(self):
        second = self.register_and_approve_device(
            {
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "second-tab",
                "pub_key": "C" * 43,
                "sig_pub": "D" * 43,
            },
        )
        created = self.create_conversation()
        first_socket = socketio.test_client(app, auth={"token": self.token})
        second_socket = socketio.test_client(
            app, auth={"token": second.json["token"]}
        )
        first_socket.get_received()
        second_socket.get_received()

        first_socket.emit("typing", {"cid": created["cid"], "is_typing": True})
        events = [
            event
            for event in second_socket.get_received()
            if event["name"] == "typing"
        ]
        self.assertEqual(len(events), 1, events)
        self.assertEqual(events[0]["args"][0]["cid"], created["cid"])
        self.assertEqual(events[0]["args"][0]["user_id"], second.json["uid"])
        self.assertTrue(events[0]["args"][0]["is_typing"])
        first_socket.disconnect()
        second_socket.disconnect()

    def test_socket_is_closed_when_its_device_is_revoked(self):
        created = self.create_conversation()
        second = self.register_and_approve_device(
            {
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "socket-revocation-authorizer",
                "pub_key": "C" * 43,
                "sig_pub": "D" * 43,
            }
        )
        client = socketio.test_client(app, auth={"token": self.token})
        self.assertTrue(client.is_connected())
        revoked = self.client.post(
            "/api/device-revoke",
            headers={"Authorization": f"Bearer {second.json['token']}"},
            json=self.revoke_payload(self.sid, second.json["sid"]),
        )
        self.assertEqual(revoked.status_code, 200, revoked.json)
        # The next event from the revoked device must terminate the socket.
        client.emit(
            "message_send", {"cid": created["cid"], "payload": {}}, callback=True
        )
        self.assertFalse(client.is_connected())

    def test_live_envelope_created_at_matches_stored_history(self):
        second = self.register_and_approve_device(
            {
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "history-reader",
                "pub_key": "C" * 43,
                "sig_pub": "D" * 43,
            },
        )
        created = self.create_conversation()
        first_socket = socketio.test_client(app, auth={"token": self.token})
        second_socket = socketio.test_client(
            app, auth={"token": second.json["token"]}
        )
        second_socket.get_received()
        payload = {
            "ct": "A" * 22,
            "nonce": "A" * 32,
            "keys": {
                self.sid: {"ek": "A" * 64, "n": "A" * 32},
                second.json["sid"]: {"ek": "B" * 64, "n": "B" * 32},
            },
        }
        ack = first_socket.emit(
            "message_send",
            {"cid": created["cid"], "mid": "created-at-match-0001", "payload": payload},
            callback=True,
        )
        self.assertTrue(ack["ok"], ack)
        live = [
            event
            for event in second_socket.get_received()
            if event["name"] == "message_new"
        ]
        self.assertEqual(len(live), 1, live)
        history = self.client.get(
            f"/api/conversation/{created['cid']}/messages?since=0&limit=20",
            headers=self.headers,
        )
        self.assertEqual(history.status_code, 200, history.json)
        row = history.json["messages"][0]
        self.assertEqual(live[0]["args"][0]["created_at"], row["created_at"])
        self.assertEqual(live[0]["args"][0]["seq"], row["seq"])
        first_socket.disconnect()
        second_socket.disconnect()

    # ----- shared block rules (cross-device sync) -----------------------

    def add_rule(self, rule_type, value, headers=None):
        return self.client.post(
            "/api/blocklist",
            headers=headers or self.headers,
            json={"type": rule_type, "value": value},
        )

    def test_blocklist_add_list_remove_roundtrip(self):
        added = self.add_rule("keyword", "광고")
        self.assertEqual(added.status_code, 200, added.json)
        rule_id = added.json["rule"]["id"]
        added_sender = self.add_rule("sender", "+821012345678")
        self.assertEqual(added_sender.status_code, 200, added_sender.json)

        listed = self.client.get("/api/blocklist", headers=self.headers)
        self.assertEqual(listed.status_code, 200, listed.json)
        rules = listed.json["rules"]
        self.assertEqual(len(rules), 2)
        self.assertIn("keyword", {r["type"] for r in rules})
        self.assertIn("sender", {r["type"] for r in rules})

        removed = self.client.post(
            "/api/blocklist/remove", headers=self.headers, json={"id": rule_id}
        )
        self.assertEqual(removed.status_code, 200, removed.json)
        remaining = self.client.get("/api/blocklist", headers=self.headers).json["rules"]
        self.assertEqual([r["type"] for r in remaining], ["sender"])

    def test_blocklist_add_is_idempotent(self):
        first = self.add_rule("keyword", "스팸")
        second = self.add_rule("keyword", "스팸")
        self.assertEqual(first.json["rule"]["id"], second.json["rule"]["id"])
        rules = self.client.get("/api/blocklist", headers=self.headers).json["rules"]
        self.assertEqual(len([r for r in rules if r["value"] == "스팸"]), 1)

    def test_blocklist_rejects_invalid(self):
        self.assertEqual(self.add_rule("bogus", "x").status_code, 400)
        self.assertEqual(self.add_rule("keyword", "").status_code, 400)
        self.assertEqual(self.add_rule("keyword", "x" * 121).status_code, 400)
        self.assertEqual(self.add_rule("sender", "not-a-phone").status_code, 400)

    def test_blocklist_is_per_user(self):
        self.add_rule("keyword", "비밀")
        other = self.client.post(
            "/api/register",
            json={"username": "blockpeer_" + self.username[-6:], "pw_hash": self.pw_hash},
        )
        self.assertEqual(other.status_code, 200, other.json)
        other_device = self.client.post(
            "/api/device-register",
            json={
                "username": other.json["username"],
                "pw_hash": self.pw_hash,
                "device_name": "peer",
                "pub_key": "E" * 43,
                "sig_pub": "F" * 43,
            },
        )
        other_headers = {"Authorization": f"Bearer {other_device.json['token']}"}
        peer_rules = self.client.get("/api/blocklist", headers=other_headers).json["rules"]
        self.assertEqual(peer_rules, [])

    def test_blocklist_add_fans_out_to_other_devices(self):
        second = self.register_and_approve_device(
            {
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "second",
                "pub_key": "C" * 43,
                "sig_pub": "D" * 43,
            },
        )
        origin_socket = socketio.test_client(app, auth={"token": self.token})
        other_socket = socketio.test_client(app, auth={"token": second.json["token"]})

        added = self.add_rule("keyword", "홍보")
        self.assertEqual(added.status_code, 200, added.json)

        other_events = [
            e for e in other_socket.get_received() if e["name"] == "blocklist_updated"
        ]
        origin_events = [
            e for e in origin_socket.get_received() if e["name"] == "blocklist_updated"
        ]
        self.assertEqual(len(other_events), 1, other_events)
        self.assertEqual(other_events[0]["args"][0]["action"], "add")
        self.assertEqual(other_events[0]["args"][0]["rule"]["value"], "홍보")
        # The device that made the change must not be notified about itself.
        self.assertEqual(origin_events, [])
        origin_socket.disconnect()
        other_socket.disconnect()

    # ----- conversation rename ------------------------------------------

    def test_bulk_contact_names_sync_is_atomic_and_fans_out_once(self):
        first = self.create_conversation("+821011110001")
        second = self.create_conversation("+821011110002")
        gateway = self.register_and_approve_device(
            {
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "contacts-gateway",
                "device_kind": "android_gateway",
                "pub_key": "O" * 43,
                "sig_pub": "P" * 43,
            },
        )
        gateway_headers = {"Authorization": f"Bearer {gateway.json['token']}"}
        origin_socket = socketio.test_client(
            app, auth={"token": gateway.json["token"]}
        )
        web_socket = socketio.test_client(app, auth={"token": self.token})
        origin_socket.get_received()
        web_socket.get_received()

        synced = self.client.post(
            "/api/contact-names/sync",
            headers=gateway_headers,
            json={
                "entries": [
                    {"cid": first["cid"], "contact_name": "  엄마  "},
                    {"cid": second["cid"], "contact_name": "아빠"},
                ]
            },
        )
        self.assertEqual(synced.status_code, 200, synced.json)
        self.assertEqual(synced.json["updated"], 2)
        expected_entries = [
            {"cid": first["cid"], "contact_name": "엄마"},
            {"cid": second["cid"], "contact_name": "아빠"},
        ]
        self.assertEqual(synced.json["entries"], expected_entries)

        listed = self.client.get("/api/conversations", headers=self.headers).json
        by_cid = {row["cid"]: row for row in listed["conversations"]}
        self.assertEqual(by_cid[first["cid"]]["name"], "+821011110001")
        self.assertEqual(by_cid[first["cid"]]["synced_contact_name"], "엄마")
        self.assertEqual(by_cid[second["cid"]]["name"], "+821011110002")
        self.assertEqual(by_cid[second["cid"]]["synced_contact_name"], "아빠")

        for connected in (origin_socket, web_socket):
            events = [
                event
                for event in connected.get_received()
                if event["name"] == "contacts_updated"
            ]
            self.assertEqual(len(events), 1, events)
            self.assertEqual(events[0]["args"][0], {"entries": expected_entries})

        cleared = self.client.post(
            "/api/contact-names/sync",
            headers=gateway_headers,
            json={
                "entries": [
                    {"cid": first["cid"], "contact_name": None},
                    {"cid": second["cid"], "contact_name": "   "},
                ]
            },
        )
        self.assertEqual(cleared.status_code, 200, cleared.json)
        listed = self.client.get("/api/conversations", headers=self.headers).json
        by_cid = {row["cid"]: row for row in listed["conversations"]}
        self.assertEqual(by_cid[first["cid"]]["synced_contact_name"], "")
        self.assertEqual(by_cid[second["cid"]]["synced_contact_name"], "")
        origin_socket.disconnect()
        web_socket.disconnect()

    def test_bulk_contact_names_requires_gateway_and_validates_bounds(self):
        created = self.create_conversation("+821022220001")
        endpoint = "/api/contact-names/sync"
        denied = self.client.post(
            endpoint,
            headers=self.headers,
            json={"entries": []},
        )
        self.assertEqual(denied.status_code, 403, denied.json)

        gateway = self.register_and_approve_device(
            {
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "validation-gateway",
                "device_kind": "android_gateway",
                "pub_key": "Q" * 43,
                "sig_pub": "R" * 43,
            },
        )
        headers = {"Authorization": f"Bearer {gateway.json['token']}"}
        invalid_entries = (
            None,
            {},
            [None],
            [{"cid": created["cid"]}],
            [{"cid": created["cid"], "contact_name": 1}],
            [{"cid": created["cid"], "contact_name": "x" * 101}],
            [{"cid": created["cid"], "contact_name": "line\nbreak"}],
            [
                {"cid": created["cid"], "contact_name": "a"},
                {"cid": created["cid"], "contact_name": "b"},
            ],
            [{"cid": f"cid-{index}", "contact_name": "x"} for index in range(501)],
        )
        for entries in invalid_entries:
            response = self.client.post(
                endpoint,
                headers=headers,
                json={"entries": entries},
            )
            self.assertEqual(response.status_code, 400, (entries, response.json))

        accepted_maximum = [
            {"cid": created["cid"], "contact_name": "x" * 100}
        ]
        response = self.client.post(
            endpoint, headers=headers, json={"entries": accepted_maximum}
        )
        self.assertEqual(response.status_code, 200, response.json)

        with mock.patch("conversations.rate_limit", return_value=9) as limiter:
            limited = self.client.post(endpoint, headers=headers, json={"entries": []})
        self.assertEqual(limited.status_code, 429, limited.json)
        self.assertEqual(limited.headers["Retry-After"], "9")
        limiter.assert_called_once_with(
            "contact-names-sync", gateway.json["sid"], 10, 60
        )

    def test_bulk_contact_names_rejects_non_self_conversations_atomically(self):
        import store

        valid = self.create_conversation("+821033330001")
        other = self.client.post(
            "/api/register",
            json={"username": "bulk_" + self.username[-6:], "pw_hash": self.pw_hash},
        )
        other_user = store.get_user_by_name(other.json["username"])
        multi_conv_id = store.create_conversation("bulk-multi", "+821033330002")
        current_user = store.get_user_by_name(self.username)
        store.add_member(multi_conv_id, current_user["id"])
        store.add_member(multi_conv_id, other_user["id"])

        gateway = self.register_and_approve_device(
            {
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "atomic-gateway",
                "device_kind": "android_gateway",
                "pub_key": "S" * 43,
                "sig_pub": "T" * 43,
            },
        )
        headers = {"Authorization": f"Bearer {gateway.json['token']}"}
        rejected = self.client.post(
            "/api/contact-names/sync",
            headers=headers,
            json={
                "entries": [
                    {"cid": valid["cid"], "contact_name": "must-not-commit"},
                    {"cid": "bulk-multi", "contact_name": "forbidden"},
                ]
            },
        )
        self.assertEqual(rejected.status_code, 403, rejected.json)
        self.assertEqual(
            store.get_conversation_by_cid(valid["cid"])["synced_contact_name"],
            "",
        )

        missing = self.client.post(
            "/api/contact-names/sync",
            headers=headers,
            json={"entries": [{"cid": "does-not-exist", "contact_name": "x"}]},
        )
        self.assertEqual(missing.status_code, 404, missing.json)

    def test_rename_conversation_updates_and_fans_out(self):
        created = self.create_conversation("+821055556666")
        second = self.register_and_approve_device(
            {
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "second",
                "pub_key": "G" * 43,
                "sig_pub": "H" * 43,
            },
        )
        other_socket = socketio.test_client(app, auth={"token": second.json["token"]})

        renamed = self.client.post(
            "/api/conversation/rename",
            headers=self.headers,
            json={"cid": created["cid"], "name": "엄마"},
        )
        self.assertEqual(renamed.status_code, 200, renamed.json)

        listed = self.client.get("/api/conversations", headers=self.headers).json
        self.assertEqual(listed["conversations"][0]["name"], "엄마")

        events = [e for e in other_socket.get_received() if e["name"] == "conv_updated"]
        self.assertEqual(len(events), 1, events)
        self.assertEqual(events[0]["args"][0]["cid"], created["cid"])
        self.assertEqual(events[0]["args"][0]["name"], "엄마")
        other_socket.disconnect()

    def test_rename_forbidden_for_non_member(self):
        created = self.create_conversation("+821077778888")
        other = self.client.post(
            "/api/register",
            json={"username": "renamer_" + self.username[-6:], "pw_hash": self.pw_hash},
        )
        other_device = self.client.post(
            "/api/device-register",
            json={
                "username": other.json["username"],
                "pw_hash": self.pw_hash,
                "device_name": "peer",
                "pub_key": "I" * 43,
                "sig_pub": "J" * 43,
            },
        )
        other_headers = {"Authorization": f"Bearer {other_device.json['token']}"}
        renamed = self.client.post(
            "/api/conversation/rename",
            headers=other_headers,
            json={"cid": created["cid"], "name": "hack"},
        )
        self.assertEqual(renamed.status_code, 403)


if __name__ == "__main__":
    unittest.main()
