import hashlib
import base64
import os
import sqlite3
import sys
import tempfile
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
from auth import approval_statement, revoke_statement
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
        relogin = self.client.post(
            "/api/device-login",
            json={
                "username": self.username,
                "pw_hash": self.pw_hash,
                "sid": self.sid,
            },
        )
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

        relogin = self.client.post(
            "/api/device-login",
            json={
                "username": self.username,
                "pw_hash": self.pw_hash,
                "sid": self.sid,
            },
        )
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
