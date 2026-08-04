"""SocketIO event handlers.

Real-time flow:
1. Client connects with JWT in auth payload.
2. Server validates JWT and joins the device room `device:<sid>`.
3. On `message_send` the client already did envelope encryption: it sends
   {cid, payload} where payload.keys maps device_sid -> {ek, n}.
4. Server:
   - Verifies sender is a member of the conversation.
   - Inserts payload, gets (msg_id, seq).
   - Fans out once to each current member device's `device:<sid>` room.
5. Client emits `message_delivered` {cid, seq} when it has stored locally.
   Server updates delivery cursor. (Optional; not required for E2E security.)
"""

from __future__ import annotations

import base64
import binascii
import json
import logging
import re
import time

import config
import store
from auth import verify_jwt
from flask import request
from flask_socketio import SocketIO, join_room
from flask_socketio import disconnect as disconnect_client
from rate_limit import check as rate_limit

log = logging.getLogger("securemsg.sockets")
B64U_RE = re.compile(r"[A-Za-z0-9_-]+", re.ASCII)

_socketio_ref: SocketIO | None = None


def emit_to_user_devices(
    user_id: int, event: str, data: dict, exclude_sid: str | None = None
) -> None:
    """Fan an event out to every connected device of a user except one.

    Used by REST mutations (shared block rules, conversation rename) so the
    other devices of the same account refresh without waiting for a poll.
    """
    if _socketio_ref is None:
        return
    for sid in store.list_user_device_sids(user_id):
        if sid == exclude_sid:
            continue
        _socketio_ref.emit(event, data, to=f"device:{sid}")


def emit_to_conv_members(conv_id: int, event: str, data: dict) -> None:
    if _socketio_ref is None:
        return
    for user_id in store.list_member_user_ids(conv_id):
        emit_to_user_devices(user_id, event, data)


