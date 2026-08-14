-- secure-msg server schema (SQLite)
-- Server stores ONLY: opaque usernames, device public keys, encrypted envelopes.
-- Private keys and plaintext NEVER touch the server. Since v0.6 the server
-- also stores user-entered block RULES (keyword/sender strings) so they can
-- sync across devices — filter strings only, never message content.

PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

-- Users: identified by opaque username. No PII.
-- `pw_hash` is bcrypt hash of client-side-stretched password (we still hash server-side too).
CREATE TABLE IF NOT EXISTS users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    NOT NULL UNIQUE,
    pw_hash       TEXT    NOT NULL,
    created_at    INTEGER NOT NULL,
    identity_sig_pub TEXT NOT NULL DEFAULT '',
    security_epoch INTEGER NOT NULL DEFAULT 0,
    directory_hash TEXT NOT NULL DEFAULT '',
    trust_enforced_at INTEGER
    ,security_mode TEXT NOT NULL DEFAULT 'verified_v2',
    email         TEXT UNIQUE,
    email_verified_at INTEGER
);

-- Devices: one user can have many devices. Each device owns its keypair (private key stays client-side).
-- `pub_key`  : X25519 public key (32 raw bytes, base64)
-- `sig_pub`  : Ed25519 signing public key (32 raw bytes, base64) for message authenticity
-- `sid`      : opaque short id used to address per-device envelope keys
CREATE TABLE IF NOT EXISTS devices (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sid           TEXT    NOT NULL UNIQUE,         -- 8-char opaque device id
    name          TEXT    NOT NULL,                 -- user-supplied label, e.g. "iPhone"
    kind          TEXT    NOT NULL DEFAULT 'web',   -- web | android_gateway
    pub_key       TEXT    NOT NULL,                 -- base64 X25519
    sig_pub       TEXT    NOT NULL,                 -- base64 Ed25519
    session_version INTEGER NOT NULL DEFAULT 1,     -- rotated to revoke issued JWTs
    trust_state   TEXT NOT NULL DEFAULT 'approved' CHECK(trust_state IN ('pending','approved','revoked')),
    challenge     TEXT NOT NULL DEFAULT '',
    approved_by_sid TEXT,
    approved_at   INTEGER,
    approval_signature TEXT,
    fingerprint   TEXT NOT NULL DEFAULT '',
    revoked_at    INTEGER,
    verification_state TEXT NOT NULL DEFAULT 'verified',
    created_at    INTEGER NOT NULL,
    last_seen     INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);

-- Short-lived, durable one-time proofs used to recover a JWT for an existing
-- device. Rows are retained after use so replay attempts fail across restarts.
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
);
CREATE INDEX IF NOT EXISTS idx_device_login_challenges_device
    ON device_login_challenges(device_id, created_at);

-- Short-lived email codes. Raw codes are never stored; code_digest is an
-- HMAC keyed by the server JWT secret and the challenge id.
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
);
CREATE INDEX IF NOT EXISTS idx_email_verification_email
    ON email_verification_challenges(email, created_at);

CREATE TABLE IF NOT EXISTS password_reset_challenges (
    challenge_id TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    code_digest TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    consumed_at INTEGER,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_password_reset_user
    ON password_reset_challenges(user_id, created_at);

CREATE TABLE IF NOT EXISTS device_approvals (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    subject_sid TEXT NOT NULL,
    approver_sid TEXT NOT NULL,
    parent_epoch INTEGER NOT NULL,
    resulting_epoch INTEGER NOT NULL,
    statement TEXT NOT NULL,
    signature TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE(user_id, subject_sid)
);

CREATE TABLE IF NOT EXISTS security_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    actor_sid TEXT,
    subject_sid TEXT,
    security_epoch INTEGER NOT NULL,
    event_json TEXT NOT NULL,
    previous_hash TEXT NOT NULL,
    event_hash TEXT NOT NULL,
    server_signature TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS device_revocations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    subject_sid TEXT NOT NULL,
    actor_sid TEXT NOT NULL,
    parent_epoch INTEGER NOT NULL,
    resulting_epoch INTEGER NOT NULL,
    reason TEXT NOT NULL,
    statement TEXT NOT NULL,
    signature TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE(user_id, subject_sid)
);
CREATE TABLE IF NOT EXISTS security_upgrades (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    identity_sid TEXT NOT NULL,
    parent_epoch INTEGER NOT NULL,
    resulting_epoch INTEGER NOT NULL,
    statement TEXT NOT NULL,
    signature TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE(user_id)
);
CREATE INDEX IF NOT EXISTS idx_security_events_user_id ON security_events(user_id, id);

-- Conversations: identified by opaque cid. Membership list stored separately.
CREATE TABLE IF NOT EXISTS conversations (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    cid           TEXT    NOT NULL UNIQUE,          -- opaque conversation id
    name          TEXT    NOT NULL DEFAULT '',       -- stable SMS identity (normally phone number)
    synced_contact_name TEXT NOT NULL DEFAULT '',    -- user-synced display label
    created_at    INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS conversation_members (
    conv_id       INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    joined_at     INTEGER NOT NULL,
    PRIMARY KEY (conv_id, user_id)
);

-- Messages: the encrypted envelope. `payload` is a JSON blob:
--   { "ct":  base64 ciphertext (secretbox over plaintext),
--     "nonce": base64 nonce,
--     "keys": { "<device_sid>": {"ek": base64 encrypted message_key (box), "n": base64 nonce} } }
-- Server cannot decrypt; it only stores and fans out to member devices.
CREATE TABLE IF NOT EXISTS messages (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    seq           INTEGER NOT NULL,                -- monotonic per-conversation sequence
    conv_id       INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sender_sid    TEXT    NOT NULL,                -- which device sent it
    sender_pub_key TEXT   NOT NULL DEFAULT '',     -- immutable sender X25519 key snapshot
    client_mid    TEXT,                            -- sender-generated retry/idempotency id
    payload       TEXT    NOT NULL,                -- encrypted envelope JSON
    created_at    INTEGER NOT NULL,
    carrier_status TEXT   NOT NULL DEFAULT 'none', -- none|queued|dispatched|sent|delivered|failed|unknown
    carrier_error  TEXT,
    carrier_updated_at INTEGER,
    UNIQUE (conv_id, seq)
);
CREATE INDEX IF NOT EXISTS idx_messages_conv_seq ON messages(conv_id, seq);

-- Delivery cursors: per (device, conversation) last delivered seq.
-- Lets a reconnecting device pull only new messages.
CREATE TABLE IF NOT EXISTS delivery_cursors (
    device_id     INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    conv_id       INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    last_seq      INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (device_id, conv_id)
);
