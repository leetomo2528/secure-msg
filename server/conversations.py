"""REST endpoints for conversations and message history (offline pull).

Note on privacy: these endpoints never return plaintext. `payload` is the encrypted
envelope the server stored; the client decrypts it using the per-device key held
locally in IndexedDB.
"""

from __future__ import annotations

import re
import secrets
import unicodedata
import base64
import hashlib
import json

import store
from auth import _err, _ok, auth_required
from flask import Blueprint, g, jsonify, request
from rate_limit import check as rate_limit
from sockets import emit_to_conv_members, emit_to_user_devices

bp = Blueprint("conv", __name__, url_prefix="/api")
USERNAME_RE = re.compile(r"[a-z0-9_]{3,20}", re.ASCII)
PHONE_RE = re.compile(r"\+?[0-9*#]{3,24}", re.ASCII)


@bp.post("/conversation")
@auth_required
def create_conversation():
    """Body: { members: [username, ...], name?: str } -> { cid, members }.
    The conversation id is a random opaque token. `name` is a display label
    (e.g. phone number for SMS bridge conversations).
    """
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        return _err("JSON object required", 400)
    retry_after = rate_limit("conversation-create", g.auth["sid"], 30, 60)
    if retry_after:
        response = jsonify({"ok": False, "error": "too many requests"})
        response.headers["Retry-After"] = str(retry_after)
        return response, 429
    members = body.get("members") or []
    raw_name = body.get("name", "")
    if not isinstance(raw_name, str):
        return _err("name must be a string", 400)
    name = raw_name.strip()[:100]
    if not isinstance(members, list) or not (1 <= len(members) <= 50):
        return _err("members must be a non-empty list of usernames", 400)

    if not all(isinstance(uname, str) for uname in members):
        return _err("invalid member username", 400)

    # Resolve member ids. Current user is implicitly a member.
    resolved = []
    for uname in dict.fromkeys(members):
        if not USERNAME_RE.fullmatch(uname):
            return _err("invalid member username", 400)
        u = store.get_user_by_name(uname)
        if not u:
            return _err(f"unknown user: {uname}", 404)
        resolved.append(u["id"])

    cid = secrets.token_urlsafe(9)  # opaque conversation id
    unique_user_ids = list(dict.fromkeys([g.auth["uid"], *resolved]))
    if len(unique_user_ids) == 1 and PHONE_RE.fullmatch(name):
        conv_id, cid, created = store.get_or_create_single_member_conversation(
            cid,
            name,
            g.auth["uid"],
        )
        return _ok(
            cid=cid,
            conv_id=conv_id,
            members=members,
            name=name,
            created=created,
        )
    conv_id = store.create_conversation_with_members(cid, name, unique_user_ids)
    return _ok(cid=cid, conv_id=conv_id, members=members, name=name, created=True)


@bp.post("/conversation/rename")
@auth_required
def rename_conversation():
    """Body: { cid, name } -> { cid, name }. Members only. Fan-out notifies
    all member devices to refresh the conversation label."""
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        return _err("JSON object required", 400)
    retry_after = rate_limit("conversation-rename", g.auth["sid"], 30, 60)
    if retry_after:
        response = jsonify({"ok": False, "error": "too many requests"})
        response.headers["Retry-After"] = str(retry_after)
        return response, 429
    cid = body.get("cid")
    raw_name = body.get("name", "")
    if not isinstance(cid, str) or not cid:
        return _err("cid required", 400)
    if not isinstance(raw_name, str):
        return _err("name must be a string", 400)
    name = raw_name.strip()[:100]
    conv = store.get_conversation_by_cid(cid)
    if not conv:
        return _err("conversation not found", 404)
    if g.auth["uid"] not in store.list_member_user_ids(conv["id"]):
        return _err("forbidden", 403)
    store.update_conversation_name(conv["id"], name)
    emit_to_conv_members(conv["id"], "conv_updated", {"cid": cid, "name": name})
    return _ok(cid=cid, name=name)


@bp.post("/contact-names/sync")
@auth_required
def sync_contact_names():
    """Atomically replace a gateway's shared contact-label snapshot.

    Only the account's Android SMS gateway may publish phone contact labels.
    Every target must be a self-only conversation belonging to that account,
    so a contact name can never be leaked into a multi-user conversation.
    """
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        return _err("JSON object required", 400)

    device = store.get_device_by_sid(g.auth["sid"])
    if not device or device["kind"] != "android_gateway":
        return _err("android gateway required", 403)
    retry_after = rate_limit("contact-names-sync", g.auth["sid"], 10, 60)
    if retry_after:
        response = jsonify({"ok": False, "error": "too many requests"})
        response.headers["Retry-After"] = str(retry_after)
        return response, 429

    raw_entries = body.get("entries")
    if not isinstance(raw_entries, list) or len(raw_entries) > 500:
        return _err("entries must be a list with at most 500 items", 400)

    entries: list[tuple[str, str]] = []
    seen_cids: set[str] = set()
    for raw_entry in raw_entries:
        if not isinstance(raw_entry, dict):
            return _err("each entry must be an object", 400)
        cid = raw_entry.get("cid")
        if "contact_name" not in raw_entry:
            return _err("contact_name required", 400)
        raw_name = raw_entry.get("contact_name")
        if not isinstance(cid, str) or not cid:
            return _err("entry cid required", 400)
        if cid in seen_cids:
            return _err("duplicate entry cid", 400)
        seen_cids.add(cid)
        if raw_name is not None and not isinstance(raw_name, str):
            return _err("contact_name must be a string or null", 400)
        contact_name = raw_name.strip() if isinstance(raw_name, str) else ""
        if len(contact_name) > 100:
            return _err("contact_name must be at most 100 characters", 400)
        if any(unicodedata.category(char) == "Cc" for char in contact_name):
            return _err("contact_name must not contain control characters", 400)
        entries.append((cid, contact_name))

    result = store.sync_contact_names(g.auth["uid"], entries)
    if result == "not_found":
        return _err("conversation not found", 404)
    if result == "forbidden":
        return _err("conversation must be self-only and owned by the account", 403)

    event_entries = [
        {"cid": cid, "contact_name": contact_name}
        for cid, contact_name in entries
    ]
    emit_to_user_devices(
        g.auth["uid"],
        "contacts_updated",
        {"entries": event_entries},
    )
    return _ok(entries=event_entries, updated=len(event_entries))


