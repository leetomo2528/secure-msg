"""SQLite access layer. Pure stdlib sqlite3, no ORM.

All functions return plain dicts/tuples. Caller is responsible for serialization.
"""

from __future__ import annotations

import json
import sqlite3
import time
from collections.abc import Iterable
from contextlib import contextmanager
from typing import Any

from config import BASE_DIR, DB_PATH


def _connect() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, isolation_level=None, timeout=10)  # autocommit
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA busy_timeout = 10000")
    return conn


@contextmanager
def conn_ctx():
    conn = _connect()
    try:
        yield conn
    finally:
        conn.close()


def init_schema() -> None:
    schema_path = BASE_DIR / "schema.sql"
    sql = schema_path.read_text(encoding="utf-8")
    with conn_ctx() as c:
        c.executescript(sql)
        # Migration: add name column if missing (existing DBs created before this column).
        cols = [
            row[1] for row in c.execute("PRAGMA table_info(conversations)").fetchall()
        ]
        if "name" not in cols:
            c.execute(
                "ALTER TABLE conversations ADD COLUMN name TEXT NOT NULL DEFAULT ''"
            )
        message_cols = [
            row[1] for row in c.execute("PRAGMA table_info(messages)").fetchall()
        ]
        if "client_mid" not in message_cols:
            c.execute("ALTER TABLE messages ADD COLUMN client_mid TEXT")
        if "sender_pub_key" not in message_cols:
            c.execute(
                "ALTER TABLE messages ADD COLUMN sender_pub_key TEXT NOT NULL DEFAULT ''"
            )
            # Preserve the sender key with each historical row before a device
            # can be revoked. Rows whose device was already deleted cannot be
            # recovered, but every future message remains decryptable.
            c.execute(
                "UPDATE messages SET sender_pub_key = COALESCE(("
                "SELECT d.pub_key FROM devices d WHERE d.sid = messages.sender_sid"
                "), '') WHERE sender_pub_key = ''"
            )
        if "carrier_status" not in message_cols:
            c.execute(
                "ALTER TABLE messages ADD COLUMN carrier_status TEXT NOT NULL DEFAULT 'none'"
            )
        if "carrier_error" not in message_cols:
            c.execute("ALTER TABLE messages ADD COLUMN carrier_error TEXT")
        if "carrier_updated_at" not in message_cols:
            c.execute("ALTER TABLE messages ADD COLUMN carrier_updated_at INTEGER")
        c.execute(
            "CREATE INDEX IF NOT EXISTS idx_messages_carrier_status "
            "ON messages(carrier_status)"
        )
        c.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_sender_mid "
            "ON messages(sender_sid, client_mid) WHERE client_mid IS NOT NULL"
        )
        device_cols = [
            row[1] for row in c.execute("PRAGMA table_info(devices)").fetchall()
        ]
        if "kind" not in device_cols:
            c.execute("ALTER TABLE devices ADD COLUMN kind TEXT NOT NULL DEFAULT 'web'")
            c.execute(
                "UPDATE devices SET kind = 'android_gateway' WHERE id IN ("
                "SELECT MIN(id) FROM devices WHERE name LIKE 'android-%' GROUP BY user_id)"
            )
        c.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_one_android_gateway_per_user "
            "ON devices(user_id) WHERE kind = 'android_gateway'"
        )


def now() -> int:
    return int(time.time())


# ----- users -------------------------------------------------------------


def create_user(username: str, pw_hash: str) -> int:
    with conn_ctx() as c:
        cur = c.execute(
            "INSERT INTO users(username, pw_hash, created_at) VALUES (?, ?, ?)",
            (username, pw_hash, now()),
        )
        return cur.lastrowid


def get_user_by_name(username: str) -> dict[str, Any] | None:
    with conn_ctx() as c:
        row = c.execute(
            "SELECT id, username, pw_hash, created_at FROM users WHERE username = ?",
            (username,),
        ).fetchone()
        return dict(row) if row else None


def get_user(uid: int) -> dict[str, Any] | None:
    with conn_ctx() as c:
        row = c.execute(
            "SELECT id, username, created_at FROM users WHERE id = ?",
            (uid,),
        ).fetchone()
        return dict(row) if row else None


# ----- devices -----------------------------------------------------------


