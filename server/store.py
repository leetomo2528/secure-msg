"""SQLite access layer. Pure stdlib sqlite3, no ORM.

All functions return plain dicts/tuples. Caller is responsible for serialization.
"""

from __future__ import annotations

import json
import base64
import hashlib
import hmac
import secrets
import sqlite3
import time
from collections.abc import Callable, Iterable
from contextlib import contextmanager
from typing import Any

import config
from config import BASE_DIR, DB_PATH


def _b64u(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def device_fingerprint(pub_key: str, sig_pub: str) -> str:
    canonical = (
        "securemsg-device-fingerprint-v1\n"
        f"pub_key={pub_key}\n"
        f"sig_pub={sig_pub}\n"
    )
    return _b64u(hashlib.sha256(canonical.encode("utf-8")).digest())


def _directory_hash_rows(rows: Iterable[sqlite3.Row | dict[str, Any]]) -> str:
    records = [
        [str(r["sid"]), str(r["pub_key"]), str(r["sig_pub"]), str(r["kind"])]
        for r in rows
    ]
    records.sort(key=lambda item: item[0])
    canonical = json.dumps(records, separators=(",", ":"), ensure_ascii=True)
    return _b64u(hashlib.sha256(canonical.encode()).digest())


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


@contextmanager
def read_snapshot():
    """Yield one deferred SQLite read transaction.

    The first query pins a coherent database snapshot.  In WAL mode writers
    can continue and commit while the caller finishes materializing related
    rows from that older snapshot.
    """
    with conn_ctx() as conn:
        conn.execute("BEGIN")
        try:
            yield conn
            conn.execute("COMMIT")
        except Exception:
            conn.execute("ROLLBACK")
            raise


def init_schema() -> None:
    schema_path = BASE_DIR / "schema.sql"
    sql = schema_path.read_text(encoding="utf-8")
    with conn_ctx() as c:
        c.executescript(sql)
        # Every additive migration below is one write-locked transaction.
        # Closing the connection on an exception rolls the whole migration
        # back, avoiding a visible half-migrated trust directory.
        c.execute("BEGIN IMMEDIATE")
        c.execute("""
            CREATE TABLE IF NOT EXISTS device_login_challenges (
                challenge_id TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
                sid TEXT NOT NULL,
                challenge TEXT NOT NULL,
                session_version INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                consumed_at INTEGER,
                created_at INTEGER NOT NULL
            )
        """)
        c.execute("CREATE INDEX IF NOT EXISTS idx_device_login_challenges_device ON device_login_challenges(device_id, created_at)")
        c.execute("""
            CREATE TABLE IF NOT EXISTS pairing_sessions (
                pairing_id     TEXT PRIMARY KEY,
                user_id        INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                subject_sid    TEXT NOT NULL,
                approver_sid   TEXT NOT NULL,
                nonce_new      TEXT NOT NULL,
                nonce_approver TEXT NOT NULL,
                expires_at     INTEGER NOT NULL,
                consumed_at    INTEGER,
                created_at     INTEGER NOT NULL
            )
        """)
        c.execute("CREATE INDEX IF NOT EXISTS idx_pairing_sessions_subject ON pairing_sessions(subject_sid, created_at)")
        user_cols = {row[1] for row in c.execute("PRAGMA table_info(users)")}
        if "identity_sig_pub" not in user_cols:
            c.execute("ALTER TABLE users ADD COLUMN identity_sig_pub TEXT NOT NULL DEFAULT ''")
        if "security_epoch" not in user_cols:
            c.execute("ALTER TABLE users ADD COLUMN security_epoch INTEGER NOT NULL DEFAULT 0")
        if "directory_hash" not in user_cols:
            c.execute("ALTER TABLE users ADD COLUMN directory_hash TEXT NOT NULL DEFAULT ''")
        if "trust_enforced_at" not in user_cols:
            c.execute("ALTER TABLE users ADD COLUMN trust_enforced_at INTEGER")
        if "security_mode" not in user_cols:
            c.execute("ALTER TABLE users ADD COLUMN security_mode TEXT NOT NULL DEFAULT 'legacy_v1'")
        if "email" not in user_cols:
            c.execute("ALTER TABLE users ADD COLUMN email TEXT")
        if "email_verified_at" not in user_cols:
            c.execute("ALTER TABLE users ADD COLUMN email_verified_at INTEGER")
        c.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON users(email) WHERE email IS NOT NULL")
        c.execute("""
            CREATE TABLE IF NOT EXISTS email_verification_challenges (
                challenge_id TEXT PRIMARY KEY,
                email TEXT NOT NULL,
                username TEXT NOT NULL,
                pw_hash TEXT NOT NULL,
                code_digest TEXT NOT NULL,
                expires_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                consumed_at INTEGER,
                created_at INTEGER NOT NULL
            )
        """)
        c.execute("CREATE INDEX IF NOT EXISTS idx_email_verification_email ON email_verification_challenges(email, created_at)")
        c.execute("""
            CREATE TABLE IF NOT EXISTS password_reset_challenges (
                challenge_id TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                email TEXT NOT NULL,
                code_digest TEXT NOT NULL,
                expires_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                consumed_at INTEGER,
                created_at INTEGER NOT NULL
            )
        """)
        c.execute("CREATE INDEX IF NOT EXISTS idx_password_reset_user ON password_reset_challenges(user_id, created_at)")
        # Migration: add name column if missing (existing DBs created before this column).
        cols = [
            row[1] for row in c.execute("PRAGMA table_info(conversations)").fetchall()
        ]
        if "name" not in cols:
            c.execute(
                "ALTER TABLE conversations ADD COLUMN name TEXT NOT NULL DEFAULT ''"
            )
        if "synced_contact_name" not in cols:
            c.execute(
                "ALTER TABLE conversations ADD COLUMN synced_contact_name "
                "TEXT NOT NULL DEFAULT ''"
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
        legacy_trust_migration = "trust_state" not in device_cols
        if "kind" not in device_cols:
            c.execute("ALTER TABLE devices ADD COLUMN kind TEXT NOT NULL DEFAULT 'web'")
            c.execute(
                "UPDATE devices SET kind = 'android_gateway' WHERE id IN ("
                "SELECT MIN(id) FROM devices WHERE name LIKE 'android-%' GROUP BY user_id)"
            )
        if "session_version" not in device_cols:
            c.execute(
                "ALTER TABLE devices ADD COLUMN session_version "
                "INTEGER NOT NULL DEFAULT 1"
            )
        trust_columns = {
            "trust_state": "TEXT NOT NULL DEFAULT 'approved'",
            "challenge": "TEXT NOT NULL DEFAULT ''",
            "approved_by_sid": "TEXT",
            "approved_at": "INTEGER",
            "approval_signature": "TEXT",
            "fingerprint": "TEXT NOT NULL DEFAULT ''",
            "revoked_at": "INTEGER",
            "verification_state": "TEXT NOT NULL DEFAULT 'legacy_unverified'",
        }
        for column, definition in trust_columns.items():
            if column not in device_cols:
                c.execute(f"ALTER TABLE devices ADD COLUMN {column} {definition}")
        # Every pre-trust device is grandfathered in. The oldest key is the
        # account's stable legacy-TOFU identity anchor; no key material changes.
        c.execute("UPDATE devices SET trust_state = 'approved' WHERE trust_state IS NULL OR trust_state = ''")
        for row in c.execute("SELECT id, pub_key, sig_pub, fingerprint FROM devices").fetchall():
            fingerprint = device_fingerprint(row["pub_key"], row["sig_pub"])
            if row["fingerprint"] == fingerprint:
                continue
            c.execute(
                "UPDATE devices SET fingerprint = ? WHERE id = ?",
                (fingerprint, row["id"]),
            )
        timestamp = now()
        for user in c.execute("SELECT id FROM users ORDER BY id").fetchall():
            uid = int(user["id"])
            gateways = c.execute(
                "SELECT id,sid FROM devices WHERE user_id=? AND kind='android_gateway' AND trust_state='approved' ORDER BY created_at,id",
                (uid,),
            ).fetchall()
            quarantined_gateways: list[str] = []
            for duplicate in gateways[1:]:
                c.execute(
                    "UPDATE devices SET trust_state='pending', verification_state='legacy_unverified', challenge=?, session_version=session_version+1, approved_by_sid=NULL, approved_at=NULL, approval_signature=NULL WHERE id=?",
                    (_b64u(secrets.token_bytes(32)), duplicate["id"]),
                )
                quarantined_gateways.append(str(duplicate["sid"]))
            earliest = c.execute(
                "SELECT sig_pub FROM devices WHERE user_id = ? ORDER BY created_at, id LIMIT 1",
                (uid,),
            ).fetchone()
            if earliest:
                c.execute(
                    "UPDATE users SET identity_sig_pub = CASE WHEN identity_sig_pub = '' THEN ? ELSE identity_sig_pub END, "
                    "security_epoch = CASE WHEN security_epoch = 0 THEN 1 ELSE security_epoch END, "
                    "trust_enforced_at = COALESCE(trust_enforced_at, ?) WHERE id = ?",
                    (earliest["sig_pub"], timestamp, uid),
                )
                if legacy_trust_migration:
                    c.execute(
                        "UPDATE devices SET approved_by_sid = COALESCE(approved_by_sid, 'legacy_tofu'), approved_at = COALESCE(approved_at, created_at) WHERE user_id = ? AND trust_state = 'approved'",
                        (uid,),
                    )
                    c.execute(
                        "UPDATE devices SET verification_state='legacy_unverified' WHERE user_id=?",
                        (uid,),
                    )
            if quarantined_gateways:
                epoch = int(c.execute("SELECT security_epoch FROM users WHERE id=?", (uid,)).fetchone()["security_epoch"]) + 1
                c.execute("UPDATE users SET security_epoch=? WHERE id=?", (epoch, uid))
                directory_hash = _refresh_directory_locked(c, uid)
                _append_security_event_locked(
                    c,
                    uid,
                    "legacy_gateway_quarantined",
                    None,
                    None,
                    epoch,
                    {"sids": quarantined_gateways, "directory_hash": directory_hash},
                )
            else:
                _refresh_directory_locked(c, uid)
        c.execute("DROP INDEX IF EXISTS idx_one_android_gateway_per_user")
        # Triggers enforce the invariant for all future writes while allowing a
        # pathological legacy database with duplicate gateways to migrate
        # without deleting or silently reclassifying either device.
        c.execute("""
            CREATE TRIGGER IF NOT EXISTS one_approved_gateway_insert
            BEFORE INSERT ON devices
            WHEN NEW.kind = 'android_gateway' AND NEW.trust_state = 'approved'
             AND EXISTS(SELECT 1 FROM devices WHERE user_id = NEW.user_id AND kind = 'android_gateway' AND trust_state = 'approved')
            BEGIN SELECT RAISE(ABORT, 'approved Android gateway already exists'); END
        """)
        c.execute("""
            CREATE TRIGGER IF NOT EXISTS one_approved_gateway_update
            BEFORE UPDATE OF kind, trust_state ON devices
            WHEN NEW.kind = 'android_gateway' AND NEW.trust_state = 'approved'
             AND EXISTS(SELECT 1 FROM devices WHERE user_id = NEW.user_id AND id != OLD.id AND kind = 'android_gateway' AND trust_state = 'approved')
            BEGIN SELECT RAISE(ABORT, 'approved Android gateway already exists'); END
        """)
        c.execute("""
            CREATE TRIGGER IF NOT EXISTS devices_keys_immutable
            BEFORE UPDATE OF pub_key, sig_pub ON devices
            WHEN NEW.pub_key != OLD.pub_key OR NEW.sig_pub != OLD.sig_pub
            BEGIN
                SELECT RAISE(ABORT, 'device public keys are immutable');
            END
        """)
        c.execute("""
            CREATE TRIGGER IF NOT EXISTS users_identity_key_immutable
            BEFORE UPDATE OF identity_sig_pub ON users
            WHEN OLD.identity_sig_pub != '' AND NEW.identity_sig_pub != OLD.identity_sig_pub
            BEGIN
                SELECT RAISE(ABORT, 'account identity key is immutable');
            END
        """)
        # Cross-device shared block rules (v0.6.0). Values are user-entered
        # filter strings, synced per user; each device still applies them
        # locally to plaintext after decryption.
        c.execute(
            """
            CREATE TABLE IF NOT EXISTS block_rules (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                type       TEXT    NOT NULL CHECK(type IN ('keyword', 'sender')),
                value      TEXT    NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE (user_id, type, value)
            )
            """
        )
        c.execute(
            "CREATE INDEX IF NOT EXISTS idx_block_rules_user ON block_rules(user_id)"
        )
        c.execute("COMMIT")


def now() -> int:
    return int(time.time())


def _refresh_directory_locked(c: sqlite3.Connection, user_id: int) -> str:
    rows = c.execute(
        "SELECT sid, pub_key, sig_pub, kind FROM devices "
        "WHERE user_id = ? AND trust_state = 'approved' ORDER BY sid",
        (user_id,),
    ).fetchall()
    digest = _directory_hash_rows(rows)
    c.execute("UPDATE users SET directory_hash = ? WHERE id = ?", (digest, user_id))
    return digest


def _append_security_event_locked(
    c: sqlite3.Connection,
    user_id: int,
    event_type: str,
    actor_sid: str | None,
    subject_sid: str | None,
    security_epoch: int,
    details: dict[str, Any],
) -> dict[str, Any]:
    previous = c.execute(
        "SELECT event_hash FROM security_events WHERE user_id = ? ORDER BY id DESC LIMIT 1",
        (user_id,),
    ).fetchone()
    previous_hash = str(previous["event_hash"]) if previous else ""
    created_at = now()
    event_json = json.dumps(details, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    material = json.dumps(
        {
            "user_id": user_id,
            "event_type": event_type,
            "actor_sid": actor_sid,
            "subject_sid": subject_sid,
            "security_epoch": security_epoch,
            "event_json": event_json,
            "previous_hash": previous_hash,
            "created_at": created_at,
        },
        sort_keys=True,
        separators=(",", ":"),
    ).encode()
    event_hash = _b64u(hashlib.sha256(material).digest())
    signature = _b64u(
        hmac.new(config.JWT_SECRET.encode(), event_hash.encode(), hashlib.sha256).digest()
    )
    cur = c.execute(
        "INSERT INTO security_events(user_id,event_type,actor_sid,subject_sid,security_epoch,event_json,previous_hash,event_hash,server_signature,created_at) "
        "VALUES (?,?,?,?,?,?,?,?,?,?)",
        (user_id, event_type, actor_sid, subject_sid, security_epoch, event_json, previous_hash, event_hash, signature, created_at),
    )
    return {"id": cur.lastrowid, "event_hash": event_hash, "server_signature": signature}


# ----- users -------------------------------------------------------------


def create_user(username: str, pw_hash: str, email: str | None = None, email_verified_at: int | None = None) -> int:
    with conn_ctx() as c:
        cur = c.execute(
            "INSERT INTO users(username, pw_hash, created_at, email, email_verified_at) VALUES (?, ?, ?, ?, ?)",
            (username, pw_hash, now(), email, email_verified_at),
        )
        return cur.lastrowid


def get_user_by_name(username: str) -> dict[str, Any] | None:
    with conn_ctx() as c:
        row = c.execute(
            "SELECT id, username, pw_hash, email, email_verified_at, created_at, identity_sig_pub, security_epoch, directory_hash, trust_enforced_at, security_mode FROM users WHERE username = ?",
            (username,),
        ).fetchone()
        return dict(row) if row else None


def get_user_by_email(email: str) -> dict[str, Any] | None:
    with conn_ctx() as c:
        row = c.execute(
            "SELECT id, username, pw_hash, email, email_verified_at, created_at, identity_sig_pub, security_epoch, directory_hash, trust_enforced_at, security_mode FROM users WHERE email = ?",
            (email,),
        ).fetchone()
        return dict(row) if row else None


_CHALLENGE_TABLES = (
    "email_verification_challenges",
    "password_reset_challenges",
    "device_login_challenges",
    "pairing_sessions",
)


def _prune_challenges_locked(c: sqlite3.Connection, timestamp: int) -> int:
    """Drop one-time challenges that can no longer be used, inside the caller's
    open transaction.

    Nothing else ever deleted from these tables, so a busy relay accumulated a
    row per verification, reset, device login and QR scan forever. A challenge
    is dead once it is consumed or expired; keeping a retention window past
    that leaves recent rows around for debugging.
    """
    cutoff = timestamp - config.CHALLENGE_RETENTION_SECONDS
    removed = 0
    for table in _CHALLENGE_TABLES:
        cur = c.execute(
            f"DELETE FROM {table} WHERE created_at < ? "  # noqa: S608 - fixed table names
            "AND (consumed_at IS NOT NULL OR expires_at < ?)",
            (cutoff, timestamp),
        )
        removed += cur.rowcount if cur.rowcount > 0 else 0
    return removed


def prune_challenges() -> int:
    """Standalone sweep, for a maintenance call or a test."""
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        try:
            removed = _prune_challenges_locked(c, now())
            c.execute("COMMIT")
            return removed
        except Exception:
            c.execute("ROLLBACK")
            raise


def create_email_verification_challenge(
    challenge_id: str, email: str, username: str, pw_hash: str,
    code_digest: str, expires_at: int,
) -> None:
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        _prune_challenges_locked(c, now())
        c.execute(
            "UPDATE email_verification_challenges SET consumed_at=COALESCE(consumed_at, created_at) WHERE email=? AND consumed_at IS NULL",
            (email,),
        )
        c.execute(
            "INSERT INTO email_verification_challenges(challenge_id,email,username,pw_hash,code_digest,expires_at,created_at) VALUES(?,?,?,?,?,?,?)",
            (challenge_id, email, username, pw_hash, code_digest, expires_at, now()),
        )
        c.execute("COMMIT")


def consume_email_verification(
    challenge_id: str, code_digest: str, timestamp: int,
) -> dict[str, Any] | None:
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        row = c.execute(
            "SELECT email,username,pw_hash,code_digest,expires_at,attempts,consumed_at FROM email_verification_challenges WHERE challenge_id=?",
            (challenge_id,),
        ).fetchone()
        if (not row or row["consumed_at"] is not None or int(row["expires_at"]) < timestamp
                or int(row["attempts"]) >= 5 or not hmac.compare_digest(row["code_digest"], code_digest)):
            if row and row["consumed_at"] is None and int(row["attempts"]) < 5:
                c.execute("UPDATE email_verification_challenges SET attempts=attempts+1 WHERE challenge_id=?", (challenge_id,))
                c.execute("COMMIT")
            else:
                c.execute("ROLLBACK")
            return None
        c.execute("UPDATE email_verification_challenges SET consumed_at=? WHERE challenge_id=?", (timestamp, challenge_id))
        result = {"email": row["email"], "username": row["username"], "pw_hash": row["pw_hash"]}
        c.execute("COMMIT")
        return result


def create_password_reset_challenge(
    challenge_id: str, user_id: int, email: str, code_digest: str, expires_at: int,
) -> None:
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        _prune_challenges_locked(c, now())
        c.execute(
            "UPDATE password_reset_challenges SET consumed_at=COALESCE(consumed_at, created_at) WHERE user_id=? AND consumed_at IS NULL",
            (user_id,),
        )
        c.execute(
            "INSERT INTO password_reset_challenges(challenge_id,user_id,email,code_digest,expires_at,created_at) VALUES(?,?,?,?,?,?)",
            (challenge_id, user_id, email, code_digest, expires_at, now()),
        )
        c.execute("COMMIT")


def consume_password_reset(
    challenge_id: str,
    email: str,
    code_digest: str,
    new_pw_hash: Callable[[], str],
    timestamp: int,
) -> str | None:
    """Verify a reset code and, only then, materialize the new password hash.

    ``new_pw_hash`` is a callable rather than a value so the caller's bcrypt
    work happens after the code, expiry, attempt count and mailbox all check
    out — a rejected code must not cost the server a key-derivation.
    """
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        row = c.execute(
            "SELECT user_id,email,code_digest,expires_at,attempts,consumed_at FROM password_reset_challenges WHERE challenge_id=?",
            (challenge_id,),
        ).fetchone()
        if (not row or row["consumed_at"] is not None or int(row["expires_at"]) < timestamp
                or int(row["attempts"]) >= 5 or row["email"] != email
                or not hmac.compare_digest(row["code_digest"], code_digest)):
            if row and row["consumed_at"] is None and int(row["attempts"]) < 5:
                c.execute("UPDATE password_reset_challenges SET attempts=attempts+1 WHERE challenge_id=?", (challenge_id,))
                c.execute("COMMIT")
            else:
                c.execute("ROLLBACK")
            return None
        user = c.execute("SELECT username,email FROM users WHERE id=?", (row["user_id"],)).fetchone()
        if not user or user["email"] != email:
            c.execute("ROLLBACK")
            return None
        c.execute("UPDATE password_reset_challenges SET consumed_at=? WHERE challenge_id=?", (timestamp, challenge_id))
        # Possession of the reset mailbox proves ownership of a previously
        # linked address, so the first successful reset also verifies it.
        c.execute(
            "UPDATE users SET pw_hash=?, email_verified_at=COALESCE(email_verified_at, ?) WHERE id=?",
            (new_pw_hash(), timestamp, row["user_id"]),
        )
        c.execute("UPDATE devices SET session_version=session_version+1 WHERE user_id=?", (row["user_id"],))
        c.execute("COMMIT")
        return str(user["username"])


def _get_user_with_conn(c: sqlite3.Connection, uid: int) -> dict[str, Any] | None:
    row = c.execute(
        "SELECT id, username, created_at, identity_sig_pub, security_epoch, directory_hash, trust_enforced_at, security_mode FROM users WHERE id = ?",
        (uid,),
    ).fetchone()
    return dict(row) if row else None


def get_user(uid: int) -> dict[str, Any] | None:
    with conn_ctx() as c:
        return _get_user_with_conn(c, uid)


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
                    "SELECT COUNT(*) AS count FROM devices WHERE user_id = ? AND trust_state != 'revoked'",
                    (user_id,),
                ).fetchone()
                if int(row["count"]) >= max_devices:
                    raise ValueError("device limit reached")
            timestamp = now()
            total_count = c.execute(
                "SELECT COUNT(*) AS count FROM devices WHERE user_id = ?",
                (user_id,),
            ).fetchone()["count"]
            # Only a genuinely new account may self-bootstrap. Tombstones are
            # retained, so revoking every device cannot silently reset trust.
            trust_state = "approved" if int(total_count) == 0 else "pending"
            challenge = _b64u(secrets.token_bytes(32))
            fingerprint = device_fingerprint(pub_key, sig_pub)
            cur = c.execute(
                "INSERT INTO devices(user_id, sid, name, kind, pub_key, sig_pub, "
                "session_version, trust_state, challenge, approved_by_sid, approved_at, fingerprint, verification_state, created_at, last_seen) "
                "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, 'verified', ?, ?)",
                (user_id, sid, name, kind, pub_key, sig_pub, trust_state, challenge, sid if trust_state == "approved" else None, timestamp if trust_state == "approved" else None, fingerprint, timestamp, timestamp),
            )
            user = c.execute("SELECT security_epoch, identity_sig_pub FROM users WHERE id = ?", (user_id,)).fetchone()
            if trust_state == "approved":
                epoch = int(user["security_epoch"]) + 1
                c.execute(
                    "UPDATE users SET identity_sig_pub = CASE WHEN identity_sig_pub = '' THEN ? ELSE identity_sig_pub END, security_epoch = ?, trust_enforced_at = COALESCE(trust_enforced_at, ?) WHERE id = ?",
                    (sig_pub, epoch, timestamp, user_id),
                )
                directory_hash = _refresh_directory_locked(c, user_id)
                _append_security_event_locked(c, user_id, "device_bootstrap", sid, sid, epoch, {"fingerprint": fingerprint, "directory_hash": directory_hash, "legacy_tofu": False})
            else:
                epoch = int(user["security_epoch"])
                _append_security_event_locked(c, user_id, "device_pending", sid, sid, epoch, {"fingerprint": fingerprint})
            c.execute("COMMIT")
            return cur.lastrowid
        except Exception:
            c.execute("ROLLBACK")
            raise


def get_device_by_sid(sid: str) -> dict[str, Any] | None:
    with conn_ctx() as c:
        row = c.execute(
            "SELECT d.id, d.user_id, d.sid, d.name, d.kind, d.pub_key, d.sig_pub, "
            "d.session_version, d.trust_state, d.challenge, d.approved_by_sid, d.approved_at, d.approval_signature, d.fingerprint, d.revoked_at, d.verification_state, d.created_at, d.last_seen, "
            "u.username, u.identity_sig_pub, u.security_epoch, u.directory_hash, u.security_mode FROM devices d JOIN users u ON u.id = d.user_id WHERE d.sid = ?",
            (sid,),
        ).fetchone()
        return dict(row) if row else None


def _list_user_devices_with_conn(
    c: sqlite3.Connection, user_id: int
) -> list[dict[str, Any]]:
    rows = c.execute(
        "SELECT id, sid, name, kind, pub_key, sig_pub, session_version, trust_state, challenge, approved_by_sid, approved_at, approval_signature, fingerprint, revoked_at, verification_state, created_at, last_seen "
        "FROM devices WHERE user_id = ? ORDER BY created_at, id",
        (user_id,),
    ).fetchall()
    return [dict(r) for r in rows]


def list_user_devices(user_id: int) -> list[dict[str, Any]]:
    with conn_ctx() as c:
        return _list_user_devices_with_conn(c, user_id)


def touch_device(device_id: int) -> None:
    with conn_ctx() as c:
        c.execute("UPDATE devices SET last_seen = ? WHERE id = ?", (now(), device_id))


def rotate_device_session(
    device_id: int,
    user_id: int,
    expected_version: int | None = None,
    require_not_revoked: bool = False,
) -> int | None:
    """Atomically revoke existing JWTs for one device and return its new version.

    ``expected_version`` prevents an older request from revoking a newer login
    that won a race after the request was authenticated.

    ``require_not_revoked`` makes the trust-state check part of the same SQL
    update as the version rotation.  This is used by password-based device
    login so a concurrent revocation cannot be followed by token issuance.
    """
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        try:
            params: tuple[Any, ...]
            where = "id = ? AND user_id = ?"
            params = (device_id, user_id)
            if expected_version is not None:
                where += " AND session_version = ?"
                params += (expected_version,)
            if require_not_revoked:
                where += " AND trust_state != 'revoked'"
            cur = c.execute(
                "UPDATE devices SET session_version = session_version + 1, "
                "last_seen = ? WHERE " + where,
                (now(), *params),
            )
            if cur.rowcount != 1:
                c.execute("ROLLBACK")
                return None
            row = c.execute(
                "SELECT session_version FROM devices WHERE id = ?",
                (device_id,),
            ).fetchone()
            c.execute("COMMIT")
            return int(row["session_version"])
        except Exception:
            c.execute("ROLLBACK")
            raise


def create_device_login_challenge(device_id: int, user_id: int, sid: str, session_version: int) -> dict[str, Any] | None:
    """Persist a challenge bound to the device's current session generation."""
    timestamp = now()
    record = {
        "challenge_id": secrets.token_urlsafe(18),
        "challenge": _b64u(secrets.token_bytes(32)),
        "expires_at": timestamp + 120,
    }
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        _prune_challenges_locked(c, timestamp)
        current = c.execute(
            "SELECT session_version,trust_state FROM devices WHERE id=? AND user_id=? AND sid=?",
            (device_id, user_id, sid),
        ).fetchone()
        if not current or current["trust_state"] == "revoked" or int(current["session_version"]) != session_version:
            c.execute("ROLLBACK")
            return None
        c.execute(
            "INSERT INTO device_login_challenges(challenge_id,user_id,device_id,sid,challenge,session_version,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?)",
            (record["challenge_id"], user_id, device_id, sid, record["challenge"], session_version, record["expires_at"], timestamp),
        )
        c.execute("COMMIT")
    return {**record, "session_version": session_version}


def consume_device_login_challenge(
    challenge_id: str, challenge: str, device_id: int, user_id: int, sid: str,
    session_version: int,
) -> tuple[int, str] | None:
    """Consume one exact unexpired proof and rotate the JWT generation atomically."""
    timestamp = now()
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        row = c.execute(
            "SELECT challenge,session_version,expires_at,consumed_at FROM device_login_challenges "
            "WHERE challenge_id=? AND user_id=? AND device_id=? AND sid=?",
            (challenge_id, user_id, device_id, sid),
        ).fetchone()
        device = c.execute(
            "SELECT trust_state,session_version FROM devices WHERE id=? AND user_id=? AND sid=?",
            (device_id, user_id, sid),
        ).fetchone()
        if (not row or row["consumed_at"] is not None or int(row["expires_at"]) < timestamp
                or row["challenge"] != challenge or int(row["session_version"]) != session_version
                or not device or device["trust_state"] == "revoked"
                or int(device["session_version"]) != session_version):
            c.execute("ROLLBACK")
            return None
        consumed = c.execute(
            "UPDATE device_login_challenges SET consumed_at=? WHERE challenge_id=? AND consumed_at IS NULL",
            (timestamp, challenge_id),
        )
        rotated = c.execute(
            "UPDATE devices SET session_version=session_version+1,last_seen=? "
            "WHERE id=? AND user_id=? AND sid=? AND session_version=? AND trust_state!='revoked'",
            (timestamp, device_id, user_id, sid, session_version),
        )
        if consumed.rowcount != 1 or rotated.rowcount != 1:
            c.execute("ROLLBACK")
            return None
        c.execute("COMMIT")
        return session_version + 1, str(device["trust_state"])


def create_pairing_session(
    user_id: int,
    approver_sid: str,
    subject_sid: str,
    challenge: str,
    nonce_new: str,
    ttl_seconds: int = 120,
) -> dict[str, Any]:
    """Create a single-use QR pairing session bound to one pending device.

    Raises ``LookupError`` with a client-facing reason when the subject is not
    a pending device of the same account or its registration challenge does
    not match, and ``ValueError`` when the approver is no longer approved.
    A rescan replaces the subject's previous live session (QR nonce binding
    must be unique at any moment).
    """
    pairing_id = secrets.token_urlsafe(18)
    nonce_approver = _b64u(secrets.token_bytes(32))
    timestamp = now()
    expires_at = timestamp + ttl_seconds
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        try:
            _prune_challenges_locked(c, timestamp)
            approver = c.execute(
                "SELECT trust_state FROM devices WHERE user_id = ? AND sid = ?",
                (user_id, approver_sid),
            ).fetchone()
            if not approver or approver["trust_state"] != "approved":
                raise ValueError("approver is no longer approved")
            subject = c.execute(
                "SELECT trust_state, challenge FROM devices WHERE user_id = ? AND sid = ?",
                (user_id, subject_sid),
            ).fetchone()
            if not subject:
                raise LookupError("subject device not found")
            if subject["trust_state"] != "pending":
                raise LookupError("subject device is not pending")
            if str(subject["challenge"]) != challenge:
                raise LookupError("subject challenge mismatch")
            epoch = int(c.execute(
                "SELECT security_epoch FROM users WHERE id = ?", (user_id,)
            ).fetchone()["security_epoch"])
            c.execute(
                "UPDATE pairing_sessions SET consumed_at = COALESCE(consumed_at, created_at) "
                "WHERE subject_sid = ? AND consumed_at IS NULL",
                (subject_sid,),
            )
            c.execute(
                "INSERT INTO pairing_sessions(pairing_id,user_id,subject_sid,approver_sid,nonce_new,nonce_approver,expires_at,created_at) "
                "VALUES (?,?,?,?,?,?,?,?)",
                (pairing_id, user_id, subject_sid, approver_sid, nonce_new, nonce_approver, expires_at, timestamp),
            )
            _append_security_event_locked(
                c, user_id, "pairing_session_created", approver_sid, subject_sid, epoch,
                {"pairing_id": pairing_id},
            )
            c.execute("COMMIT")
        except Exception:
            c.execute("ROLLBACK")
            raise
    return {"pairing_id": pairing_id, "nonce_approver": nonce_approver, "expires_at": expires_at}


def get_live_pairing_session(subject_sid: str, timestamp: int) -> dict[str, Any] | None:
    with conn_ctx() as c:
        row = c.execute(
            "SELECT pairing_id, nonce_approver, expires_at FROM pairing_sessions "
            "WHERE subject_sid = ? AND consumed_at IS NULL AND expires_at > ? "
            "ORDER BY rowid DESC LIMIT 1",
            (subject_sid, timestamp),
        ).fetchone()
        return dict(row) if row else None


def revoke_device(
    device_id: int,
    user_id: int,
    actor_sid: str,
    actor_session_version: int,
    parent_epoch: int,
    statement: str,
    signature: str,
) -> bool:
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        actor = c.execute(
            "SELECT trust_state,session_version FROM devices WHERE user_id = ? AND sid = ?",
            (user_id, actor_sid),
        ).fetchone()
        if not actor or actor["trust_state"] != "approved" or int(actor["session_version"]) != actor_session_version:
            c.execute("ROLLBACK")
            raise PermissionError("actor is no longer approved")
        row = c.execute("SELECT sid, trust_state FROM devices WHERE id = ? AND user_id = ?", (device_id, user_id)).fetchone()
        if not row or row["trust_state"] == "revoked":
            c.execute("ROLLBACK")
            return False
        if row["trust_state"] != "approved":
            c.execute("ROLLBACK")
            raise ValueError("device is not approved")
        current_epoch = int(c.execute(
            "SELECT security_epoch FROM users WHERE id=?", (user_id,)
        ).fetchone()["security_epoch"])
        if current_epoch != parent_epoch:
            c.execute("ROLLBACK")
            raise RuntimeError("security epoch changed")
        cur = c.execute(
            "UPDATE devices SET trust_state = 'revoked', revoked_at = ?, session_version = session_version + 1 WHERE id = ? AND user_id = ?",
            (now(), device_id, user_id),
        )
        epoch = parent_epoch + 1
        c.execute("UPDATE users SET security_epoch = ? WHERE id = ?", (epoch, user_id))
        directory_hash = _refresh_directory_locked(c, user_id)
        c.execute(
            "INSERT INTO device_revocations(user_id,subject_sid,actor_sid,parent_epoch,resulting_epoch,reason,statement,signature,created_at) VALUES (?,?,?,?,?,'user_revoked',?,?,?)",
            (user_id, row["sid"], actor_sid, parent_epoch, epoch, statement, signature, now()),
        )
        _append_security_event_locked(c, user_id, "device_revoked", actor_sid, row["sid"], epoch, {"directory_hash": directory_hash, "statement": statement, "signature": signature})
        c.execute("COMMIT")
        return cur.rowcount > 0


def cancel_pending_device(
    device_id: int, user_id: int, sid: str, session_version: int
) -> bool:
    """Atomically cancel exactly the pending device authenticated by its JWT."""
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        cur = c.execute(
            "UPDATE devices SET trust_state='revoked', revoked_at=?, session_version=session_version+1 "
            "WHERE id=? AND user_id=? AND sid=? AND session_version=? AND trust_state='pending'",
            (now(), device_id, user_id, sid, session_version),
        )
        if cur.rowcount != 1:
            c.execute("ROLLBACK")
            return False
        epoch = int(c.execute("SELECT security_epoch FROM users WHERE id=?", (user_id,)).fetchone()["security_epoch"])
        directory_hash = _refresh_directory_locked(c, user_id)
        _append_security_event_locked(c, user_id, "device_revoked", sid, sid, epoch, {"directory_hash": directory_hash, "pending_cancel": True})
        c.execute("COMMIT")
        return True


def reject_pending_device(
    user_id: int,
    actor_sid: str,
    actor_session_version: int,
    subject_sid: str,
    challenge: str,
    parent_epoch: int,
) -> bool:
    """Approved-device rejection of an untrusted registration; no directory change."""
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        actor = c.execute(
            "SELECT trust_state,session_version FROM devices WHERE user_id=? AND sid=?",
            (user_id, actor_sid),
        ).fetchone()
        user = c.execute("SELECT security_epoch FROM users WHERE id=?", (user_id,)).fetchone()
        if not actor or actor["trust_state"] != "approved" or int(actor["session_version"]) != actor_session_version:
            c.execute("ROLLBACK")
            raise PermissionError("actor is no longer approved")
        if int(user["security_epoch"]) != parent_epoch:
            c.execute("ROLLBACK")
            raise RuntimeError("security epoch changed")
        cur = c.execute(
            "UPDATE devices SET trust_state='revoked',revoked_at=?,session_version=session_version+1 "
            "WHERE user_id=? AND sid=? AND trust_state='pending' AND challenge=?",
            (now(), user_id, subject_sid, challenge),
        )
        if cur.rowcount != 1:
            c.execute("ROLLBACK")
            return False
        directory_hash = _refresh_directory_locked(c, user_id)
        _append_security_event_locked(c, user_id, "pending_device_rejected", actor_sid, subject_sid, parent_epoch, {"challenge": challenge, "directory_hash": directory_hash})
        c.execute("COMMIT")
        return True


def approve_pending_device(
    user_id: int,
    approver_sid: str,
    subject_sid: str,
    parent_epoch: int,
    statement: str,
    signature: str,
    approver_session_version: int,
    pairing: dict[str, str] | None = None,
) -> dict[str, Any]:
    """Approve once, conditional on the signed directory epoch.

    ``pairing`` carries the v2 QR binding (pairing_id, nonce_new,
    nonce_approver). The session is validated and consumed inside the same
    write transaction as the approval, so an expired, reused, or
    mismatched session can never produce an approval certificate.
    """
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        try:
            approver = c.execute(
                "SELECT trust_state, session_version FROM devices WHERE user_id = ? AND sid = ?",
                (user_id, approver_sid),
            ).fetchone()
            subject = c.execute(
                "SELECT trust_state FROM devices WHERE user_id = ? AND sid = ?",
                (user_id, subject_sid),
            ).fetchone()
            user = c.execute(
                "SELECT security_epoch FROM users WHERE id = ?", (user_id,)
            ).fetchone()
            if (
                not approver
                or approver["trust_state"] != "approved"
                or int(approver["session_version"]) != approver_session_version
            ):
                raise PermissionError("approver is not approved")
            if not subject:
                raise LookupError("device not found")
            if subject["trust_state"] != "pending":
                raise ValueError("device is not pending")
            if int(user["security_epoch"]) != parent_epoch:
                raise RuntimeError("security epoch changed")
            timestamp = now()
            if pairing is not None:
                session = c.execute(
                    "SELECT subject_sid, nonce_new, nonce_approver, expires_at, consumed_at "
                    "FROM pairing_sessions WHERE pairing_id = ?",
                    (pairing["pairing_id"],),
                ).fetchone()
                if (
                    not session
                    or session["consumed_at"] is not None
                    or int(session["expires_at"]) <= timestamp
                    or str(session["subject_sid"]) != subject_sid
                    or str(session["nonce_new"]) != pairing["nonce_new"]
                    or str(session["nonce_approver"]) != pairing["nonce_approver"]
                ):
                    raise ValueError("pairing session is invalid or expired")
            epoch = parent_epoch + 1
            c.execute(
                "UPDATE devices SET trust_state = 'approved', verification_state='verified', approved_by_sid = ?, approved_at = ?, approval_signature = ? WHERE user_id = ? AND sid = ? AND trust_state = 'pending'",
                (approver_sid, timestamp, signature, user_id, subject_sid),
            )
            c.execute(
                "INSERT INTO device_approvals(user_id,subject_sid,approver_sid,parent_epoch,resulting_epoch,statement,signature,created_at) VALUES (?,?,?,?,?,?,?,?)",
                (user_id, subject_sid, approver_sid, parent_epoch, epoch, statement, signature, timestamp),
            )
            if pairing is not None:
                c.execute(
                    "UPDATE pairing_sessions SET consumed_at = ? WHERE pairing_id = ? AND consumed_at IS NULL",
                    (timestamp, pairing["pairing_id"]),
                )
            c.execute("UPDATE users SET security_epoch = ? WHERE id = ?", (epoch, user_id))
            directory_hash = _refresh_directory_locked(c, user_id)
            _append_security_event_locked(c, user_id, "device_approved", approver_sid, subject_sid, epoch, {"directory_hash": directory_hash, "approval_signature": signature, "pairing": pairing is not None})
            c.execute("COMMIT")
            return {"security_epoch": epoch, "directory_hash": directory_hash}
        except Exception:
            c.execute("ROLLBACK")
            raise


def upgrade_legacy_security(
    user_id: int,
    actor_sid: str,
    actor_session_version: int,
    parent_epoch: int,
    statement: str,
    signature: str,
) -> dict[str, Any]:
    """Promote the legacy identity anchor and quarantine all uncertified peers."""
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        try:
            user = c.execute(
                "SELECT security_mode,security_epoch,identity_sig_pub FROM users WHERE id=?",
                (user_id,),
            ).fetchone()
            actor = c.execute(
                "SELECT sig_pub,trust_state,session_version FROM devices WHERE user_id=? AND sid=?",
                (user_id, actor_sid),
            ).fetchone()
            if not user or user["security_mode"] != "legacy_v1":
                raise ValueError("account is not legacy")
            if int(user["security_epoch"]) != parent_epoch:
                raise RuntimeError("security epoch changed")
            if (
                not actor
                or actor["trust_state"] != "approved"
                or int(actor["session_version"]) != actor_session_version
                or actor["sig_pub"] != user["identity_sig_pub"]
            ):
                raise PermissionError("legacy identity anchor required")
            timestamp = now()
            for peer in c.execute(
                "SELECT id FROM devices WHERE user_id=? AND sid!=? AND trust_state='approved'",
                (user_id, actor_sid),
            ).fetchall():
                c.execute(
                    "UPDATE devices SET trust_state='pending', verification_state='legacy_unverified', challenge=?, session_version=session_version+1, approved_by_sid=NULL, approved_at=NULL, approval_signature=NULL WHERE id=?",
                    (_b64u(secrets.token_bytes(32)), peer["id"]),
                )
            epoch = parent_epoch + 1
            c.execute(
                "UPDATE devices SET verification_state='verified', approved_by_sid=sid, approved_at=COALESCE(approved_at,?) WHERE user_id=? AND sid=?",
                (timestamp, user_id, actor_sid),
            )
            c.execute(
                "UPDATE users SET security_mode='verified_v2', security_epoch=?, trust_enforced_at=? WHERE id=?",
                (epoch, timestamp, user_id),
            )
            directory_hash = _refresh_directory_locked(c, user_id)
            c.execute(
                "INSERT INTO security_upgrades(user_id,identity_sid,parent_epoch,resulting_epoch,statement,signature,created_at) VALUES (?,?,?,?,?,?,?)",
                (user_id, actor_sid, parent_epoch, epoch, statement, signature, timestamp),
            )
            _append_security_event_locked(c, user_id, "legacy_security_upgraded", actor_sid, actor_sid, epoch, {"statement": statement, "signature": signature, "directory_hash": directory_hash})
            c.execute("COMMIT")
            return {"security_epoch": epoch, "directory_hash": directory_hash}
        except Exception:
            c.execute("ROLLBACK")
            raise


def _get_key_directory_with_conn(
    c: sqlite3.Connection, user_id: int
) -> dict[str, Any] | None:
    user = _get_user_with_conn(c, user_id)
    if not user:
        return None
    proof = _get_directory_proof_with_conn(c, user_id, user=user)
    return {
        "user_id": user_id,
        "identity_sig_pub": user["identity_sig_pub"],
        "security_epoch": user["security_epoch"],
        "directory_hash": user["directory_hash"],
        "trust_enforced_at": user["trust_enforced_at"],
        "security_mode": user["security_mode"],
        "devices": [
            d
            for d in _list_user_devices_with_conn(c, user_id)
            if d["trust_state"] == "approved"
        ],
        "device_history": proof["device_history"],
        "approval_certificates": proof["approval_certificates"],
        "revocation_certificates": proof["revocation_certificates"],
        "security_upgrade_certificates": proof["security_upgrade_certificates"],
    }


def get_key_directory(user_id: int) -> dict[str, Any] | None:
    with read_snapshot() as c:
        return _get_key_directory_with_conn(c, user_id)


def _get_directory_proof_with_conn(
    c: sqlite3.Connection,
    user_id: int,
    *,
    user: dict[str, Any] | None = None,
) -> dict[str, Any] | None:
    """Public verification material for an account device directory.

    Revoked devices that were once approved remain in device_history because
    they may be approvers in a still-valid descendant certificate chain.
    Never-approved pending/cancelled devices are intentionally omitted.
    """
    if user is None:
        user = _get_user_with_conn(c, user_id)
    if not user:
        return None
    history = c.execute(
        "SELECT sid,kind,pub_key,sig_pub,fingerprint,trust_state,challenge,approved_by_sid,approved_at,approval_signature,revoked_at,verification_state "
        "FROM devices WHERE user_id = ? AND (trust_state = 'approved' OR approved_at IS NOT NULL) ORDER BY created_at,id",
        (user_id,),
    ).fetchall()
    approvals = c.execute(
        "SELECT subject_sid,approver_sid,parent_epoch,resulting_epoch,statement,signature,created_at "
        "FROM device_approvals WHERE user_id = ? ORDER BY resulting_epoch,id",
        (user_id,),
    ).fetchall()
    revocations = c.execute(
        "SELECT subject_sid,actor_sid,parent_epoch,resulting_epoch,reason,statement,signature,created_at FROM device_revocations WHERE user_id=? ORDER BY resulting_epoch,id",
        (user_id,),
    ).fetchall()
    upgrades = c.execute(
        "SELECT identity_sid,parent_epoch,resulting_epoch,statement,signature,created_at FROM security_upgrades WHERE user_id=? ORDER BY id",
        (user_id,),
    ).fetchall()
    return {
        "user_id": user_id,
        "identity_sig_pub": user["identity_sig_pub"],
        "security_epoch": user["security_epoch"],
        "directory_hash": user["directory_hash"],
        "trust_enforced_at": user["trust_enforced_at"],
        "security_mode": user["security_mode"],
        "device_history": [dict(row) for row in history],
        "approval_certificates": [dict(row) for row in approvals],
        "revocation_certificates": [dict(row) for row in revocations],
        "security_upgrade_certificates": [dict(row) for row in upgrades],
    }


def get_directory_proof(user_id: int) -> dict[str, Any] | None:
    with read_snapshot() as c:
        return _get_directory_proof_with_conn(c, user_id)


def list_security_events(user_id: int, limit: int = 200, before_id: int | None = None) -> list[dict[str, Any]]:
    with conn_ctx() as c:
        where = "user_id = ?"
        params: tuple[Any, ...] = (user_id,)
        if before_id is not None:
            where += " AND id < ?"
            params += (before_id,)
        rows = c.execute(
            "SELECT id,event_type,actor_sid,subject_sid,security_epoch,event_json,previous_hash,event_hash,server_signature,created_at FROM security_events WHERE " + where + " ORDER BY id DESC LIMIT ?",
            (*params, limit),
        ).fetchall()
        result = []
        # Return the newest bounded page in chronological order so clients can
        # verify previous_hash as a forward chain without fetching all history.
        for row in reversed(rows):
            item = dict(row)
            item["details"] = json.loads(item.pop("event_json"))
            result.append(item)
        return result


def count_security_events(user_id: int) -> int:
    with conn_ctx() as c:
        row = c.execute(
            "SELECT COUNT(*) AS count FROM security_events WHERE user_id = ?",
            (user_id,),
        ).fetchone()
        return int(row["count"])


def count_security_events_before(user_id: int, before_id: int) -> int:
    with conn_ctx() as c:
        row = c.execute(
            "SELECT COUNT(*) AS count FROM security_events WHERE user_id=? AND id<?",
            (user_id, before_id),
        ).fetchone()
        return int(row["count"])


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
            "SELECT id, cid, name, synced_contact_name, created_at "
            "FROM conversations WHERE cid = ?",
            (cid,),
        ).fetchone()
        return dict(row) if row else None


def list_members(conv_id: int) -> list[dict[str, Any]]:
    """Return all devices of all members — needed to fan out envelope keys."""
    with conn_ctx() as c:
        rows = c.execute(
            "SELECT d.id AS device_id, d.sid, d.user_id, d.pub_key, d.sig_pub, d.name, d.kind, "
            "d.session_version, u.security_epoch, u.directory_hash, u.identity_sig_pub "
            "FROM conversation_members m "
            "JOIN devices d ON d.user_id = m.user_id "
            "JOIN users u ON u.id = d.user_id "
            "WHERE m.conv_id = ? AND d.trust_state = 'approved'",
            (conv_id,),
        ).fetchall()
        return [dict(r) for r in rows]


def get_conversation_directory_snapshot(cid: str) -> dict[str, Any] | None:
    """Materialize a conversation's members and trust proofs atomically."""
    with read_snapshot() as c:
        conv_row = c.execute(
            "SELECT id, cid, name, synced_contact_name, created_at "
            "FROM conversations WHERE cid = ?",
            (cid,),
        ).fetchone()
        if not conv_row:
            return None
        conv = dict(conv_row)
        members = [
            dict(row)
            for row in c.execute(
                "SELECT d.id AS device_id, d.sid, d.user_id, d.pub_key, d.sig_pub, d.name, d.kind, "
                "d.session_version, u.security_epoch, u.directory_hash, u.identity_sig_pub "
                "FROM conversation_members m "
                "JOIN devices d ON d.user_id = m.user_id "
                "JOIN users u ON u.id = d.user_id "
                "WHERE m.conv_id = ? AND d.trust_state = 'approved' "
                "ORDER BY d.user_id, d.sid, d.id",
                (conv["id"],),
            ).fetchall()
        ]
        user_ids = [
            int(row["user_id"])
            for row in c.execute(
                "SELECT user_id FROM conversation_members WHERE conv_id = ? "
                "ORDER BY user_id",
                (conv["id"],),
            ).fetchall()
        ]
        checkpoints = []
        proofs = []
        for user_id in user_ids:
            user = _get_user_with_conn(c, user_id)
            if not user:
                continue
            checkpoints.append(
                {
                    "user_id": user_id,
                    "identity_sig_pub": user["identity_sig_pub"],
                    "security_epoch": user["security_epoch"],
                    "directory_hash": user["directory_hash"],
                    "security_mode": user["security_mode"],
                }
            )
            proofs.append(_get_directory_proof_with_conn(c, user_id, user=user))
        return {
            "conversation": conv,
            "members": members,
            "directory_checkpoints": checkpoints,
            "directory_proofs": proofs,
        }


# ----- messages ---------------------------------------------------------


def next_seq(conv_id: int) -> int:
    with conn_ctx() as c:
        row = c.execute(
            "SELECT COALESCE(MAX(seq), 0) AS s FROM messages WHERE conv_id = ?",
            (conv_id,),
        ).fetchone()
        return int(row["s"]) + 1


def canonical_message_payload(payload: str | dict[str, Any]) -> str:
    """Return the stable JSON representation used for retry comparisons."""
    value = json.loads(payload) if isinstance(payload, str) else payload
    return json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    )


