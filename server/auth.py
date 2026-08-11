"""Auth: register, login (issue JWT), device registration, JWT verify.

Crypto notes:
- Server NEVER sees private keys. Client generates keypairs in-browser and only sends
  the public keys (X25519 + Ed25519) at device-register time.
- Password is hashed client-side with Argon2id via libsodium before transmission (so the
  server never sees the raw password either); then we bcrypt-hash it again server-side
  for storage defense in depth. Both layers are off the wire.
- JWT contains only {uid, sid, sv}. No PII. ``sv`` is a per-device revocation
  version checked against SQLite on every authenticated operation.
"""

from __future__ import annotations

import base64
import binascii
import re
import secrets
import sqlite3
import time
from functools import wraps

import bcrypt
import config
import jwt
import store
from nacl.exceptions import BadSignatureError
from nacl.signing import VerifyKey
from flask import Blueprint, g, jsonify, request
from rate_limit import check as rate_limit

bp = Blueprint("auth", __name__, url_prefix="/api")
USERNAME_RE = re.compile(r"[a-z0-9_]{3,20}", re.ASCII)
B64U_RE = re.compile(r"[A-Za-z0-9_-]+", re.ASCII)
SID_RE = re.compile(r"[A-Za-z0-9_-]{8,64}", re.ASCII)

# Precomputed bcrypt hash (same cost as real stored hashes) so credential checks
# spend identical work whether the username exists or not. Without this, a
# missing username skips bcrypt entirely and leaks account existence through a
# ~250ms response-time difference.
_DUMMY_PW_HASH = bcrypt.hashpw(
    b"securemsg-timing-equalizer", bcrypt.gensalt(rounds=12)
).decode("utf-8")


def _check_password(pw_hash: str, stored_hash: str | None) -> bool:
    """Always runs exactly one bcrypt verification (constant-work per attempt)."""
    target = stored_hash if stored_hash is not None else _DUMMY_PW_HASH
    try:
        ok = bcrypt.checkpw(pw_hash.encode("utf-8"), target.encode("utf-8"))
    except ValueError:
        return False  # malformed stored hash must not become a 500
    return ok and stored_hash is not None


def _ok(**kw) -> tuple:
    return jsonify({"ok": True, **kw})


def _err(msg: str, status: int = 400) -> tuple:
    return jsonify({"ok": False, "error": msg}), status


def _rate_error(retry_after: int) -> tuple:
    response = jsonify({"ok": False, "error": "too many requests"})
    response.headers["Retry-After"] = str(retry_after)
    return response, 429


def issue_jwt(uid: int, sid: str, session_version: int) -> str:
    payload = {
        "uid": uid,
        "sid": sid,
        "sv": session_version,
        "iat": int(time.time()),
        "exp": int(time.time()) + config.JWT_TTL_SECONDS,
    }
    return jwt.encode(payload, config.JWT_SECRET, algorithm=config.JWT_ALG)


def verify_jwt(token: str) -> dict | None:
    try:
        return jwt.decode(token, config.JWT_SECRET, algorithms=[config.JWT_ALG])
    except jwt.PyJWTError:
        return None


def _valid_client_hash(value: object) -> bool:
    """The clients send a URL-safe, unpadded 32-byte Argon2id result."""
    return isinstance(value, str) and _valid_b64u(value, 32)


def _valid_b64u(value: object, raw_length: int) -> bool:
    if not isinstance(value, str) or not B64U_RE.fullmatch(value):
        return False
    try:
        padded = value + "=" * (-len(value) % 4)
        decoded = base64.b64decode(padded, altchars=b"-_", validate=True)
    except (binascii.Error, ValueError):
        return False
    return len(decoded) == raw_length


def _text(body: dict, field: str) -> str:
    value = body.get(field)
    return value if isinstance(value, str) else ""


def _json_body() -> dict | None:
    body = request.get_json(silent=True)
    return body if isinstance(body, dict) else None