def add_device(
    user_id: int,
    sid: str,
    name: str,
    pub_key: str,
    sig_pub: str,
    kind: str = "web",
    max_devices: int | None = None,
) -> int:
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        try:
            if max_devices is not None:
                row = c.execute(
                    "SELECT COUNT(*) AS count FROM devices WHERE user_id = ?",
                    (user_id,),
                ).fetchone()
                if int(row["count"]) >= max_devices:
                    raise ValueError("device limit reached")
            timestamp = now()
            cur = c.execute(
                "INSERT INTO devices(user_id, sid, name, kind, pub_key, sig_pub, created_at, last_seen) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (user_id, sid, name, kind, pub_key, sig_pub, timestamp, timestamp),
            )
            c.execute("COMMIT")
            return cur.lastrowid
        except Exception:
            c.execute("ROLLBACK")
            raise


def get_device_by_sid(sid: str) -> dict[str, Any] | None:
    with conn_ctx() as c:
        row = c.execute(
            "SELECT d.id, d.user_id, d.sid, d.name, d.kind, d.pub_key, d.sig_pub, d.created_at, d.last_seen, "
            "u.username FROM devices d JOIN users u ON u.id = d.user_id WHERE d.sid = ?",
            (sid,),
        ).fetchone()
        return dict(row) if row else None


def list_user_devices(user_id: int) -> list[dict[str, Any]]:
    with conn_ctx() as c:
        rows = c.execute(
            "SELECT id, sid, name, kind, pub_key, sig_pub, created_at, last_seen "
            "FROM devices WHERE user_id = ? ORDER BY created_at",
            (user_id,),
        ).fetchall()
        return [dict(r) for r in rows]


def touch_device(device_id: int) -> None:
    with conn_ctx() as c:
        c.execute("UPDATE devices SET last_seen = ? WHERE id = ?", (now(), device_id))


def revoke_device(device_id: int, user_id: int) -> bool:
    with conn_ctx() as c:
        cur = c.execute(
            "DELETE FROM devices WHERE id = ? AND user_id = ?",
            (device_id, user_id),
        )
        return cur.rowcount > 0


# ----- conversations -----------------------------------------------------


def create_conversation(cid: str, name: str = "") -> int:
    with conn_ctx() as c:
        cur = c.execute(
            "INSERT INTO conversations(cid, name, created_at) VALUES (?, ?, ?)",
            (cid, name, now()),
        )
        return cur.lastrowid


def create_conversation_with_members(
    cid: str, name: str, user_ids: Iterable[int]
) -> int:
    """Create the conversation and all memberships atomically."""
    unique_user_ids = list(dict.fromkeys(user_ids))
    if not unique_user_ids:
        raise ValueError("conversation must have at least one member")
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        try:
            timestamp = now()
            cur = c.execute(
                "INSERT INTO conversations(cid, name, created_at) VALUES (?, ?, ?)",
                (cid, name, timestamp),
            )
            conv_id = int(cur.lastrowid)
            c.executemany(
                "INSERT INTO conversation_members(conv_id, user_id, joined_at) VALUES (?, ?, ?)",
                ((conv_id, user_id, timestamp) for user_id in unique_user_ids),
            )
            c.execute("COMMIT")
            return conv_id
        except Exception:
            c.execute("ROLLBACK")
            raise


def get_or_create_single_member_conversation(
    cid: str,
    name: str,
    user_id: int,
) -> tuple[int, str, bool]:
    """Atomically reuse a self-only named conversation or create it.

    SMS clients resolve a phone number to a self-only relay conversation. This
    prevents concurrent Android/web requests from splitting one phone thread
    across duplicate conversation ids.
    """
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        try:
            existing = c.execute(
                "SELECT cv.id, cv.cid FROM conversations cv "
                "JOIN conversation_members mine ON mine.conv_id = cv.id AND mine.user_id = ? "
                "WHERE cv.name = ? AND ("
                "SELECT COUNT(*) FROM conversation_members all_members "
                "WHERE all_members.conv_id = cv.id) = 1 "
                "ORDER BY cv.id ASC LIMIT 1",
                (user_id, name),
            ).fetchone()
            if existing:
                c.execute("COMMIT")
                return int(existing["id"]), str(existing["cid"]), False
            timestamp = now()
            cur = c.execute(
                "INSERT INTO conversations(cid, name, created_at) VALUES (?, ?, ?)",
                (cid, name, timestamp),
            )
            conv_id = int(cur.lastrowid)
            c.execute(
                "INSERT INTO conversation_members(conv_id, user_id, joined_at) VALUES (?, ?, ?)",
                (conv_id, user_id, timestamp),
            )
            c.execute("COMMIT")
            return conv_id, cid, True
        except Exception:
            c.execute("ROLLBACK")
            raise


