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
    created_at    INTEGER NOT NULL
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
    created_at    INTEGER NOT NULL,
    last_seen     INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);

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