def _request_claims(*, missing_error: str) -> tuple[dict | None, tuple | None]:
    """Decode the bearer token once and normalize malformed JWT claims.

    Both authenticated decorators use the same claim validation. Keeping it in
    one place prevents subtle differences in how pending and approved devices
    interpret a token while retaining their distinct error messages.
    """
    header = request.headers.get("Authorization", "")
    if not header.startswith("Bearer "):
        return None, _err(missing_error, 401)
    decoded = verify_jwt(header[7:])
    if not decoded:
        return None, _err("invalid token", 401)
    try:
        claims = {
            "uid": int(decoded["uid"]),
            "sid": str(decoded["sid"]),
            "session_version": int(decoded["sv"]),
        }
    except (KeyError, TypeError, ValueError):
        return None, _err("invalid token", 401)
    return claims, None


def auth_required(fn):
    """Validate JWT and ensure its device has not since been revoked."""
    @wraps(fn)
    def wrapper(*args, **kwargs):
        claims, error = _request_claims(missing_error="missing bearer token")
        if error:
            return error
        assert claims is not None
        uid = claims["uid"]
        sid = claims["sid"]
        session_version = claims["session_version"]
        device = store.get_device_by_sid(sid)
        if (
            not device
            or device["user_id"] != uid
            or device["session_version"] != session_version
        ):
            return _err("device revoked or unknown", 401)
        if device["trust_state"] != "approved":
            return _err("device approval required", 403)
        g.auth = {
            "uid": uid,
            "sid": sid,
            "device_id": device["id"],
            "session_version": session_version,
        }
        return fn(*args, **kwargs)

    return wrapper


def pending_auth_required(fn):
    """Authenticate a device token without granting application access."""
    @wraps(fn)
    def wrapper(*args, **kwargs):
        claims, error = _request_claims(missing_error="invalid token")
        if error:
            return error
        assert claims is not None
        uid = claims["uid"]
        sid = claims["sid"]
        sv = claims["session_version"]
        device = store.get_device_by_sid(sid)
        if not device or device["user_id"] != uid or device["session_version"] != sv or device["trust_state"] == "revoked":
            return _err("device revoked or unknown", 401)
        g.auth = {"uid": uid, "sid": sid, "device_id": device["id"], "session_version": sv}
        g.device = device
        return fn(*args, **kwargs)

    return wrapper


# ----- endpoints --------------------------------------------------------


@bp.post("/register")
def register():
    """Body: { username, pw_hash }.
    `username` must match ^[a-z0-9_]{3,20}$ — opaque, NOT real name/email/phone.
    `pw_hash` is the Argon2id hash computed client-side over the user's password+salt.
    """
    body = _json_body()
    if body is None:
        return _err("JSON object required", 400)
    retry_after = rate_limit("register", "", 10, 60)
    if retry_after:
        return _rate_error(retry_after)
    username = _text(body, "username").strip().lower()
    pw_hash = _text(body, "pw_hash")

    if not USERNAME_RE.fullmatch(username):
        return _err("username must be 3-20 chars of [a-z0-9_]", 400)
    if not _valid_client_hash(pw_hash):
        return _err("pw_hash must be base64url for 32 bytes", 400)
    if store.get_user_by_name(username):
        return _err("username already taken", 409)

    # bcrypt over the already-client-hashed value (defense in depth). 12 rounds.
    server_hash = bcrypt.hashpw(
        pw_hash.encode("utf-8"), bcrypt.gensalt(rounds=12)
    ).decode("utf-8")
    try:
        uid = store.create_user(username, server_hash)
    except sqlite3.IntegrityError:
        return _err("username already taken", 409)
    return _ok(uid=uid, username=username)


@bp.post("/login")
def login():
    """Body: { username, pw_hash } -> { token }.
    `pw_hash` is the SAME client-side Argon2id hash used at register time.
    The server compares bcrypt(client_pw_hash, stored) — so a server db leak
    reveals only bcrypt-hashed client-hashes, not the password.
    """
    body = _json_body()
    if body is None:
        return _err("JSON object required", 400)
    username = _text(body, "username").strip().lower()
    retry_after = rate_limit("login", username, 20, 60)
    if retry_after:
        return _rate_error(retry_after)
    pw_hash = _text(body, "pw_hash")
    if not USERNAME_RE.fullmatch(username) or not _valid_client_hash(pw_hash):
        return _err("username and pw_hash required", 400)

    user = store.get_user_by_name(username)
    if not _check_password(pw_hash, user["pw_hash"] if user else None):
        return _err("invalid credentials", 401)

    # Token must be paired with a device. If user has devices, client picks one
    # via /device-login. If user has none, client must run /device-register first
    # (which is anonymous-ish — only requires the username to exist).
    user_devices = store.list_user_devices(user["id"])
    return _ok(
        uid=user["id"],
        username=user["username"],
        has_devices=any(d["trust_state"] != "revoked" for d in user_devices),
        has_approved_devices=any(d["trust_state"] == "approved" for d in user_devices),
        has_pending_devices=any(d["trust_state"] == "pending" for d in user_devices),
    )