def add_member(conv_id: int, user_id: int) -> None:
    with conn_ctx() as c:
        c.execute(
            "INSERT OR IGNORE INTO conversation_members(conv_id, user_id, joined_at) VALUES (?, ?, ?)",
            (conv_id, user_id, now()),
        )


def get_conversation_by_cid(cid: str) -> dict[str, Any] | None:
    with conn_ctx() as c:
        row = c.execute(
            "SELECT id, cid, name, created_at FROM conversations WHERE cid = ?",
            (cid,),
        ).fetchone()
        return dict(row) if row else None


def list_members(conv_id: int) -> list[dict[str, Any]]:
    """Return all devices of all members — needed to fan out envelope keys."""
    with conn_ctx() as c:
        rows = c.execute(
            "SELECT d.id AS device_id, d.sid, d.user_id, d.pub_key, d.name, d.kind "
            "FROM conversation_members m "
            "JOIN devices d ON d.user_id = m.user_id "
            "WHERE m.conv_id = ?",
            (conv_id,),
        ).fetchall()
        return [dict(r) for r in rows]


# ----- messages ---------------------------------------------------------


def next_seq(conv_id: int) -> int:
    with conn_ctx() as c:
        row = c.execute(
            "SELECT COALESCE(MAX(seq), 0) AS s FROM messages WHERE conv_id = ?",
            (conv_id,),
        ).fetchone()
        return int(row["s"]) + 1


def insert_message(
    conv_id: int,
    sender_id: int,
    sender_sid: str,
    payload: str,
    client_mid: str | None = None,
    expected_recipient_sids: set[str] | None = None,
    created_at: int | None = None,
) -> tuple[int, int, bool]:
    """Return (message id, seq, inserted) in one immediate transaction.

    A repeated sender-generated client_mid returns the original row instead of
    allocating another sequence, making Socket.IO acknowledgement retries safe.

    Callers that fan the row out live (Socket.IO) should pass the timestamp they
    will put in the envelope so live events and history pulls agree exactly.
    """
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        try:
            timestamp = int(created_at) if created_at is not None else now()
            sender = c.execute(
                "SELECT pub_key FROM devices WHERE sid = ? AND user_id = ?",
                (sender_sid, sender_id),
            ).fetchone()
            if not sender:
                raise ValueError("sender device is revoked or unknown")
            if client_mid:
                existing = c.execute(
                    "SELECT id, seq, conv_id FROM messages "
                    "WHERE sender_sid = ? AND client_mid = ?",
                    (sender_sid, client_mid),
                ).fetchone()
                if existing:
                    if int(existing["conv_id"]) != conv_id:
                        raise ValueError(
                            "client_mid was already used in another conversation"
                        )
                    c.execute("COMMIT")
                    return int(existing["id"]), int(existing["seq"]), False
            if expected_recipient_sids is not None:
                current_sids = {
                    str(row["sid"])
                    for row in c.execute(
                        "SELECT d.sid FROM conversation_members m "
                        "JOIN devices d ON d.user_id = m.user_id "
                        "WHERE m.conv_id = ?",
                        (conv_id,),
                    ).fetchall()
                }
                if current_sids != expected_recipient_sids:
                    raise ValueError("payload keys do not match conversation devices")
            row = c.execute(
                "SELECT COALESCE(MAX(seq), 0) AS s FROM messages WHERE conv_id = ?",
                (conv_id,),
            ).fetchone()
            seq = int(row["s"]) + 1
            cur = c.execute(
                "INSERT INTO messages(seq, conv_id, sender_id, sender_sid, sender_pub_key, "
                "client_mid, payload, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    seq,
                    conv_id,
                    sender_id,
                    sender_sid,
                    sender["pub_key"],
                    client_mid,
                    payload,
                    timestamp,
                ),
            )
            c.execute("COMMIT")
            return cur.lastrowid, seq, True
        except Exception:
            c.execute("ROLLBACK")
            raise


