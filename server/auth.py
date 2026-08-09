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

import bcrypt
import config
import jwt
import store
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


def auth_required(fn):
    """Validate JWT and ensure its device has not since been revoked."""
    from functools import wraps

    @wraps(fn)
    def wrapper(*args, **kwargs):
        header = request.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            return _err("missing bearer token", 401)
        decoded = verify_jwt(header[7:])
        if not decoded:
            return _err("invalid token", 401)
        try:
            uid = int(decoded["uid"])
            sid = str(decoded["sid"])
            session_version = int(decoded["sv"])
        except (KeyError, TypeError, ValueError):
            return _err("invalid token", 401)
        device = store.get_device_by_sid(sid)
        if (
            not device
            or device["user_id"] != uid
            or device["session_version"] != session_version
        ):
            return _err("device revoked or unknown", 401)
        g.auth = {
            "uid": uid,
            "sid": sid,
            "device_id": device["id"],
            "session_version": session_version,
        }
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
    return _ok(
        uid=user["id"],
        username=user["username"],
        has_devices=bool(store.list_user_devices(user["id"])),
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
    return _ok(sid=sid, token=token, uid=user["id"])


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
    session_version = store.rotate_device_session(dev["id"], user["id"])
    if session_version is None:
        return _err("device not found", 404)
    token = issue_jwt(user["id"], sid, session_version)
    return _ok(sid=sid, token=token, uid=user["id"])


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
    return _ok(
        devices=[
            {
                "sid": d["sid"],
                "name": d["name"],
                "kind": d["kind"],
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
    if not SID_RE.fullmatch(sid):
        return _err("sid required", 400)
    dev = store.get_device_by_sid(sid)
    if not dev or dev["user_id"] != g.auth["uid"]:
        return _err("device not found", 404)
    store.revoke_device(dev["id"], g.auth["uid"])
    return _ok(revoked=sid)