@bp.post("/device-register")
def device_register():
    """Body: { username, pw_hash, device_name, pub_key, sig_pub } -> { sid, token }.
    Creates a new device for the authenticated user. The private keys never leave
    the browser; only pub_key (X25519) and sig_pub (Ed25519) are uploaded.
    """
    body = _json_body()
    if body is None:
        return _err("JSON object required", 400)
    username = _text(body, "username").strip().lower()
    retry_after = rate_limit("device-register", username, 20, 60)
    if retry_after:
        return _rate_error(retry_after)
    pw_hash = _text(body, "pw_hash")
    device_name = _text(body, "device_name").strip()[:40]
    pub_key = _text(body, "pub_key")
    sig_pub = _text(body, "sig_pub")
    device_kind = _text(body, "device_kind") or "web"

    if not (USERNAME_RE.fullmatch(username) and device_name):
        return _err("missing fields", 400)
    if not _valid_client_hash(pw_hash):
        return _err("pw_hash must be base64url for 32 bytes", 400)
    if not _valid_b64u(pub_key, 32) or not _valid_b64u(sig_pub, 32):
        return _err("pub_key/sig_pub must be base64url for 32 bytes", 400)
    if device_kind not in {"web", "android_gateway"}:
        return _err("invalid device_kind", 400)

    user = store.get_user_by_name(username)
    if not _check_password(pw_hash, user["pw_hash"] if user else None):
        return _err("invalid credentials", 401)
    sid = secrets.token_urlsafe(6)[:8]  # 8-char opaque device id
    try:
        store.add_device(
            user["id"],
            sid,
            device_name,
            pub_key,
            sig_pub,
            kind=device_kind,
            max_devices=config.MAX_DEVICES_PER_USER,
        )
    except ValueError as exc:
        if str(exc) == "device limit reached":
            return _err("device limit reached", 409)
        raise
    except sqlite3.IntegrityError:
        if device_kind == "android_gateway":
            return _err("an Android SMS gateway is already registered", 409)
        return _err("device registration conflict; retry", 409)
    device = store.get_device_by_sid(sid)
    token = issue_jwt(user["id"], sid, device["session_version"])
    if device["trust_state"] == "pending":
        # Import lazily to avoid the auth <-> sockets module cycle. Only
        # approved devices have versioned rooms, and the helper filters again.
        from sockets import emit_to_user_devices

        emit_to_user_devices(
            user["id"],
            "device_pending",
            {
                "sid": sid,
                "name": device["name"],
                "kind": device["kind"],
                "fingerprint": device["fingerprint"],
                "challenge": device["challenge"],
                "security_epoch": device["security_epoch"],
            },
            exclude_sid=sid,
        )
    return _ok(
        sid=sid,
        token=token,
        uid=user["id"],
        trust_state=device["trust_state"],
        challenge=device["challenge"],
        security_epoch=device["security_epoch"],
        directory_hash=device["directory_hash"],
        identity_sig_pub=device["identity_sig_pub"],
        security_mode=device["security_mode"],
    )