def attach_socketio(app, socketio: SocketIO) -> None:
    global _socketio_ref
    _socketio_ref = socketio
    clients: dict[str, tuple[int, str, int, int]] = {}

    def current_client() -> tuple[int, str, int] | None:
        record = clients.get(request.sid)
        if not record:
            return None
        uid, sid, device_id, expires_at = record
        if expires_at <= int(time.time()):
            clients.pop(request.sid, None)
            disconnect_client()
            return None
        device = store.get_device_by_sid(sid)
        if not device or device["user_id"] != uid or device["id"] != device_id:
            clients.pop(request.sid, None)
            # Same contract as the expiry branch above: a revoked/unknown device
            # must not keep an idle socket alive (README: revoked sockets are
            # terminated; clients keep their local keys and re-login).
            disconnect_client()
            return None
        return uid, sid, device_id

    def valid_b64u(value: object, raw_length: int | None = None) -> bool:
        if not isinstance(value, str) or not B64U_RE.fullmatch(value):
            return False
        try:
            decoded = base64.b64decode(
                value + "=" * (-len(value) % 4),
                altchars=b"-_",
                validate=True,
            )
        except (binascii.Error, ValueError):
            return False
        return raw_length is None or len(decoded) == raw_length

    @socketio.on("connect")
    def _connect(auth=None):
        auth = auth if isinstance(auth, dict) else {}
        header = request.headers.get("Authorization", "")
        token = auth.get("token") or (
            header[7:] if header.startswith("Bearer ") else None
        )
        if not token:
            raise ConnectionRefusedError("auth required")
        decoded = verify_jwt(token)
        if not decoded:
            raise ConnectionRefusedError("invalid token")
        try:
            uid, sid, expires_at = (
                int(decoded["uid"]),
                str(decoded["sid"]),
                int(decoded["exp"]),
            )
        except (KeyError, TypeError, ValueError):
            raise ConnectionRefusedError("invalid token")
        dev = store.get_device_by_sid(sid)
        if not dev or dev["user_id"] != uid:
            raise ConnectionRefusedError("device unknown")
        store.touch_device(dev["id"])
        clients[request.sid] = (uid, sid, dev["id"], expires_at)
        join_room(f"device:{sid}")
        log.info("connect uid=%s sid=%s", uid, sid)

    @socketio.on("disconnect")
    def _disconnect():
        client = clients.pop(request.sid, None)
        uid, sid = (client[0], client[1]) if client else (None, None)
        log.info("disconnect uid=%s sid=%s", uid, sid)

    @socketio.on("message_send")
    def _message_send(data: dict):
        """Client sends {cid, payload}. Server stores + fans out.
        `payload` is opaque encrypted envelope JSON; server does not inspect it
        beyond structural checks (must have `ct`, `nonce`, `keys`).
        """
        client = current_client()
        if not client:
            return {"ok": False, "error": "unauthenticated"}
        uid, sid, _device_id = client
        retry_after = rate_limit("message-send", sid, 120, 60)
        if retry_after:
            return {
                "ok": False,
                "error": "too many messages",
                "retry_after": retry_after,
            }

        if not isinstance(data, dict):
            return {"ok": False, "error": "invalid event body"}
        cid = data.get("cid")
        client_mid = data.get("mid")
        payload = data.get("payload")
        if (
            not isinstance(cid, str)
            or not (1 <= len(cid) <= 64)
            or not isinstance(payload, dict)
        ):
            return {"ok": False, "error": "cid and payload required"}
        if client_mid is not None and (
            not isinstance(client_mid, str)
            or not re.fullmatch(r"[A-Za-z0-9_-]{16,64}", client_mid, re.ASCII)
        ):
            return {"ok": False, "error": "invalid message id"}
        if not ({"ct", "nonce", "keys"} <= set(payload.keys())):
            return {"ok": False, "error": "payload must have ct, nonce, keys"}
        if not isinstance(payload["keys"], dict) or not payload["keys"]:
            return {"ok": False, "error": "payload.keys must be a non-empty dict"}
        try:
            encoded_payload = json.dumps(
                payload, separators=(",", ":"), ensure_ascii=False
            )
        except (TypeError, ValueError):
            return {"ok": False, "error": "payload must be JSON serializable"}
        if len(encoded_payload.encode("utf-8")) > config.MAX_ENVELOPE_BYTES:
            return {"ok": False, "error": "payload too large"}
        if not valid_b64u(payload["ct"]) or not valid_b64u(payload["nonce"], 24):
            return {"ok": False, "error": "invalid ciphertext or nonce"}

        conv = store.get_conversation_by_cid(cid)
        if not conv:
            return {"ok": False, "error": "conversation not found"}
        members = store.list_members(conv["id"])
        if not any(d["user_id"] == uid for d in members):
            return {"ok": False, "error": "forbidden"}
        if client_mid:
            existing = store.get_message_by_sender_mid(sid, client_mid)
            if existing:
                if int(existing["conv_id"]) != int(conv["id"]):
                    return {
                        "ok": False,
                        "error": "message id already used in another conversation",
                    }
                return {
                    "ok": True,
                    "seq": int(existing["seq"]),
                    "id": int(existing["id"]),
                    "duplicate": True,
                }
        member_sids = {d["sid"] for d in members}
        if set(payload["keys"]) != member_sids:
            return {
                "ok": False,
                "error": "payload keys do not match conversation devices",
            }
        for wrapped in payload["keys"].values():
            if (
                not isinstance(wrapped, dict)
                or not valid_b64u(wrapped.get("ek"), 48)
                or not valid_b64u(wrapped.get("n"), 24)
            ):
                return {"ok": False, "error": "invalid wrapped key"}

        sender_device = store.get_device_by_sid(sid)
        if not sender_device or sender_device["user_id"] != uid:
            return {"ok": False, "error": "sender device is revoked or unknown"}
        created_at = store.now()
        try:
            msg_id, seq, inserted = store.insert_message(
                conv_id=conv["id"],
                sender_id=uid,
                sender_sid=sid,
                payload=encoded_payload,
                client_mid=client_mid,
                expected_recipient_sids=member_sids,
                created_at=created_at,
            )
        except ValueError as exc:
            return {"ok": False, "error": str(exc)}
        if not inserted:
            return {"ok": True, "seq": seq, "id": msg_id, "duplicate": True}
        envelope = {
            "id": msg_id,
            "seq": seq,
            "cid": cid,
            "conv_id": conv["id"],
            "sender_id": uid,
            "sender_sid": sid,
            "sender_pub_key": sender_device["pub_key"],
            "payload": payload,
            # Must be the stored row's timestamp, not a fresh now(): history pull
            # returns the stored value and clients reconcile live events against it.
            "created_at": created_at,
        }
        # Fan out exactly once per registered device. Sending repeatedly to a
        # shared user room would duplicate carrier SMS when a user has 2+ devices.
        for device in members:
            socketio.emit("message_new", envelope, to=f"device:{device['sid']}")
        return {"ok": True, "seq": seq, "id": msg_id}

    @socketio.on("message_delivered")
    def _message_delivered(data: dict):
        """Client acks {cid, seq}. We bump the per-device delivery cursor so
        a reconnecting client can pull from this point onward. The server learns
        only the seq number — no message content."""
        client = current_client()
        if not client or not isinstance(data, dict):
            return
        uid, sid, device_id = client
        cid = data.get("cid")
        try:
            seq = int(data.get("seq") or 0)
        except (TypeError, ValueError):
            return
        if seq <= 0:
            return
        conv = store.get_conversation_by_cid(cid)
        if not conv:
            return
        members = store.list_members(conv["id"])
        if not any(member["user_id"] == uid for member in members):
            return
        if seq > store.max_seq(conv["id"]):
            return
        dev = store.get_device_by_sid(sid)
        if not dev or dev["user_id"] != uid or dev["id"] != device_id:
            return
        store.set_cursor(dev["id"], conv["id"], seq)

    @socketio.on("carrier_status")
    def _carrier_status(data: dict):
        """Android gateway reports carrier dispatch/delivery for one relay row.

        This event contains only metadata. The gateway role and conversation
        membership are checked before the status is accepted and fanned out.
        """
        client = current_client()
        if not client or not isinstance(data, dict):
            return {"ok": False, "error": "unauthenticated"}
        uid, sid, device_id = client
        device = store.get_device_by_sid(sid)
        if not device or device["id"] != device_id or device["user_id"] != uid:
            return {"ok": False, "error": "device revoked or unknown"}
        if device.get("kind") != "android_gateway":
            return {"ok": False, "error": "Android gateway required"}
        cid = data.get("cid")
        try:
            seq = int(data.get("seq") or 0)
        except (TypeError, ValueError):
            return {"ok": False, "error": "invalid sequence"}
        status = data.get("status")
        raw_error = data.get("error")
        if raw_error is not None and not isinstance(raw_error, str):
            return {"ok": False, "error": "invalid carrier error"}
        error = raw_error[:300] if isinstance(raw_error, str) else None
        if not isinstance(cid, str) or not (1 <= len(cid) <= 64):
            return {"ok": False, "error": "invalid conversation"}
        if seq <= 0 or not isinstance(status, str):
            return {"ok": False, "error": "invalid status"}
        conv = store.get_conversation_by_cid(cid)
        if not conv:
            return {"ok": False, "error": "conversation not found"}
        members = store.list_members(conv["id"])
        if not any(member["user_id"] == uid for member in members):
            return {"ok": False, "error": "forbidden"}
        if not any(member["sid"] == sid for member in members):
            return {"ok": False, "error": "gateway is not a conversation member"}
        try:
            result = store.update_carrier_status(conv["id"], seq, status, error)
        except ValueError as exc:
            return {"ok": False, "error": str(exc)}
        if result is None:
            return {"ok": False, "error": "message not found"}
        event = {
            "cid": cid,
            "seq": seq,
            "carrier_status": result["status"],
            "carrier_error": result["error"],
            "carrier_updated_at": result["updated_at"],
        }
        for member in members:
            socketio.emit("message_status", event, to=f"device:{member['sid']}")
        return {"ok": True, **event}

    @socketio.on("typing")
    def _typing(data: dict):
        """Lightweight presence: {cid, is_typing}. Forwarded to every conversation
        device except the sending socket's own device. Skipping the whole sender
        user would silence typing entirely in self-only SMS conversations, where
        the user's OTHER devices are the only recipients."""
        client = current_client()
        if not client or not isinstance(data, dict):
            return
        uid, sid, _device_id = client
        cid = data.get("cid")
        if not isinstance(cid, str) or not (1 <= len(cid) <= 64):
            return
        if not isinstance(data.get("is_typing"), bool):
            return
        if rate_limit("typing", sid, 120, 60):
            return
        is_typing = data["is_typing"]
        conv = store.get_conversation_by_cid(cid) if cid else None
        if not conv:
            return
        members = store.list_members(conv["id"])
        if not any(d["user_id"] == uid for d in members):
            return
        for device in members:
            if device["sid"] == sid:
                continue
            socketio.emit(
                "typing",
                {"cid": cid, "user_id": uid, "is_typing": is_typing},
                to=f"device:{device['sid']}",
            )
