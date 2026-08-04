import hashlib
import os
import sys
import tempfile
import unittest
from pathlib import Path

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

app.config["TESTING"] = True


def tearDownModule():
    for suffix in ("", "-wal", "-shm"):
        _db_path.with_name(_db_path.name + suffix).unlink(missing_ok=True)


class ServerSmokeTest(unittest.TestCase):
    def setUp(self):
        self.client = app.test_client()
        self.pw_hash = "A" * 43
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
                "sig_pub": "B" * 43,
            },
        )
        self.assertEqual(device.status_code, 200, device.json)
        self.token = device.json["token"]
        self.sid = device.json["sid"]

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

    def test_sms_conversation_name_is_returned(self):
        self.create_conversation()
        listed = self.client.get("/api/conversations", headers=self.headers)
        self.assertEqual(listed.status_code, 200, listed.json)
        self.assertEqual(listed.json["conversations"][0]["name"], "+821012345678")

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
        revoked = self.client.post(
            "/api/device-revoke",
            headers=self.headers,
            json={"sid": self.sid},
        )
        self.assertEqual(revoked.status_code, 200, revoked.json)
        after = self.client.get("/api/devices", headers=self.headers)
        self.assertEqual(after.status_code, 401, after.json)

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
        first = self.client.post(
            "/api/device-register",
            json={
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "android-one",
                "device_kind": "android_gateway",
                "pub_key": "C" * 43,
                "sig_pub": "D" * 43,
            },
        )
        self.assertEqual(first.status_code, 200, first.json)
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
        self.assertEqual(second.status_code, 409, second.json)

    def test_socket_fanout_is_once_per_device(self):
        second = self.client.post(
            "/api/device-register",
            json={
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "second",
                "pub_key": "C" * 43,
                "sig_pub": "D" * 43,
            },
        )
        self.assertEqual(second.status_code, 200, second.json)
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
        third = self.client.post(
            "/api/device-register",
            json={
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "third-after-send",
                "pub_key": "E" * 43,
                "sig_pub": "F" * 43,
            },
        )
        self.assertEqual(third.status_code, 200, third.json)
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

        second = self.client.post(
            "/api/device-register",
            json={
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "history-reader",
                "pub_key": "C" * 43,
                "sig_pub": "D" * 43,
            },
        )
        self.assertEqual(second.status_code, 200, second.json)
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
            json={"sid": self.sid},
        )
        self.assertEqual(revoked.status_code, 200, revoked.json)
        history = self.client.get(
            f"/api/conversation/{created['cid']}/messages?since=0&limit=20",
            headers=second_headers,
        )
        self.assertEqual(history.status_code, 200, history.json)
        self.assertEqual(history.json["messages"][0]["sender_pub_key"], "A" * 43)

    def test_carrier_status_requires_gateway_and_is_persisted(self):
        gateway = self.client.post(
            "/api/device-register",
            json={
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "android-gateway",
                "device_kind": "android_gateway",
                "pub_key": "C" * 43,
                "sig_pub": "D" * 43,
            },
        )
        self.assertEqual(gateway.status_code, 200, gateway.json)
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
        second = self.client.post(
            "/api/device-register",
            json={
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "second-tab",
                "pub_key": "C" * 43,
                "sig_pub": "D" * 43,
            },
        )
        self.assertEqual(second.status_code, 200, second.json)
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
        client = socketio.test_client(app, auth={"token": self.token})
        self.assertTrue(client.is_connected())
        revoked = self.client.post(
            "/api/device-revoke",
            headers=self.headers,
            json={"sid": self.sid},
        )
        self.assertEqual(revoked.status_code, 200, revoked.json)
        # The next event from the revoked device must terminate the socket.
        client.emit(
            "message_send", {"cid": created["cid"], "payload": {}}, callback=True
        )
        self.assertFalse(client.is_connected())

    def test_live_envelope_created_at_matches_stored_history(self):
        second = self.client.post(
            "/api/device-register",
            json={
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": "history-reader",
                "pub_key": "C" * 43,
                "sig_pub": "D" * 43,
            },
        )
        self.assertEqual(second.status_code, 200, second.json)
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


if __name__ == "__main__":
    unittest.main()