@bp.post("/device-login")
def device_login():
    """Body: { username, pw_hash, sid } -> { token }.
    Re-issues a JWT for an EXISTING device. Used when the browser kept the
    device's private key in IndexedDB but lost the JWT. The user proves
    knowledge of the password (via pw_hash) to get a fresh token for that sid.
    """
    body = _json_body()
    if body is None:
        return _err("JSON object required", 400)
    username = _text(body, "username").strip().lower()
    retry_after = rate_limit("device-login", username, 20, 60)
    if retry_after:
        return _rate_error(retry_after)
    pw_hash = _text(body, "pw_hash")
    sid = _text(body, "sid")
    if not (
        USERNAME_RE.fullmatch(username)
        and _valid_client_hash(pw_hash)
        and SID_RE.fullmatch(sid)
    ):
        return _err("missing fields", 400)

    user = store.get_user_by_name(username)
    if not _check_password(pw_hash, user["pw_hash"] if user else None):
        return _err("invalid credentials", 401)
    dev = store.get_device_by_sid(sid)
    if not dev or dev["user_id"] != user["id"]:
        return _err("device not found", 404)
    if dev["trust_state"] == "revoked":
        return _err("device revoked", 403)
    session_version = store.rotate_device_session(dev["id"], user["id"])
    if session_version is None:
        return _err("device not found", 404)
    token = issue_jwt(user["id"], sid, session_version)
    return _ok(sid=sid, token=token, uid=user["id"], trust_state=dev["trust_state"])


@bp.post("/logout")
@auth_required
def logout():
    """Revoke all JWTs from the current device without deleting its keypair."""
    session_version = store.rotate_device_session(
        g.auth["device_id"],
        g.auth["uid"],
        expected_version=g.auth["session_version"],
    )
    if session_version is None:
        return _err("invalid token", 401)
    return _ok(logged_out=True)


@bp.get("/devices")
@auth_required
def devices_list():
    """List current user's devices. Auth required."""
    devs = store.list_user_devices(g.auth["uid"])
    user = store.get_user(g.auth["uid"])
    return _ok(
        security_epoch=user["security_epoch"],
        directory_hash=user["directory_hash"],
        identity_sig_pub=user["identity_sig_pub"],
        security_mode=user["security_mode"],
        devices=[
            {
                "sid": d["sid"],
                "name": d["name"],
                "kind": d["kind"],
                "pub_key": d["pub_key"],
                "sig_pub": d["sig_pub"],
                "fingerprint": d["fingerprint"],
                "trust_state": d["trust_state"],
                # The registration challenge is certificate material: clients
                # need it later to verify the persisted approval signature,
                # including for revoked tombstones. It is a public nonce.
                "challenge": d["challenge"],
                "approved_by_sid": d["approved_by_sid"],
                "approved_at": d["approved_at"],
                "revoked_at": d["revoked_at"],
                "verification_state": d["verification_state"],
                "created_at": d["created_at"],
                "last_seen": d["last_seen"],
            }
            for d in devs
        ]
    )


@bp.post("/device-revoke")
@auth_required
def device_revoke():
    body = _json_body()
    if body is None:
        return _err("JSON object required", 400)
    sid = _text(body, "sid")
    signature = _text(body, "signature")
    reason = _text(body, "reason")
    parent_epoch = body.get("parent_epoch")
    if (
        not SID_RE.fullmatch(sid)
        or not _valid_b64u(signature, 64)
        or reason != "user_revoked"
        or not isinstance(parent_epoch, int)
        or isinstance(parent_epoch, bool)
        or parent_epoch < 0
    ):
        return _err("sid, parent_epoch, user_revoked reason and Ed25519 signature required", 400)
    dev = store.get_device_by_sid(sid)
    if not dev or dev["user_id"] != g.auth["uid"]:
        return _err("device not found", 404)
    actor = store.get_device_by_sid(g.auth["sid"])
    statement = revoke_statement(g.auth["uid"], dev, g.auth["sid"], parent_epoch)
    try:
        VerifyKey(_decode_b64u(actor["sig_pub"])).verify(
            statement.encode(), _decode_b64u(signature)
        )
    except (BadSignatureError, ValueError):
        return _err("invalid revoke signature", 403)
    try:
        changed = store.revoke_device(
            dev["id"],
            g.auth["uid"],
            g.auth["sid"],
            g.auth["session_version"],
            parent_epoch,
            statement,
            signature,
        )
    except PermissionError:
        return _err("actor is no longer approved", 401)
    except ValueError as exc:
        return _err(str(exc), 409)
    except RuntimeError:
        return _err("security epoch changed", 409)
    if not changed:
        return _err("device already revoked", 409)
    from sockets import emit_to_user_devices

    refreshed = store.get_user(g.auth["uid"])
    emit_to_user_devices(
        g.auth["uid"],
        "device_revoked",
        {
            "sid": sid,
            "security_epoch": refreshed["security_epoch"],
            "directory_hash": refreshed["directory_hash"],
        },
    )
    return _ok(revoked=sid)


