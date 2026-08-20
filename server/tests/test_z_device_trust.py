"""Security regression tests for the trusted-device directory.

The filename deliberately sorts after ``test_smoke.py``.  During a full
unittest discovery run the application modules are therefore already loaded;
each test still patches ``store.DB_PATH`` to a fresh database so trust-state
tests cannot depend on (or mutate) smoke-test state.  Direct execution also
uses a temporary bootstrap database rather than the developer database.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
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


_bootstrap_path: Path | None = None
if "app" not in sys.modules:
    _bootstrap_file = tempfile.NamedTemporaryFile(
        prefix="securemsg-trust-bootstrap-", suffix=".db", delete=False
    )
    _bootstrap_file.close()
    _bootstrap_path = Path(_bootstrap_file.name)
    os.environ["SECUREMSG_DB"] = str(_bootstrap_path)
    os.environ["SECUREMSG_JWT_SECRET"] = (
        "trust-test-secret-for-unit-tests-32-bytes-minimum"
    )
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import config
import store
from app import app, socketio
from auth import (
    approval_statement,
    issue_jwt,
    legacy_upgrade_statement,
    revoke_statement,
)


app.config["TESTING"] = True


def _b64u(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _key_material(seed_byte: int) -> tuple[str, SigningKey, str]:
    signing_key = SigningKey(bytes([seed_byte]) * 32)
    # X25519 public keys are opaque to these API tests, but must decode to 32 bytes.
    box_public = _b64u(bytes([(seed_byte + 73) % 256]) * 32)
    sig_public = _b64u(bytes(signing_key.verify_key))
    return box_public, signing_key, sig_public


class TrustedDeviceTest(unittest.TestCase):
    def setUp(self) -> None:
        db_file = tempfile.NamedTemporaryFile(
            prefix="securemsg-trust-test-", suffix=".db", delete=False
        )
        db_file.close()
        self.db_path = Path(db_file.name)
        self.db_patch = mock.patch.object(store, "DB_PATH", self.db_path)
        self.db_patch.start()
        store.init_schema()

        self.client = app.test_client()
        suffix = hashlib.sha256(self._testMethodName.encode()).hexdigest()[:10]
        self.username = f"trust_{suffix}"
        self.pw_hash = "A" * 43
        registered = self._register_account(self.username, self.pw_hash)
        self.assertEqual(registered.status_code, 200, registered.json)
        self.uid = registered.json["uid"]
        self.first_box, self.first_signing, self.first_sig_public = _key_material(11)
        self.first = self._register_device(
            "first", self.first_box, self.first_sig_public
        )
        self.first_headers = self._headers(self.first["token"])

    def tearDown(self) -> None:
        self.db_patch.stop()
        for suffix in ("", "-wal", "-shm"):
            self.db_path.with_name(self.db_path.name + suffix).unlink(missing_ok=True)

    @staticmethod
    def _headers(token: str) -> dict[str, str]:
        return {"Authorization": f"Bearer {token}"}

    def _register_account(self, username: str, pw_hash: str):
        """Accounts are created through the email-verified flow only."""
        email = f"{username}@example.test"
        with mock.patch("emailer.send_code") as send_code:
            requested = self.client.post(
                "/api/register/email/request",
                json={"username": username, "email": email, "pw_hash": pw_hash},
            )
            self.assertEqual(requested.status_code, 200, requested.json)
            code = send_code.call_args.args[2]
        return self.client.post(
            "/api/register/email/verify",
            json={"challenge_id": requested.json["challenge_id"], "code": code},
        )

    def _register_device(
        self,
        name: str,
        pub_key: str,
        sig_pub: str,
        kind: str = "web",
    ) -> dict:
        response = self.client.post(
            "/api/device-register",
            json={
                "username": self.username,
                "pw_hash": self.pw_hash,
                "device_name": name,
                "device_kind": kind,
                "pub_key": pub_key,
                "sig_pub": sig_pub,
            },
        )
        self.assertEqual(response.status_code, 200, response.json)
        return response.json

    def _new_pending(self, seed: int = 29) -> tuple[dict, str, SigningKey, str]:
        box_public, signing_key, sig_public = _key_material(seed)
        registered = self._register_device("pending", box_public, sig_public)
        self.assertEqual(registered["trust_state"], "pending")
        return registered, box_public, signing_key, sig_public

    def _signed_approval(
        self,
        pending: dict,
        *,
        parent_epoch: int | None = None,
        signing_key: SigningKey | None = None,
        statement_suffix: str = "",
    ) -> tuple[dict, str]:
        epoch = pending["security_epoch"] if parent_epoch is None else parent_epoch
        subject = store.get_device_by_sid(pending["sid"])
        statement = approval_statement(self.uid, subject, epoch) + statement_suffix
        signature = _b64u(bytes((signing_key or self.first_signing).sign(
            statement.encode("utf-8")
        ).signature))
        return {
            "subject_sid": pending["sid"],
            "parent_epoch": epoch,
            "signature": signature,
        }, statement

    def _approve(self, pending: dict) -> dict:
        body, _statement = self._signed_approval(pending)
        response = self.client.post(
            "/api/device-approve", headers=self.first_headers, json=body
        )
        self.assertEqual(response.status_code, 200, response.json)
        return response.json

    def _signed_revoke(
        self,
        subject_sid: str,
        *,
        actor_sid: str | None = None,
        signing_key: SigningKey | None = None,
        parent_epoch: int | None = None,
        statement_suffix: str = "",
    ) -> dict:
        actor_sid = actor_sid or self.first["sid"]
        epoch = (
            store.get_user(self.uid)["security_epoch"]
            if parent_epoch is None
            else parent_epoch
        )
        subject = store.get_device_by_sid(subject_sid)
        statement = revoke_statement(
            self.uid, subject, actor_sid, epoch
        ) + statement_suffix
        signature = _b64u(
            bytes(
                (signing_key or self.first_signing)
                .sign(statement.encode("utf-8"))
                .signature
            )
        )
        return {
            "sid": subject_sid,
            "parent_epoch": epoch,
            "reason": "user_revoked",
            "signature": signature,
        }

    def test_first_device_bootstraps_and_later_device_is_pending_and_isolated(self):
        self.assertEqual(self.first["trust_state"], "approved")
        self.assertEqual(self.first["security_epoch"], 1)
        self.assertEqual(self.first["identity_sig_pub"], self.first_sig_public)

        pending, _box, _key, _sig = self._new_pending()
        self.assertEqual(len(base64.urlsafe_b64decode(pending["challenge"] + "=")), 32)
        pending_headers = self._headers(pending["token"])

        status = self.client.get(
            "/api/device-pending-status", headers=pending_headers
        )
        self.assertEqual(status.status_code, 200, status.json)
        self.assertEqual(status.json["trust_state"], "pending")
        self.assertEqual(status.json["challenge"], pending["challenge"])

        for method, path, body in (
            ("get", "/api/devices", None),
            ("get", "/api/key-directory", None),
            (
                "post",
                "/api/conversation",
                {"members": [self.username], "name": "+821011112222"},
            ),
        ):
            response = getattr(self.client, method)(
                path, headers=pending_headers, json=body
            )
            self.assertEqual(response.status_code, 403, (path, response.json))
            self.assertEqual(response.json["error"], "device approval required")

    def test_pending_registration_notifies_approved_socket_and_cannot_connect(self):
        approved_socket = socketio.test_client(app, auth={"token": self.first["token"]})
        self.assertTrue(approved_socket.is_connected())
        approved_socket.get_received()

        pending, _box, _key, _sig = self._new_pending(seed=31)
        events = [
            event
            for event in approved_socket.get_received()
            if event["name"] == "device_pending"
        ]
        self.assertEqual(len(events), 1, events)
        payload = events[0]["args"][0]
        self.assertEqual(payload["sid"], pending["sid"])
        self.assertEqual(
            payload["fingerprint"],
            store.get_device_by_sid(pending["sid"])["fingerprint"],
        )

        pending_socket = socketio.test_client(
            app, auth={"token": pending["token"]}
        )
        self.assertFalse(pending_socket.is_connected())
        approved_socket.disconnect()

    def test_approval_uses_exact_canonical_statement_and_rejects_tampering(self):
        pending, _box, _key, _sig = self._new_pending()
        valid_body, exact_statement = self._signed_approval(pending)
        expected = (
            "securemsg-device-approval-v1\n"
            f"uid={self.uid}\n"
            f"subject_sid={pending['sid']}\n"
            f"pub_key={store.get_device_by_sid(pending['sid'])['pub_key']}\n"
            f"sig_pub={store.get_device_by_sid(pending['sid'])['sig_pub']}\n"
            "kind=web\n"
            f"challenge={pending['challenge']}\n"
            f"parent_epoch={pending['security_epoch']}\n"
        )
        self.assertEqual(exact_statement, expected)

        tampered_body, _ = self._signed_approval(
            pending, statement_suffix="tampered=true\n"
        )
        rejected = self.client.post(
            "/api/device-approve", headers=self.first_headers, json=tampered_body
        )
        self.assertEqual(rejected.status_code, 403, rejected.json)
        self.assertEqual(rejected.json["error"], "invalid approval signature")

        accepted = self.client.post(
            "/api/device-approve", headers=self.first_headers, json=valid_body
        )
        self.assertEqual(accepted.status_code, 200, accepted.json)
        self.assertEqual(accepted.json["approved"], pending["sid"])

    def test_approval_rejects_stale_epoch_and_replay(self):
        pending, _box, _key, _sig = self._new_pending()
        stale_body, _ = self._signed_approval(
            pending, parent_epoch=pending["security_epoch"] - 1
        )
        stale = self.client.post(
            "/api/device-approve", headers=self.first_headers, json=stale_body
        )
        self.assertEqual(stale.status_code, 409, stale.json)
        self.assertEqual(stale.json["error"], "security epoch changed")

        valid_body, _ = self._signed_approval(pending)
        first = self.client.post(
            "/api/device-approve", headers=self.first_headers, json=valid_body
        )
        self.assertEqual(first.status_code, 200, first.json)
        replay = self.client.post(
            "/api/device-approve", headers=self.first_headers, json=valid_body
        )
        self.assertEqual(replay.status_code, 409, replay.json)
        self.assertEqual(replay.json["error"], "device is not pending")

    def test_revoke_signature_rejects_tamper_stale_epoch_and_replay(self):
        pending, _box, _key, _sig = self._new_pending(seed=37)
        self._approve(pending)

        tampered = self._signed_revoke(
            pending["sid"], statement_suffix="tampered=true\n"
        )
        rejected = self.client.post(
            "/api/device-revoke", headers=self.first_headers, json=tampered
        )
        self.assertEqual(rejected.status_code, 403, rejected.json)
        self.assertEqual(rejected.json["error"], "invalid revoke signature")

        current_epoch = store.get_user(self.uid)["security_epoch"]
        stale = self._signed_revoke(
            pending["sid"], parent_epoch=current_epoch - 1
        )
        rejected = self.client.post(
            "/api/device-revoke", headers=self.first_headers, json=stale
        )
        self.assertEqual(rejected.status_code, 409, rejected.json)
        self.assertEqual(rejected.json["error"], "security epoch changed")

        valid = self._signed_revoke(pending["sid"])
        accepted = self.client.post(
            "/api/device-revoke", headers=self.first_headers, json=valid
        )
        self.assertEqual(accepted.status_code, 200, accepted.json)
        replay = self.client.post(
            "/api/device-revoke", headers=self.first_headers, json=valid
        )
        self.assertEqual(replay.status_code, 409, replay.json)
        self.assertEqual(replay.json["error"], "device already revoked")

    def test_pending_is_excluded_from_recipient_keyset_until_approved(self):
        created = self.client.post(
            "/api/conversation",
            headers=self.first_headers,
            json={"members": [self.username], "name": "+821033344444"},
        )
        self.assertEqual(created.status_code, 200, created.json)
        cid = created.json["cid"]
        pending, _box, _key, _sig = self._new_pending()

        before = self.client.get(
            f"/api/conversation/{cid}/members", headers=self.first_headers
        )
        self.assertEqual(before.status_code, 200, before.json)
        self.assertEqual(
            {row["sid"] for row in before.json["members"]}, {self.first["sid"]}
        )
        self.assertIn("recipient_keyset_hash", before.json)
        self.assertTrue(before.json["directory_checkpoints"])

        self._approve(pending)
        after = self.client.get(
            f"/api/conversation/{cid}/members", headers=self.first_headers
        )
        self.assertEqual(after.status_code, 200, after.json)
        self.assertEqual(
            {row["sid"] for row in after.json["members"]},
            {self.first["sid"], pending["sid"]},
        )
        self.assertNotEqual(
            before.json["recipient_keyset_hash"],
            after.json["recipient_keyset_hash"],
        )

    def test_key_directory_is_one_read_snapshot_while_approval_commits(self):
        pending, _box, _key, _sig = self._new_pending()
        snapshot_pinned = threading.Event()
        resume_reader = threading.Event()
        reader_finished = threading.Event()
        original_get_proof = store._get_directory_proof_with_conn
        result: dict[str, object] = {}

        def pause_after_snapshot_query(connection, user_id, *, user=None):
            snapshot_pinned.set()
            if not resume_reader.wait(timeout=5):
                raise TimeoutError("reader snapshot was not resumed")
            return original_get_proof(connection, user_id, user=user)

        def read_directory():
            try:
                with app.test_client() as reader:
                    result["response"] = reader.get(
                        "/api/key-directory", headers=self.first_headers
                    )
            except BaseException as exc:  # surface thread failures in this test
                result["error"] = exc
            finally:
                reader_finished.set()

        with mock.patch.object(
            store,
            "_get_directory_proof_with_conn",
            side_effect=pause_after_snapshot_query,
        ):
            reader_thread = threading.Thread(target=read_directory, daemon=True)
            reader_thread.start()
            self.assertTrue(snapshot_pinned.wait(timeout=5))

            try:
                approved = self._approve(pending)
                self.assertEqual(approved["security_epoch"], 2)
                self.assertFalse(reader_finished.is_set())
            finally:
                resume_reader.set()
            reader_thread.join(timeout=5)

        self.assertFalse(reader_thread.is_alive())
        if "error" in result:
            raise result["error"]  # type: ignore[misc]
        before = result["response"]
        self.assertEqual(before.status_code, 200, before.json)
        self.assertEqual(before.json["security_epoch"], 1)
        self.assertEqual(
            {row["sid"] for row in before.json["devices"]}, {self.first["sid"]}
        )
        self.assertEqual(before.json["approval_certificates"], [])

        after = self.client.get("/api/key-directory", headers=self.first_headers)
        self.assertEqual(after.status_code, 200, after.json)
        self.assertEqual(after.json["security_epoch"], 2)
        self.assertEqual(
            {row["sid"] for row in after.json["devices"]},
            {self.first["sid"], pending["sid"]},
        )
        self.assertEqual(len(after.json["approval_certificates"]), 1)

    def test_conversation_members_is_one_snapshot_while_approval_commits(self):
        created = self.client.post(
            "/api/conversation",
            headers=self.first_headers,
            json={"members": [self.username], "name": "+821033355555"},
        )
        self.assertEqual(created.status_code, 200, created.json)
        cid = created.json["cid"]
        pending, _box, _key, _sig = self._new_pending(seed=31)
        snapshot_pinned = threading.Event()
        resume_reader = threading.Event()
        reader_finished = threading.Event()
        original_get_proof = store._get_directory_proof_with_conn
        result: dict[str, object] = {}

        def pause_after_snapshot_query(connection, user_id, *, user=None):
            if not snapshot_pinned.is_set():
                snapshot_pinned.set()
                if not resume_reader.wait(timeout=5):
                    raise TimeoutError("conversation snapshot was not resumed")
            return original_get_proof(connection, user_id, user=user)

        def read_members():
            try:
                with app.test_client() as reader:
                    result["response"] = reader.get(
                        f"/api/conversation/{cid}/members",
                        headers=self.first_headers,
                    )
            except BaseException as exc:  # surface thread failures in this test
                result["error"] = exc
            finally:
                reader_finished.set()

        with mock.patch.object(
            store,
            "_get_directory_proof_with_conn",
            side_effect=pause_after_snapshot_query,
        ):
            reader_thread = threading.Thread(target=read_members, daemon=True)
            reader_thread.start()
            self.assertTrue(snapshot_pinned.wait(timeout=5))

            try:
                approved = self._approve(pending)
                self.assertEqual(approved["security_epoch"], 2)
                self.assertFalse(reader_finished.is_set())
            finally:
                resume_reader.set()
            reader_thread.join(timeout=5)

        self.assertFalse(reader_thread.is_alive())
        if "error" in result:
            raise result["error"]  # type: ignore[misc]
        before = result["response"]
        self.assertEqual(before.status_code, 200, before.json)
        self.assertEqual(
            {row["sid"] for row in before.json["members"]}, {self.first["sid"]}
        )
        self.assertEqual(before.json["directory_checkpoints"][0]["security_epoch"], 1)
        self.assertEqual(before.json["directory_proofs"][0]["security_epoch"], 1)
        self.assertEqual(before.json["directory_proofs"][0]["approval_certificates"], [])

        after = self.client.get(
            f"/api/conversation/{cid}/members", headers=self.first_headers
        )
        self.assertEqual(after.status_code, 200, after.json)
        self.assertEqual(
            {row["sid"] for row in after.json["members"]},
            {self.first["sid"], pending["sid"]},
        )
        self.assertEqual(after.json["directory_checkpoints"][0]["security_epoch"], 2)
        self.assertEqual(after.json["directory_proofs"][0]["security_epoch"], 2)
        self.assertEqual(
            len(after.json["directory_proofs"][0]["approval_certificates"]), 1
        )

    def test_device_public_keys_are_database_immutable(self):
        with store.conn_ctx() as connection:
            with self.assertRaisesRegex(
                sqlite3.IntegrityError, "device public keys are immutable"
            ):
                connection.execute(
                    "UPDATE devices SET pub_key = ? WHERE sid = ?",
                    ("Z" * 43, self.first["sid"]),
                )
            with self.assertRaisesRegex(
                sqlite3.IntegrityError, "device public keys are immutable"
            ):
                connection.execute(
                    "UPDATE devices SET sig_pub = ? WHERE sid = ?",
                    ("Y" * 43, self.first["sid"]),
                )

    def test_device_fingerprint_has_a_fixed_domain_separated_golden_vector(self):
        self.assertEqual(
            store.device_fingerprint("A" * 43, "B" * 43),
            "RaJeHfMC3LRkpSSHdc32cYqJ0pn9VmkuJ8VmXDORQtI",
        )

    def test_pending_device_can_only_soft_revoke_itself(self):
        pending, _box, _key, _sig = self._new_pending(seed=53)
        pending_headers = self._headers(pending["token"])
        cancelled = self.client.post(
            "/api/device-pending-revoke", headers=pending_headers
        )
        self.assertEqual(cancelled.status_code, 200, cancelled.json)
        self.assertEqual(cancelled.json["revoked"], pending["sid"])
        self.assertEqual(
            store.get_device_by_sid(pending["sid"])["trust_state"], "revoked"
        )
        denied = self.client.get(
            "/api/device-pending-status", headers=pending_headers
        )
        self.assertEqual(denied.status_code, 401, denied.json)
        # The approved bootstrap device is untouched and remains usable.
        allowed = self.client.get("/api/devices", headers=self.first_headers)
        self.assertEqual(allowed.status_code, 200, allowed.json)
        first = next(
            row for row in allowed.json["devices"] if row["sid"] == self.first["sid"]
        )
        self.assertEqual(first["trust_state"], "approved")

    def test_last_approved_device_self_revoke_locks_account_without_rebootstrap(self):
        live_socket = socketio.test_client(
            app, auth={"token": self.first["token"]}
        )
        self.assertTrue(live_socket.is_connected())

        revoked = self.client.post(
            "/api/device-revoke",
            headers=self.first_headers,
            json=self._signed_revoke(self.first["sid"]),
        )
        self.assertEqual(revoked.status_code, 200, revoked.json)
        self.assertEqual(revoked.json["revoked"], self.first["sid"])
        tombstone = store.get_device_by_sid(self.first["sid"])
        self.assertIsNotNone(tombstone)
        self.assertEqual(
            tombstone["trust_state"], "revoked"
        )
        self.assertIsNotNone(tombstone["revoked_at"])
        self.assertEqual(tombstone["pub_key"], self.first_box)
        self.assertEqual(tombstone["sig_pub"], self.first_sig_public)
        denied = self.client.get("/api/devices", headers=self.first_headers)
        self.assertEqual(denied.status_code, 401, denied.json)

        live_socket.emit(
            "message_send", {"cid": "unused", "payload": {}}, callback=True
        )
        self.assertFalse(live_socket.is_connected())

        replacement_box, _replacement_signing, replacement_sig = _key_material(61)
        replacement = self._register_device(
            "replacement", replacement_box, replacement_sig
        )
        self.assertEqual(replacement["trust_state"], "pending")
        status = self.client.get(
            "/api/device-pending-status",
            headers=self._headers(replacement["token"]),
        )
        self.assertEqual(status.status_code, 200, status.json)
        self.assertEqual(status.json["trust_state"], "pending")
        self.assertEqual(
            [d["trust_state"] for d in store.list_user_devices(self.uid)],
            ["revoked", "pending"],
        )

    def test_approval_toctou_errors_have_stable_http_mapping(self):
        pending, _box, _key, _sig = self._new_pending(seed=57)
        body, _statement = self._signed_approval(pending)
        cases = (
            (PermissionError("approver is not approved"), 401, "approver is no longer approved"),
            (LookupError("device not found"), 404, "device not found"),
        )
        for error, status, message in cases:
            with self.subTest(error=type(error).__name__), mock.patch.object(
                store, "approve_pending_device", side_effect=error
            ):
                response = self.client.post(
                    "/api/device-approve", headers=self.first_headers, json=body
                )
            self.assertEqual(response.status_code, status, response.json)
            self.assertEqual(response.json["error"], message)

    def test_directory_proof_retains_cross_signature_and_revoked_approver(self):
        created = self.client.post(
            "/api/conversation",
            headers=self.first_headers,
            json={"members": [self.username], "name": "+821066677788"},
        )
        self.assertEqual(created.status_code, 200, created.json)
        pending, _box, second_signing, _sig = self._new_pending(seed=59)
        approval_body, expected_statement = self._signed_approval(pending)
        approved = self.client.post(
            "/api/device-approve", headers=self.first_headers, json=approval_body
        )
        self.assertEqual(approved.status_code, 200, approved.json)
        second_headers = self._headers(pending["token"])

        revoke_body = self._signed_revoke(
            self.first["sid"],
            actor_sid=pending["sid"],
            signing_key=second_signing,
        )
        expected_revoke_statement = revoke_statement(
            self.uid,
            store.get_device_by_sid(self.first["sid"]),
            pending["sid"],
            revoke_body["parent_epoch"],
        )
        revoked = self.client.post(
            "/api/device-revoke",
            headers=second_headers,
            json=revoke_body,
        )
        self.assertEqual(revoked.status_code, 200, revoked.json)
        members = self.client.get(
            f"/api/conversation/{created.json['cid']}/members",
            headers=second_headers,
        )
        self.assertEqual(members.status_code, 200, members.json)
        self.assertEqual(len(members.json["directory_proofs"]), 1)
        proof = members.json["directory_proofs"][0]
        history = {row["sid"]: row for row in proof["device_history"]}
        self.assertEqual(history[self.first["sid"]]["trust_state"], "revoked")
        self.assertEqual(history[pending["sid"]]["trust_state"], "approved")

        certificates = proof["approval_certificates"]
        self.assertEqual(len(certificates), 1, certificates)
        certificate = certificates[0]
        self.assertEqual(certificate["subject_sid"], pending["sid"])
        self.assertEqual(certificate["approver_sid"], self.first["sid"])
        self.assertEqual(certificate["statement"], expected_statement)
        self.assertEqual(certificate["signature"], approval_body["signature"])
        self.first_signing.verify_key.verify(
            certificate["statement"].encode("utf-8"),
            base64.urlsafe_b64decode(
                certificate["signature"]
                + "=" * (-len(certificate["signature"]) % 4)
            ),
        )

        revocations = proof["revocation_certificates"]
        self.assertEqual(len(revocations), 1, revocations)
        revocation = revocations[0]
        self.assertEqual(revocation["subject_sid"], self.first["sid"])
        self.assertEqual(revocation["actor_sid"], pending["sid"])
        self.assertEqual(revocation["reason"], "user_revoked")
        self.assertEqual(revocation["statement"], expected_revoke_statement)
        self.assertEqual(revocation["signature"], revoke_body["signature"])
        second_signing.verify_key.verify(
            revocation["statement"].encode("utf-8"),
            base64.urlsafe_b64decode(
                revocation["signature"]
                + "=" * (-len(revocation["signature"]) % 4)
            ),
        )

        directory = self.client.get("/api/key-directory", headers=second_headers)
        self.assertEqual(directory.status_code, 200, directory.json)
        self.assertEqual(
            directory.json["approval_certificates"], certificates
        )
        self.assertEqual(directory.json["revocation_certificates"], revocations)
        directory_history = {
            row["sid"]: row for row in directory.json["device_history"]
        }
        self.assertEqual(
            directory_history[self.first["sid"]]["trust_state"], "revoked"
        )

    def test_soft_revoke_preserves_tombstone_and_auditable_hash_chain(self):
        pending, _box, _key, _sig = self._new_pending()
        self._approve(pending)
        pending_headers = self._headers(pending["token"])
        revoked = self.client.post(
            "/api/device-revoke",
            headers=self.first_headers,
            json=self._signed_revoke(pending["sid"]),
        )
        self.assertEqual(revoked.status_code, 200, revoked.json)

        denied = self.client.get("/api/devices", headers=pending_headers)
        self.assertEqual(denied.status_code, 401, denied.json)
        listed = self.client.get("/api/devices", headers=self.first_headers)
        tombstone = next(
            row for row in listed.json["devices"] if row["sid"] == pending["sid"]
        )
        self.assertEqual(tombstone["trust_state"], "revoked")
        self.assertIsNotNone(tombstone["revoked_at"])
        self.assertTrue(tombstone["pub_key"])
        self.assertTrue(tombstone["sig_pub"])

        directory = self.client.get("/api/key-directory", headers=self.first_headers)
        self.assertNotIn(
            pending["sid"], {row["sid"] for row in directory.json["devices"]}
        )
        log = self.client.get("/api/security-log", headers=self.first_headers)
        self.assertEqual(log.status_code, 200, log.json)
        events = log.json["events"]
        self.assertEqual(
            [event["event_type"] for event in events],
            ["device_bootstrap", "device_pending", "device_approved", "device_revoked"],
        )
        previous_hash = ""
        for event in events:
            self.assertEqual(event["previous_hash"], previous_hash)
            expected_signature = _b64u(
                hmac.new(
                    config.JWT_SECRET.encode(),
                    event["event_hash"].encode(),
                    hashlib.sha256,
                ).digest()
            )
            self.assertTrue(
                hmac.compare_digest(expected_signature, event["server_signature"])
            )
            previous_hash = event["event_hash"]


class TrustedDeviceLegacyMigrationTest(unittest.TestCase):
    def test_legacy_devices_are_approved_and_oldest_signature_becomes_anchor(self):
        legacy_file = tempfile.NamedTemporaryFile(
            prefix="securemsg-trust-legacy-", suffix=".db", delete=False
        )
        legacy_file.close()
        legacy_path = Path(legacy_file.name)
        try:
            # sqlite3.Connection.__exit__ does not close the connection.
            with closing(sqlite3.connect(legacy_path)) as connection:
                connection.executescript(
                    """
                    CREATE TABLE users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL UNIQUE,
                        pw_hash TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    );
                    CREATE TABLE devices (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id INTEGER NOT NULL REFERENCES users(id),
                        sid TEXT NOT NULL UNIQUE,
                        name TEXT NOT NULL,
                        kind TEXT NOT NULL DEFAULT 'web',
                        pub_key TEXT NOT NULL,
                        sig_pub TEXT NOT NULL,
                        session_version INTEGER NOT NULL DEFAULT 1,
                        created_at INTEGER NOT NULL,
                        last_seen INTEGER NOT NULL
                    );
                    CREATE TABLE conversations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        cid TEXT NOT NULL UNIQUE,
                        name TEXT NOT NULL DEFAULT '',
                        created_at INTEGER NOT NULL
                    );
                    CREATE TABLE conversation_members (
                        conv_id INTEGER NOT NULL REFERENCES conversations(id),
                        user_id INTEGER NOT NULL REFERENCES users(id),
                        joined_at INTEGER NOT NULL,
                        PRIMARY KEY (conv_id, user_id)
                    );
                    CREATE TABLE messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        seq INTEGER NOT NULL,
                        conv_id INTEGER NOT NULL REFERENCES conversations(id),
                        sender_id INTEGER NOT NULL REFERENCES users(id),
                        sender_sid TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        UNIQUE (conv_id, seq)
                    );
                    CREATE TABLE delivery_cursors (
                        device_id INTEGER NOT NULL REFERENCES devices(id),
                        conv_id INTEGER NOT NULL REFERENCES conversations(id),
                        last_seq INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (device_id, conv_id)
                    );
                    INSERT INTO users VALUES (1, 'legacyuser', 'bcrypt', 1);
                    """
                )
                connection.commit()
                old_box, _old_key, old_sig = _key_material(41)
                new_box, _new_key, new_sig = _key_material(42)
                connection.execute(
                    "INSERT INTO devices VALUES (1,1,'legacy001','old','android_gateway',?,?,1,10,10)",
                    (old_box, old_sig),
                )
                connection.execute(
                    "INSERT INTO devices VALUES (2,1,'legacy002','new','android_gateway',?,?,1,20,20)",
                    (new_box, new_sig),
                )
                legacy_payload = '{"ct":"legacy-ciphertext","keys":{"legacy001":{"ek":"wrapped"}}}'
                connection.execute(
                    "INSERT INTO conversations VALUES (1,'legacy-cid','+821012345678',30)"
                )
                connection.execute(
                    "INSERT INTO conversation_members VALUES (1,1,30)"
                )
                connection.execute(
                    "INSERT INTO messages VALUES (1,7,1,1,'legacy001',?,40)",
                    (legacy_payload,),
                )
                connection.execute("INSERT INTO delivery_cursors VALUES (1,1,7)")
                connection.commit()

            with mock.patch.object(store, "DB_PATH", legacy_path):
                store.init_schema()
                third_box, _third_key, third_sig = _key_material(43)
                third_id = store.add_device(
                    1,
                    "legacy003",
                    "future-gateway",
                    third_box,
                    third_sig,
                    kind="android_gateway",
                )
                with store.conn_ctx() as connection:
                    user = connection.execute(
                        "SELECT identity_sig_pub, security_epoch, directory_hash, "
                        "trust_enforced_at FROM users WHERE id = 1"
                    ).fetchone()
                    devices = connection.execute(
                        "SELECT sid, kind, trust_state, approved_by_sid, fingerprint "
                        "FROM devices ORDER BY id"
                    ).fetchall()
                    preserved = connection.execute(
                        "SELECT cv.cid, cv.name, m.seq, m.payload, dc.last_seq "
                        "FROM conversations cv "
                        "JOIN messages m ON m.conv_id = cv.id "
                        "JOIN delivery_cursors dc ON dc.conv_id = cv.id "
                        "WHERE cv.id = 1"
                    ).fetchone()
                    trigger = connection.execute(
                        "SELECT name FROM sqlite_master WHERE type='trigger' "
                        "AND name='devices_keys_immutable'"
                    ).fetchone()
                    with self.assertRaisesRegex(
                        sqlite3.IntegrityError, "account identity key is immutable"
                    ):
                        connection.execute(
                            "UPDATE users SET identity_sig_pub = ? WHERE id = 1",
                            (new_sig,),
                        )
                    with self.assertRaisesRegex(
                        sqlite3.IntegrityError,
                        "approved Android gateway already exists",
                    ):
                        connection.execute(
                            "UPDATE devices SET trust_state = 'approved' WHERE id = ?",
                            (third_id,),
                        )
            self.assertEqual(user["identity_sig_pub"], old_sig)
            self.assertIsNotNone(user["trust_enforced_at"])
            self.assertTrue(user["directory_hash"])
            self.assertEqual(
                [row["kind"] for row in devices[:2]],
                ["android_gateway", "android_gateway"],
            )
            self.assertEqual(
                [row["trust_state"] for row in devices],
                ["approved", "pending", "pending"],
            )
            self.assertEqual(
                [row["approved_by_sid"] for row in devices],
                ["legacy_tofu", None, None],
            )
            self.assertTrue(all(row["fingerprint"] for row in devices))
            self.assertIsNotNone(trigger)
            self.assertEqual(
                tuple(preserved),
                (
                    "legacy-cid",
                    "+821012345678",
                    7,
                    legacy_payload,
                    7,
                ),
            )
        finally:
            for suffix in ("", "-wal", "-shm"):
                legacy_path.with_name(legacy_path.name + suffix).unlink(missing_ok=True)

    def test_legacy_anchor_upgrade_quarantines_then_cross_signs_peer(self):
        legacy_file = tempfile.NamedTemporaryFile(
            prefix="securemsg-upgrade-legacy-", suffix=".db", delete=False
        )
        legacy_file.close()
        legacy_path = Path(legacy_file.name)
        anchor_box, anchor_signing, anchor_sig = _key_material(71)
        peer_box, _peer_signing, peer_sig = _key_material(72)
        try:
            with closing(sqlite3.connect(legacy_path)) as connection:
                connection.executescript(
                    """
                    CREATE TABLE users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL UNIQUE,
                        pw_hash TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    );
                    CREATE TABLE devices (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id INTEGER NOT NULL REFERENCES users(id),
                        sid TEXT NOT NULL UNIQUE,
                        name TEXT NOT NULL,
                        kind TEXT NOT NULL DEFAULT 'web',
                        pub_key TEXT NOT NULL,
                        sig_pub TEXT NOT NULL,
                        session_version INTEGER NOT NULL DEFAULT 1,
                        created_at INTEGER NOT NULL,
                        last_seen INTEGER NOT NULL
                    );
                    INSERT INTO users VALUES (1, 'upgradelegacy', 'unused', 1);
                    """
                )
                connection.execute(
                    "INSERT INTO devices VALUES (1,1,'anchor001','anchor','web',?,?,1,10,10)",
                    (anchor_box, anchor_sig),
                )
                connection.execute(
                    "INSERT INTO devices VALUES (2,1,'legacy02','peer','web',?,?,1,20,20)",
                    (peer_box, peer_sig),
                )
                connection.commit()

            with mock.patch.object(store, "DB_PATH", legacy_path):
                store.init_schema()
                anchor = store.get_device_by_sid("anchor001")
                peer_before = store.get_device_by_sid("legacy02")
                self.assertEqual(anchor["security_mode"], "legacy_v1")
                self.assertEqual(peer_before["trust_state"], "approved")
                anchor_token = issue_jwt(1, "anchor001", anchor["session_version"])
                peer_old_token = issue_jwt(
                    1, "legacy02", peer_before["session_version"]
                )
                headers = {"Authorization": f"Bearer {anchor_token}"}
                epoch = anchor["security_epoch"]
                statement = legacy_upgrade_statement(
                    1, "anchor001", anchor_sig, epoch
                )
                signature = _b64u(
                    bytes(anchor_signing.sign(statement.encode("utf-8")).signature)
                )
                upgraded = app.test_client().post(
                    "/api/security-upgrade",
                    headers=headers,
                    json={"parent_epoch": epoch, "signature": signature},
                )
                self.assertEqual(upgraded.status_code, 200, upgraded.json)
                self.assertEqual(upgraded.json["security_mode"], "verified_v2")

                peer_pending = store.get_device_by_sid("legacy02")
                self.assertEqual(peer_pending["trust_state"], "pending")
                self.assertGreater(
                    peer_pending["session_version"], peer_before["session_version"]
                )
                denied = app.test_client().get(
                    "/api/devices",
                    headers={"Authorization": f"Bearer {peer_old_token}"},
                )
                self.assertEqual(denied.status_code, 401, denied.json)

                parent_epoch = upgraded.json["security_epoch"]
                approval = approval_statement(1, peer_pending, parent_epoch)
                approval_signature = _b64u(
                    bytes(
                        anchor_signing.sign(approval.encode("utf-8")).signature
                    )
                )
                reapproved = app.test_client().post(
                    "/api/device-approve",
                    headers=headers,
                    json={
                        "subject_sid": "legacy02",
                        "parent_epoch": parent_epoch,
                        "signature": approval_signature,
                    },
                )
                self.assertEqual(reapproved.status_code, 200, reapproved.json)
                self.assertEqual(
                    store.get_device_by_sid("legacy02")["trust_state"], "approved"
                )
        finally:
            for suffix in ("", "-wal", "-shm"):
                legacy_path.with_name(legacy_path.name + suffix).unlink(missing_ok=True)


def tearDownModule() -> None:
    if _bootstrap_path is not None:
        for suffix in ("", "-wal", "-shm"):
            _bootstrap_path.with_name(_bootstrap_path.name + suffix).unlink(
                missing_ok=True
            )