def get_message_by_sender_mid(
    sender_sid: str, client_mid: str
) -> dict[str, Any] | None:
    with conn_ctx() as c:
        row = c.execute(
            "SELECT id, seq, conv_id FROM messages WHERE sender_sid = ? AND client_mid = ?",
            (sender_sid, client_mid),
        ).fetchone()
        return dict(row) if row else None


def fetch_messages_since(
    conv_id: int, since_seq: int, limit: int = 200
) -> list[dict[str, Any]]:
    with conn_ctx() as c:
        rows = c.execute(
            "SELECT id, seq, conv_id, sender_id, sender_sid, sender_pub_key, payload, created_at, "
            "carrier_status, carrier_error, carrier_updated_at "
            "FROM messages WHERE conv_id = ? AND seq > ? ORDER BY seq ASC LIMIT ?",
            (conv_id, since_seq, limit),
        ).fetchall()
        result = []
        for row in rows:
            item = dict(row)
            # SQLite stores the opaque envelope as JSON text, while both clients
            # consume an object. Keep the wire shape identical to Socket.IO.
            item["payload"] = json.loads(item["payload"])
            result.append(item)
        return result


_CARRIER_STATUS_ORDER = {
    "none": 0,
    "queued": 1,
    "unknown": 2,
    "dispatched": 3,
    "sent": 4,
    "failed": 5,
    "delivery_failed": 5,
    "delivered": 5,
}
_TERMINAL_CARRIER_STATUSES = {"failed", "delivery_failed", "delivered"}


def update_carrier_status(
    conv_id: int,
    seq: int,
    status: str,
    error: str | None = None,
) -> dict[str, Any] | None:
    """Update the carrier lifecycle without allowing a terminal status to regress.

    The relay only sees status metadata (cid/seq/status); message content remains
    inside the E2E envelope. Failure and delivery outcomes are terminal so delayed
    Android broadcasts cannot move a row back to an earlier state.
    """
    if status not in _CARRIER_STATUS_ORDER:
        raise ValueError("invalid carrier status")
    safe_error = error[:300] if isinstance(error, str) else None
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        try:
            row = c.execute(
                "SELECT carrier_status, carrier_error, carrier_updated_at "
                "FROM messages WHERE conv_id = ? AND seq = ?",
                (conv_id, seq),
            ).fetchone()
            if not row:
                c.execute("COMMIT")
                return None
            current = str(row["carrier_status"] or "none")
            if current in _TERMINAL_CARRIER_STATUSES or (
                _CARRIER_STATUS_ORDER.get(status, 0)
                < _CARRIER_STATUS_ORDER.get(current, 0)
            ):
                c.execute("COMMIT")
                return {
                    "status": current,
                    "error": row["carrier_error"],
                    "updated_at": row["carrier_updated_at"],
                }
            updated = now()
            c.execute(
                "UPDATE messages SET carrier_status = ?, carrier_error = ?, carrier_updated_at = ? "
                "WHERE conv_id = ? AND seq = ?",
                (status, safe_error, updated, conv_id, seq),
            )
            c.execute("COMMIT")
            return {"status": status, "error": safe_error, "updated_at": updated}
        except Exception:
            c.execute("ROLLBACK")
            raise


def max_seq(conv_id: int) -> int:
    with conn_ctx() as c:
        row = c.execute(
            "SELECT COALESCE(MAX(seq), 0) AS seq FROM messages WHERE conv_id = ?",
            (conv_id,),
        ).fetchone()
        return int(row["seq"])


# ----- delivery cursors -------------------------------------------------


def get_cursor(device_id: int, conv_id: int) -> int:
    with conn_ctx() as c:
        row = c.execute(
            "SELECT last_seq FROM delivery_cursors WHERE device_id = ? AND conv_id = ?",
            (device_id, conv_id),
        ).fetchone()
        return int(row["last_seq"]) if row else 0


def set_cursor(device_id: int, conv_id: int, last_seq: int) -> None:
    with conn_ctx() as c:
        c.execute(
            "INSERT INTO delivery_cursors(device_id, conv_id, last_seq) VALUES (?, ?, ?) "
            "ON CONFLICT(device_id, conv_id) DO UPDATE SET "
            "last_seq = MAX(delivery_cursors.last_seq, excluded.last_seq)",
            (device_id, conv_id, last_seq),
        )
