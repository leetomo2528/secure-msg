"""Cross-device shared block rules (keywords + blocked senders).

Rules are stored server-side PER USER so every device of the same account
applies the same filter set. Values are user-entered filter strings (keywords
or phone numbers) — not message content; plaintext still never reaches the
server. Each device applies rules locally after decryption.
"""

from __future__ import annotations

import re

import store
from auth import _err, _ok, auth_required
from flask import Blueprint, g, jsonify, request
from rate_limit import check as rate_limit
from sockets import emit_to_user_devices

bp = Blueprint("blocklist", __name__, url_prefix="/api")

RULE_TYPES = {"keyword", "sender"}
# Loose sender address: digits/+/#/* with separators, 3-32 chars after strip.
SENDER_RE = re.compile(r"[+*#0-9][+*#0-9\-\s]{1,30}[0-9]", re.ASCII)


def _validate(rule_type: object, raw_value: object) -> tuple[str, str] | None:
    if rule_type not in RULE_TYPES:
        return None
    if not isinstance(raw_value, str):
        return None
    value = raw_value.strip()
    if rule_type == "keyword":
        if not (1 <= len(value) <= 120):
            return None
    else:
        if not (3 <= len(value) <= 32) or not SENDER_RE.fullmatch(value):
            return None
    return (rule_type, value)


@bp.get("/blocklist")
@auth_required
def list_rules():
    return _ok(rules=store.list_block_rules(g.auth["uid"]))


@bp.post("/blocklist")
@auth_required
def add_rule():
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        return _err("JSON object required", 400)
    retry_after = rate_limit("blocklist-add", g.auth["sid"], 120, 60)
    if retry_after:
        response = jsonify({"ok": False, "error": "too many requests"})
        response.headers["Retry-After"] = str(retry_after)
        return response, 429
    validated = _validate(body.get("type"), body.get("value"))
    if validated is None:
        return _err("type must be keyword|sender with a valid value", 400)
    rule_type, value = validated
    try:
        rule = store.add_block_rule(g.auth["uid"], rule_type, value)
    except ValueError as exc:
        return _err(str(exc), 409)
    emit_to_user_devices(
        g.auth["uid"],
        "blocklist_updated",
        {"action": "add", "rule": rule},
        exclude_sid=g.auth["sid"],
    )
    return _ok(rule=rule)


@bp.post("/blocklist/remove")
@auth_required
def remove_rule():
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        return _err("JSON object required", 400)
    retry_after = rate_limit("blocklist-remove", g.auth["sid"], 120, 60)
    if retry_after:
        response = jsonify({"ok": False, "error": "too many requests"})
        response.headers["Retry-After"] = str(retry_after)
        return response, 429
    try:
        rule_id = int(body.get("id"))
    except (TypeError, ValueError):
        return _err("id must be an integer", 400)
    if not store.remove_block_rule(g.auth["uid"], rule_id):
        return _err("rule not found", 404)
    emit_to_user_devices(
        g.auth["uid"],
        "blocklist_updated",
        {"action": "remove", "id": rule_id},
        exclude_sid=g.auth["sid"],
    )
    return _ok()
