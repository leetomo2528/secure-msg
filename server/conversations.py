"""REST endpoints for conversations and message history (offline pull).

Note on privacy: these endpoints never return plaintext. `payload` is the encrypted
envelope the server stored; the client decrypts it using the per-device key held
locally in IndexedDB.
"""

from __future__ import annotations

import secrets
import unicodedata
import base64
import hashlib
import json

import store
from auth import (
    PHONE_RE,
    USERNAME_RE,
    _err,
    _json_body,
    _ok,
    _rate_error,
    _valid_b64u,
    auth_required,
)
from flask import Blueprint, g, request
from rate_limit import check as rate_limit
from sockets import emit_to_conv_members, emit_to_user_devices

bp = Blueprint("conv", __name__, url_prefix="/api")
# JSON has no integer width, but sqlite3 raises OverflowError at bind time for
# anything past 2**63, which surfaces as a 500 with a traceback rather than a
# rejected request. Cap at the range a JS client can even represent.
MAX_SAFE_INT = 2 ** 53
# One full history sync spends a call per conversation on missing-keys, on
# messages and (per 200-key batch) on share-keys. At the old budget of 120 an
# account with more phone threads than that could never finish a sync, and no
# client backs off on the 429.
SYNC_SCAN_BUDGET = 600


@bp.post("/conversation")
@auth_required
def create_conversation():
    """Body: { members: [username, ...], name?: str } -> { cid, members }.
    The conversation id is a random opaque token. `name` is a display label
    (e.g. phone number for SMS bridge conversations).
    """
    body = _json_body()
    if body is None:
        return _err("JSON object required", 400)
    retry_after = rate_limit("conversation-create", g.auth["sid"], 30, 60)
    if retry_after:
        return _rate_error(retry_after)
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
    #
    # Every rejection below is the SAME message on purpose. Naming the member
    # that does not exist turns this endpoint into a username oracle, which
    # would undo the constant-time credential check /login goes to the trouble
    # of doing.
    resolved = []
    for uname in dict.fromkeys(members):
        if not USERNAME_RE.fullmatch(uname):
            return _err("invalid member list", 400)
        u = store.get_user_by_name(uname)
        if not u:
            return _err("invalid member list", 400)
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
    all member devices to refresh the conversation label. On a self-only phone
    thread the label lands in `synced_contact_name` and `name` is untouched."""
    body = _json_body()
    if body is None:
        return _err("JSON object required", 400)
    retry_after = rate_limit("conversation-rename", g.auth["sid"], 30, 60)
    if retry_after:
        return _rate_error(retry_after)
    cid = body.get("cid")
    raw_name = body.get("name", "")
    if not isinstance(cid, str) or not cid:
        return _err("cid required", 400)
    if not isinstance(raw_name, str):
        return _err("name must be a string", 400)
    name = raw_name.strip()[:100]
    conv, error = _member_conversation(cid)
    if error:
        return error
    # On a self-only phone thread `name` is the SMS identity, not a label:
    # sockets.py's carrier gate, both clients' ownership policy and
    # get_or_create_single_member_conversation all match on it, so overwriting
    # it de-owned the gateway and split one number across two cids. A rename
    # there is a contact label, which is what synced_contact_name holds.
    # sync_contact_names re-checks self-only ownership inside its own
    # transaction, so a group merely NAMED like a number still renames.
    if PHONE_RE.fullmatch(str(conv["name"] or "")):
        if store.sync_contact_names(g.auth["uid"], [(cid, name)]) is None:
            emit_to_user_devices(
                g.auth["uid"],
                "contacts_updated",
                {"entries": [{"cid": cid, "contact_name": name}]},
            )
            return _ok(cid=cid, name=conv["name"], synced_contact_name=name)
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
    body = _json_body()
    if body is None:
        return _err("JSON object required", 400)

    device = store.get_device_by_sid(g.auth["sid"])
    if not device or device["kind"] != "android_gateway":
        return _err("android gateway required", 403)
    retry_after = rate_limit("contact-names-sync", g.auth["sid"], 10, 60)
    if retry_after:
        return _rate_error(retry_after)

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
    return _ok(conversations=store.list_conversations_for_user(g.auth["uid"]))


@bp.get("/conversation/<cid>/members")
@auth_required
def conv_members(cid: str):
    """Return all devices of all members — client needs these pubkeys to fan out
    envelope encryption keys for every device.
    """
    snapshot = store.get_conversation_directory_snapshot(cid)
    if not snapshot:
        return _err("conversation not found", 404)
    conv = snapshot["conversation"]
    members = snapshot["members"]
    # Conversation membership is an account property, independent of how many
    # approved devices that account currently exposes in the key directory.
    if not any(
        checkpoint["user_id"] == g.auth["uid"]
        for checkpoint in snapshot["directory_checkpoints"]
    ):
        return _err("forbidden", 403)
    key_records = sorted(
        (int(d["user_id"]), d["sid"], d["pub_key"], d["sig_pub"])
        for d in members
    )
    canonical = json.dumps(key_records, separators=(",", ":"), ensure_ascii=True)
    recipient_keyset_hash = base64.urlsafe_b64encode(
        hashlib.sha256(canonical.encode()).digest()
    ).decode().rstrip("=")
    return _ok(
        conv_id=conv["id"],
        cid=cid,
        recipient_keyset_hash=recipient_keyset_hash,
        directory_checkpoints=snapshot["directory_checkpoints"],
        directory_proofs=snapshot["directory_proofs"],
        # Deliberately NO device `name`: envelope encryption needs the keys and
        # the SID, never the human label. Anyone who can name you in a
        # conversation could otherwise read your device names ("Yunje's
        # MacBook") straight out of this endpoint. Your own device names still
        # come back from /devices, which is scoped to your account.
        members=[
            {
                "user_id": d["user_id"],
                "device_id": d["device_id"],
                "sid": d["sid"],
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
    # Parsing the query string costs nothing; resolving the conversation is
    # two SQLite round-trips. A malformed pull must not pay for them.
    try:
        since = int(request.args.get("since", "0"))
        limit = int(request.args.get("limit", "200"))
    except (TypeError, ValueError):
        return _err("since and limit must be integers", 400)
    if not (0 <= since <= MAX_SAFE_INT) or not (1 <= limit <= 1000):
        return _err("since must be >= 0 and limit must be 1-1000", 400)
    conv, error = _member_conversation(cid)
    if error:
        return error
    # A page is up to 1000 envelopes of up to MAX_ENVELOPE_BYTES each, all
    # materialized in the one gunicorn worker, so concurrent pulls are a
    # memory lever. Every other expensive read is already budgeted.
    retry_after = rate_limit("messages-pull", g.auth["sid"], SYNC_SCAN_BUDGET, 60)
    if retry_after:
        return _rate_error(retry_after)
    msgs = store.fetch_messages_since(conv["id"], since, limit)
    return _ok(messages=msgs, conv_id=conv["id"], cid=cid)


MAX_SHARE_ENTRIES = 200
# A re-wrapped envelope key is exactly what the socket send path accepts: a
# crypto_box of the 32-byte message key (32 + 16 MAC) under a 24-byte nonce.
# Anything longer is not a key, and since share-keys REWRITES a stored
# envelope, a looser bound here is a way to grow other accounts' stored rows.
SHARE_EK_BYTES = 48
SHARE_NONCE_BYTES = 24


@bp.get("/conversation/<cid>/missing-keys")
@auth_required
def missing_keys(cid: str):
    """Sequences in `cid` that `?sid=` cannot decrypt yet.

    The sharing device drives history backfill from this list, so it only
    re-wraps what is actually missing.
    """
    retry_after = rate_limit("missing-keys", g.auth["sid"], SYNC_SCAN_BUDGET, 60)
    if retry_after:
        return _rate_error(retry_after)
    target_sid = request.args.get("sid", "")
    conv, target, error = _share_target(cid, target_sid)
    if error:
        return error
    return _ok(
        cid=cid,
        sid=target["sid"],
        seqs=store.missing_key_sequences(conv["id"], target["sid"]),
    )


@bp.post("/conversation/<cid>/share-keys")
@auth_required
def share_keys(cid: str):
    """Body: { sid, entries: [{ seq, ek, n }] } -> { added, skipped }.

    Grants a later-registered device of the *same account* the ability to read
    existing history: the caller unwraps each message key with its own device
    key and re-wraps it for the target's public key. The relay only ever sees
    the wrapped keys, exactly as it does for a normal send.
    """
    body = _json_body()
    if body is None:
        return _err("JSON object required", 400)
    retry_after = rate_limit("share-keys", g.auth["sid"], SYNC_SCAN_BUDGET, 60)
    if retry_after:
        return _rate_error(retry_after)

    conv, target, error = _share_target(cid, body.get("sid", ""))
    if error:
        return error

    entries = body.get("entries")
    if not isinstance(entries, list) or not entries:
        return _err("entries must be a non-empty list", 400)
    if len(entries) > MAX_SHARE_ENTRIES:
        return _err(f"at most {MAX_SHARE_ENTRIES} entries per request", 400)
    clean: list[dict] = []
    for entry in entries:
        if not isinstance(entry, dict):
            return _err("each entry must be an object", 400)
        seq = entry.get("seq")
        ek = entry.get("ek")
        n = entry.get("n")
        if (
            not isinstance(seq, int)
            or isinstance(seq, bool)
            or not (1 <= seq <= MAX_SAFE_INT)
        ):
            return _err("seq must be a positive integer", 400)
        if not _valid_b64u(ek, SHARE_EK_BYTES):
            return _err(f"ek must be base64url for {SHARE_EK_BYTES} bytes", 400)
        if not _valid_b64u(n, SHARE_NONCE_BYTES):
            return _err(f"n must be base64url for {SHARE_NONCE_BYTES} bytes", 400)
        clean.append({"seq": seq, "ek": ek, "n": n})

    result = store.share_message_keys(conv["id"], target["sid"], g.auth["sid"], clean)
    return _ok(**result)


def _member_conversation(cid: str) -> tuple[dict | None, tuple | None]:
    """Resolve a conversation the caller's account must already belong to.

    Returns (conversation, None) or (None, error response). Three endpoints
    asked this one question three different ways, two of them by materializing
    every approved device of every member — with its pub_key, sig_pub and
    directory columns — only to test a boolean. The account-level test is the
    one that survives: auth_required has already refused a caller whose own
    device is not approved, so the device scan could never have rejected a
    request this admits.
    """
    conv = store.get_conversation_by_cid(cid)
    if not conv:
        return None, _err("conversation not found", 404)
    if not store.is_conversation_member(conv["id"], g.auth["uid"]):
        return None, _err("forbidden", 403)
    return conv, None


def _share_target(cid: str, target_sid: str):
    """Resolve (conversation, target device) for a key-sharing call.

    The target must be another approved device of the caller's own account:
    sharing is a backfill for one's own new device, never a way to hand
    history to a second account that merely shares the conversation.
    """
    if not isinstance(target_sid, str) or not target_sid:
        return None, None, _err("sid is required", 400)
    conv, error = _member_conversation(cid)
    if error:
        return None, None, error
    if target_sid == g.auth["sid"]:
        return None, None, _err("target must be another device", 400)
    target = store.get_device_by_sid(target_sid)
    if not target or target["user_id"] != g.auth["uid"]:
        return None, None, _err("target device not found", 404)
    if target["trust_state"] != "approved":
        return None, None, _err("target device is not approved", 409)
    return conv, target, None