@bp.get("/conversations")
@auth_required
def list_conversations():
    """Return conversations the current user is in, plus their other members."""
    with store.conn_ctx() as c:
        rows = c.execute(
            "SELECT cv.cid, cv.id AS conv_id, cv.name, cv.synced_contact_name, "
            "cv.created_at FROM conversation_members m "
            "JOIN conversations cv ON cv.id = m.conv_id WHERE m.user_id = ? "
            "ORDER BY cv.created_at DESC, cv.id DESC",
            (g.auth["uid"],),
        ).fetchall()
        out = []
        for r in rows:
            members_rows = c.execute(
                "SELECT u.username FROM conversation_members m "
                "JOIN users u ON u.id = m.user_id WHERE m.conv_id = ?",
                (r["conv_id"],),
            ).fetchall()
            out.append(
                {
                    "cid": r["cid"],
                    "conv_id": r["conv_id"],
                    "name": r["name"],
                    "synced_contact_name": r["synced_contact_name"],
                    "members": [mr["username"] for mr in members_rows],
                    "created_at": r["created_at"],
                }
            )
    return _ok(conversations=out)


@bp.get("/conversation/<cid>/members")
@auth_required
def conv_members(cid: str):
    """Return all devices of all members — client needs these pubkeys to fan out
    envelope encryption keys for every device.
    """
    conv = store.get_conversation_by_cid(cid)
    if not conv:
        return _err("conversation not found", 404)
    members = store.list_members(conv["id"])
    # Verify the requesting user is a member.
    if not any(d["user_id"] == g.auth["uid"] for d in members):
        return _err("forbidden", 403)
    key_records = sorted(
        (int(d["user_id"]), d["sid"], d["pub_key"], d["sig_pub"])
        for d in members
    )
    canonical = json.dumps(key_records, separators=(",", ":"), ensure_ascii=True)
    recipient_keyset_hash = base64.urlsafe_b64encode(
        hashlib.sha256(canonical.encode()).digest()
    ).decode().rstrip("=")
    directories = {}
    directory_proofs = {}
    for user_id in store.list_member_user_ids(conv["id"]):
        user = store.get_user(user_id)
        directories[str(user_id)] = {
            "user_id": user_id,
            "identity_sig_pub": user["identity_sig_pub"],
            "security_epoch": user["security_epoch"],
            "directory_hash": user["directory_hash"],
            "security_mode": user["security_mode"],
        }
        directory_proofs[str(user_id)] = store.get_directory_proof(user_id)
    return _ok(
        conv_id=conv["id"],
        cid=cid,
        recipient_keyset_hash=recipient_keyset_hash,
        directory_checkpoints=list(directories.values()),
        directory_proofs=list(directory_proofs.values()),
        members=[
            {
                "user_id": d["user_id"],
                "device_id": d["device_id"],
                "sid": d["sid"],
                "name": d["name"],
                "pub_key": d["pub_key"],
                "sig_pub": d["sig_pub"],
                "kind": d["kind"],
            }
            for d in members
        ],
    )


@bp.get("/conversation/<cid>/messages")
@auth_required
def fetch_messages(cid: str):
    """Offline pull: ?since=<seq>&limit=<n>. Returns envelopes strictly after `since`.
    Use this on reconnect to backfill anything missed while offline.
    """
    conv = store.get_conversation_by_cid(cid)
    if not conv:
        return _err("conversation not found", 404)
    members = store.list_members(conv["id"])
    if not any(d["user_id"] == g.auth["uid"] for d in members):
        return _err("forbidden", 403)

    try:
        since = int(request.args.get("since", "0"))
        limit = int(request.args.get("limit", "200"))
    except (TypeError, ValueError):
        return _err("since and limit must be integers", 400)
    if since < 0 or not (1 <= limit <= 1000):
        return _err("since must be >= 0 and limit must be 1-1000", 400)
    msgs = store.fetch_messages_since(conv["id"], since, limit)
    return _ok(messages=msgs, conv_id=conv["id"], cid=cid)