def message_retry_matches(
    existing: dict[str, Any],
    *,
    cid: str,
    payload: str | dict[str, Any],
    sender_pub_key: str,
) -> bool:
    """Check that a reused client_mid is the same security-bound message."""
    try:
        return (
            str(existing["cid"]) == cid
            and str(existing["sender_pub_key"]) == sender_pub_key
            and canonical_message_payload(existing["payload"])
            == canonical_message_payload(payload)
        )
    except (KeyError, TypeError, ValueError, json.JSONDecodeError):
        return False


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
                "SELECT pub_key FROM devices WHERE sid = ? AND user_id = ? AND trust_state = 'approved'",
                (sender_sid, sender_id),
            ).fetchone()
            if not sender:
                raise ValueError("sender device is revoked or unknown")
            if client_mid:
                existing = c.execute(
                    "SELECT m.id, m.seq, m.conv_id, cv.cid, m.sender_pub_key, m.payload "
                    "FROM messages m JOIN conversations cv ON cv.id = m.conv_id "
                    "WHERE m.sender_sid = ? AND m.client_mid = ?",
                    (sender_sid, client_mid),
                ).fetchone()
                if existing:
                    if int(existing["conv_id"]) != conv_id or not message_retry_matches(
                        dict(existing),
                        cid=str(existing["cid"]),
                        payload=payload,
                        sender_pub_key=str(sender["pub_key"]),
                    ):
                        raise ValueError("message id conflicts with original message")
                    c.execute("COMMIT")
                    return int(existing["id"]), int(existing["seq"]), False
            if expected_recipient_sids is not None:
                current_sids = {
                    str(row["sid"])
                    for row in c.execute(
                        "SELECT d.sid FROM conversation_members m "
                        "JOIN devices d ON d.user_id = m.user_id "
                        "WHERE m.conv_id = ? AND d.trust_state = 'approved'",
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
            "SELECT m.id, m.seq, m.conv_id, cv.cid, m.sender_pub_key, m.payload "
            "FROM messages m JOIN conversations cv ON cv.id = m.conv_id "
            "WHERE m.sender_sid = ? AND m.client_mid = ?",
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


# ----- shared block rules (cross-device sync) ---------------------------


def list_block_rules(user_id: int) -> list[dict[str, Any]]:
    with conn_ctx() as c:
        rows = c.execute(
            "SELECT id, type, value, created_at FROM block_rules "
            "WHERE user_id = ? ORDER BY created_at ASC, id ASC",
            (user_id,),
        ).fetchall()
        return [dict(r) for r in rows]


def add_block_rule(user_id: int, rule_type: str, value: str) -> dict[str, Any]:
    """Insert-or-keep a rule; always returns the canonical stored row.

    Raises ``ValueError('block rule limit reached')`` past
    ``config.MAX_BLOCK_RULES``. Every rule is pushed to all of the account's
    devices and evaluated against every decrypted message, so an unbounded
    list is a foot-gun even when only the owner can add to it.
    """
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        try:
            existing = c.execute(
                "SELECT id, type, value, created_at FROM block_rules "
                "WHERE user_id = ? AND type = ? AND value = ?",
                (user_id, rule_type, value),
            ).fetchone()
            if existing:
                c.execute("COMMIT")
                return dict(existing)
            count = c.execute(
                "SELECT COUNT(*) AS n FROM block_rules WHERE user_id = ?",
                (user_id,),
            ).fetchone()
            if int(count["n"]) >= config.MAX_BLOCK_RULES:
                raise ValueError("block rule limit reached")
            c.execute(
                "INSERT INTO block_rules(user_id, type, value, created_at) "
                "VALUES (?, ?, ?, ?)",
                (user_id, rule_type, value, now()),
            )
            row = c.execute(
                "SELECT id, type, value, created_at FROM block_rules "
                "WHERE user_id = ? AND type = ? AND value = ?",
                (user_id, rule_type, value),
            ).fetchone()
            c.execute("COMMIT")
            return dict(row)
        except Exception:
            c.execute("ROLLBACK")
            raise


def remove_block_rule(user_id: int, rule_id: int) -> bool:
    with conn_ctx() as c:
        cur = c.execute(
            "DELETE FROM block_rules WHERE id = ? AND user_id = ?",
            (rule_id, user_id),
        )
        return cur.rowcount > 0


# ----- conversation rename ----------------------------------------------


def update_conversation_name(conv_id: int, name: str) -> None:
    with conn_ctx() as c:
        c.execute("UPDATE conversations SET name = ? WHERE id = ?", (name, conv_id))


def sync_contact_names(
    user_id: int, entries: list[tuple[str, str]]
) -> str | None:
    """Validate and update a contact snapshot in one write transaction.

    Returns ``not_found`` or ``forbidden`` without applying any update, or
    ``None`` after all entries were committed.
    """
    if not entries:
        return None
    cids = [cid for cid, _ in entries]
    placeholders = ",".join("?" for _ in cids)
    with conn_ctx() as c:
        c.execute("BEGIN IMMEDIATE")
        try:
            rows = c.execute(
                "SELECT cv.id, cv.cid, "
                "EXISTS(SELECT 1 FROM conversation_members own "
                "WHERE own.conv_id = cv.id AND own.user_id = ?) AS owned, "
                "(SELECT COUNT(*) FROM conversation_members members "
                "WHERE members.conv_id = cv.id) AS member_count "
                f"FROM conversations cv WHERE cv.cid IN ({placeholders})",
                (user_id, *cids),
            ).fetchall()
            by_cid = {str(row["cid"]): row for row in rows}
            if any(cid not in by_cid for cid in cids):
                c.execute("ROLLBACK")
                return "not_found"
            if any(
                not bool(by_cid[cid]["owned"])
                or int(by_cid[cid]["member_count"]) != 1
                for cid in cids
            ):
                c.execute("ROLLBACK")
                return "forbidden"
            c.executemany(
                "UPDATE conversations SET synced_contact_name = ? WHERE id = ?",
                (
                    (contact_name, int(by_cid[cid]["id"]))
                    for cid, contact_name in entries
                ),
            )
            c.execute("COMMIT")
            return None
        except Exception:
            c.execute("ROLLBACK")
            raise


def list_member_user_ids(conv_id: int) -> list[int]:
    with conn_ctx() as c:
        rows = c.execute(
            "SELECT user_id FROM conversation_members WHERE conv_id = ?",
            (conv_id,),
        ).fetchall()
        return [int(r["user_id"]) for r in rows]


def is_self_only_conversation_owner(conv_id: int, user_id: int) -> bool:
    """Return whether ``user_id`` is the conversation's sole member.

    Carrier authorization uses the account membership itself as the ownership
    boundary.  Device membership alone is insufficient because every approved
    device of every participant appears in ``list_members``.
    """
    with conn_ctx() as c:
        row = c.execute(
            "SELECT COUNT(*) AS member_count, "
            "COALESCE(MAX(CASE WHEN user_id = ? THEN 1 ELSE 0 END), 0) AS owned "
            "FROM conversation_members WHERE conv_id = ?",
            (user_id, conv_id),
        ).fetchone()
        return bool(row and int(row["member_count"]) == 1 and int(row["owned"]) == 1)


def list_user_device_sids(user_id: int) -> list[str]:
    with conn_ctx() as c:
        rows = c.execute(
            "SELECT sid FROM devices WHERE user_id = ? AND trust_state = 'approved'",
            (user_id,),
        ).fetchall()
        return [str(r["sid"]) for r in rows]