def revoke_statement(uid: int, subject: dict, actor_sid: str, parent_epoch: int) -> str:
    return (
        "securemsg-device-revoke-v1\n"
        f"uid={uid}\n"
        f"subject_sid={subject['sid']}\n"
        f"subject_pub_key={subject['pub_key']}\n"
        f"subject_sig_pub={subject['sig_pub']}\n"
        f"actor_sid={actor_sid}\n"
        f"parent_epoch={parent_epoch}\n"
        "reason=user_revoked\n"
    )


@bp.post("/device-pending-revoke")
@pending_auth_required
def pending_device_revoke():
    """Cancel only the pending device represented by this bearer token."""
    device = store.get_device_by_sid(g.auth["sid"])
    if device["trust_state"] != "pending":
        return _err("device is not pending", 409)
    if not store.cancel_pending_device(
        device["id"], g.auth["uid"], g.auth["sid"], g.auth["session_version"]
    ):
        return _err("device is no longer pending", 409)
    from sockets import emit_to_user_devices

    user = store.get_user(g.auth["uid"])
    emit_to_user_devices(
        g.auth["uid"],
        "device_revoked",
        {
            "sid": g.auth["sid"],
            "security_epoch": user["security_epoch"],
            "directory_hash": user["directory_hash"],
        },
    )
    return _ok(revoked=g.auth["sid"])


@bp.post("/device-reject-pending")
@auth_required
def reject_pending_device():
    body = _json_body()
    if body is None:
        return _err("JSON object required", 400)
    sid = _text(body, "sid")
    challenge = _text(body, "challenge")
    parent_epoch = body.get("parent_epoch")
    if not SID_RE.fullmatch(sid) or not _valid_b64u(challenge, 32) or not isinstance(parent_epoch, int) or isinstance(parent_epoch, bool) or parent_epoch < 0:
        return _err("sid, challenge and parent_epoch required", 400)
    try:
        changed = store.reject_pending_device(
            g.auth["uid"], g.auth["sid"], g.auth["session_version"], sid, challenge, parent_epoch
        )
    except PermissionError:
        return _err("actor is no longer approved", 401)
    except RuntimeError:
        return _err("security epoch changed", 409)
    if not changed:
        return _err("pending device or challenge not found", 404)
    return _ok(rejected=sid)


@bp.get("/device-pending-status")
@pending_auth_required
def pending_status():
    device = store.get_device_by_sid(g.auth["sid"])
    return _ok(
        sid=device["sid"],
        trust_state=device["trust_state"],
        challenge=device["challenge"],
        fingerprint=device["fingerprint"],
        security_epoch=device["security_epoch"],
        directory_hash=device["directory_hash"],
        identity_sig_pub=device["identity_sig_pub"],
        security_mode=device["security_mode"],
    )


def approval_statement(uid: int, subject: dict, parent_epoch: int) -> str:
    return (
        "securemsg-device-approval-v1\n"
        f"uid={uid}\n"
        f"subject_sid={subject['sid']}\n"
        f"pub_key={subject['pub_key']}\n"
        f"sig_pub={subject['sig_pub']}\n"
        f"kind={subject['kind']}\n"
        f"challenge={subject['challenge']}\n"
        f"parent_epoch={parent_epoch}\n"
    )


def legacy_upgrade_statement(uid: int, identity_sid: str, identity_sig_pub: str, parent_epoch: int) -> str:
    return (
        "securemsg-legacy-upgrade-v1\n"
        f"uid={uid}\n"
        f"identity_sid={identity_sid}\n"
        f"identity_sig_pub={identity_sig_pub}\n"
        f"parent_epoch={parent_epoch}\n"
    )


@bp.post("/security-upgrade")
@auth_required
def security_upgrade():
    body = _json_body()
    if body is None:
        return _err("JSON object required", 400)
    parent_epoch = body.get("parent_epoch")
    signature = _text(body, "signature")
    if not isinstance(parent_epoch, int) or isinstance(parent_epoch, bool) or parent_epoch < 0 or not _valid_b64u(signature, 64):
        return _err("parent_epoch and Ed25519 signature required", 400)
    device = store.get_device_by_sid(g.auth["sid"])
    statement = legacy_upgrade_statement(g.auth["uid"], device["sid"], device["sig_pub"], parent_epoch)
    try:
        VerifyKey(_decode_b64u(device["sig_pub"])).verify(statement.encode(), _decode_b64u(signature))
    except (BadSignatureError, ValueError):
        return _err("invalid upgrade signature", 403)
    try:
        result = store.upgrade_legacy_security(g.auth["uid"], g.auth["sid"], g.auth["session_version"], parent_epoch, statement, signature)
    except PermissionError:
        return _err("legacy identity anchor required", 403)
    except ValueError:
        return _err("account is not legacy", 409)
    except RuntimeError:
        return _err("security epoch changed", 409)
    return _ok(security_mode="verified_v2", **result)


@bp.post("/device-approve")
@auth_required
def device_approve():
    body = _json_body()
    if body is None:
        return _err("JSON object required", 400)
    subject_sid = _text(body, "subject_sid")
    signature = _text(body, "signature")
    parent_epoch = body.get("parent_epoch")
    if not SID_RE.fullmatch(subject_sid) or not _valid_b64u(signature, 64) or not isinstance(parent_epoch, int) or isinstance(parent_epoch, bool) or parent_epoch < 0:
        return _err("subject_sid, parent_epoch and Ed25519 signature required", 400)
    subject = store.get_device_by_sid(subject_sid)
    if not subject or subject["user_id"] != g.auth["uid"]:
        return _err("device not found", 404)
    if subject["trust_state"] != "pending":
        return _err("device is not pending", 409)
    user = store.get_user(g.auth["uid"])
    if int(user["security_epoch"]) != parent_epoch:
        return _err("security epoch changed", 409)
    approver = store.get_device_by_sid(g.auth["sid"])
    statement = approval_statement(g.auth["uid"], subject, parent_epoch)
    try:
        VerifyKey(_decode_b64u(approver["sig_pub"])).verify(statement.encode(), _decode_b64u(signature))
    except (BadSignatureError, ValueError):
        return _err("invalid approval signature", 403)
    try:
        result = store.approve_pending_device(
            g.auth["uid"],
            g.auth["sid"],
            subject_sid,
            parent_epoch,
            statement,
            signature,
            g.auth["session_version"],
        )
    except sqlite3.IntegrityError:
        return _err("an Android SMS gateway is already approved", 409)
    except PermissionError:
        return _err("approver is no longer approved", 401)
    except LookupError:
        return _err("device not found", 404)
    except ValueError:
        return _err("device is not pending", 409)
    except RuntimeError:
        return _err("security epoch changed", 409)
    from sockets import emit_to_user_devices

    emit_to_user_devices(
        g.auth["uid"],
        "device_approved",
        {"sid": subject_sid, **result},
    )
    return _ok(approved=subject_sid, **result)


def _decode_b64u(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


@bp.get("/key-directory")
@auth_required
def key_directory():
    return _ok(**store.get_key_directory(g.auth["uid"]))


@bp.get("/security-log")
@auth_required
def security_log():
    try:
        limit = int(request.args.get("limit", "200"))
        before_raw = request.args.get("before_id")
        before_id = int(before_raw) if before_raw is not None else None
    except ValueError:
        return _err("limit and before_id must be integers", 400)
    if not 1 <= limit <= 1000 or (before_id is not None and before_id <= 0):
        return _err("limit must be 1-1000 and before_id positive", 400)
    events = store.list_security_events(g.auth["uid"], limit, before_id)
    older_than = events[0]["id"] if events else (before_id or 1)
    older_count = store.count_security_events_before(g.auth["uid"], older_than)
    return _ok(
        events=events,
        anchor_previous_hash=events[0]["previous_hash"] if events else "",
        has_more=older_count > 0,
        next_before_id=older_than if older_count > 0 else None,
    )
