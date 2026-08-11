/**
 * IndexedDB persistence layer.
 *
 * Stores client-side in IndexedDB. The browser profile/OS storage protection is
 * the at-rest boundary; message plaintext and private keys are not additionally
 * encrypted by this application:
 *   - meta: { username, uid, sid, deviceName, keypair, pubKey }  (current device)
 *   - devices: { sid -> { name, pub_key, user_id } }  (cache of known devices)
 *   - messages: { [cid+seq] -> { id, seq, cid, sender_sid, plaintext, created_at } }
 *   - cursors: { cid -> last_seq }
 *   - blocklist: { id, keyword, created_at }  (substring filter, applied after decrypt)
 */
import { openDB, type DBSchema, type IDBPDatabase } from "idb";
import type { DeviceKeypair } from "../crypto/keys";
import { serverDirectoryHash } from "../crypto/deviceTrust";
import { normalizePhone } from "./conversationPolicy";

interface MetaRow {
  key: "current";
  value: {
    username: string;
    uid: number;
    sid: string;
    deviceName: string;
    keypair: DeviceKeypair;
  };
}

export interface DeviceRow {
  sid: string;
  user_id: number;
  name: string;
  pub_key: string;
  sig_pub?: string;
}

export interface AccountTrustRow {
  uid: number;
  identity_sig_pub: string;
  security_epoch: number;
  directory_hash: string;
  updated_at: number;
}

export interface TrustedDeviceRow {
  id: string;
  uid: number;
  sid: string;
  pub_key: string;
  sig_pub: string;
  kind: string;
  fingerprint: string;
  first_seen_at: number;
  updated_at: number;
}

export interface TrustedDirectorySnapshot {
  uid: number;
  identity_sig_pub: string;
  security_epoch: number;
  directory_hash: string;
  devices: Array<{
    sid: string;
    pub_key: string;
    sig_pub: string;
    kind: string;
    fingerprint: string;
  }>;
}

export type TrustViolationCode = "identity_changed" | "rollback" | "equivocation" | "device_key_changed";

export class TrustViolationError extends Error {
  constructor(public readonly code: TrustViolationCode, message: string) {
    super(message);
    this.name = "TrustViolationError";
  }
}

export interface MessageRow {
  id: string; // `${cid}:${seq}` synthetic key
  seq: number;
  cid: string;
  sender_id: number;
  sender_sid: string;
  plaintext: string;
  created_at: number;
  blocked?: boolean;
  content_type?: "text" | "mms";
  subject?: string | null;
  attachments?: MessageAttachment[];
  carrier_status?: string;
  carrier_error?: string | null;
  carrier_updated_at?: number | null;
}

export interface MessageAttachment {
  name: string;
  content_type: string;
  data: string;
  size: number;
}

interface CursorRow {
  cid: string;
  last_seq: number;
}

export interface BlockRow {
  id: string;
  keyword: string;
  created_at: number;
}

export interface SenderRow {
  id: string;
  sender: string;
  created_at: number;
}

interface SecureMsgDB extends DBSchema {
  meta: { key: "current"; value: MetaRow };
  devices: { key: string; value: DeviceRow; indexes: { "by-user": number } };
  messages: {
    key: string; // `${cid}:${seq}`
    value: MessageRow;
    indexes: { "by-cid": string; "by-cid-seq": [string, number] };
  };
  cursors: { key: string; value: CursorRow };
  blocklist: { key: string; value: BlockRow; indexes: { "by-keyword": string } };
  blockedSenders: { key: string; value: SenderRow; indexes: { "by-sender": string } };
  accountTrust: { key: number; value: AccountTrustRow };
  trustedDevices: { key: string; value: TrustedDeviceRow; indexes: { "by-account": number } };
}

let _db: Promise<IDBPDatabase<SecureMsgDB>> | null = null;

export function db(): Promise<IDBPDatabase<SecureMsgDB>> {
  if (!_db) {
    _db = openDB<SecureMsgDB>("secure-msg", 3, {
      upgrade(d, oldVersion) {
        if (oldVersion < 1) {
          d.createObjectStore("meta");
          const devices = d.createObjectStore("devices", { keyPath: "sid" });
          devices.createIndex("by-user", "user_id");
          const messages = d.createObjectStore("messages", { keyPath: "id" });
          // We use a synthetic key `cid:seq` to dedupe. Store id = `${cid}:${seq}`.
          messages.createIndex("by-cid", "cid");
          messages.createIndex("by-cid-seq", ["cid", "seq"]);
          d.createObjectStore("cursors", { keyPath: "cid" });
          const block = d.createObjectStore("blocklist", { keyPath: "id" });
          block.createIndex("by-keyword", "keyword");
        }
        if (oldVersion < 2) {
          const senders = d.createObjectStore("blockedSenders", { keyPath: "id" });
          senders.createIndex("by-sender", "sender");
        }
        if (oldVersion < 3) {
          d.createObjectStore("accountTrust", { keyPath: "uid" });
          const trust = d.createObjectStore("trustedDevices", { keyPath: "id" });
          trust.createIndex("by-account", "uid");
        }
      },
    });
  }
  return _db;
}

// ----- meta -------------------------------------------------------------

export async function setMeta(meta: MetaRow["value"]): Promise<void> {
  const d = await db();
  // The `meta` store has NO keyPath (out-of-line keys), so put() requires the
  // key explicitly. Omitting it makes IndexedDB throw DataError ("Data provided
  // to an operation does not meet requirements."), which broke every web
  // login/register at the device-key persistence step.
  await d.put("meta", { key: "current", value: meta }, "current");
}

export async function getMeta(): Promise<MetaRow["value"] | null> {
  const d = await db();
  const row = await d.get("meta", "current");
  return row?.value ?? null;
}

export async function clearMeta(): Promise<void> {
  const d = await db();
  await d.delete("meta", "current");
}

/** Clear account content while retaining this browser's device keypair. */
export async function clearSessionData(): Promise<void> {
  const d = await db();
  const tx = d.transaction(["devices", "messages", "cursors"], "readwrite");
  await Promise.all([
    tx.objectStore("devices").clear(),
    tx.objectStore("messages").clear(),
    tx.objectStore("cursors").clear(),
  ]);
  await tx.done;
}

/** Clear all local data after this device is revoked. */
export async function clearAllData(): Promise<void> {
  const d = await db();
  const tx = d.transaction(
    ["meta", "devices", "messages", "cursors", "blocklist", "blockedSenders", "accountTrust", "trustedDevices"],
    "readwrite",
  );
  await Promise.all([
    tx.objectStore("meta").clear(),
    tx.objectStore("devices").clear(),
    tx.objectStore("messages").clear(),
    tx.objectStore("cursors").clear(),
    tx.objectStore("blocklist").clear(),
    tx.objectStore("blockedSenders").clear(),
    tx.objectStore("accountTrust").clear(),
    tx.objectStore("trustedDevices").clear(),
  ]);
  await tx.done;
}

// ----- devices ----------------------------------------------------------

export async function cacheDevice(row: DeviceRow): Promise<void> {
  const d = await db();
  await d.put("devices", row);
}

export async function getDevice(sid: string): Promise<DeviceRow | null> {
  const d = await db();
  return (await d.get("devices", sid)) ?? null;
}

export async function cacheDevices(rows: DeviceRow[]): Promise<void> {
  const d = await db();
  const tx = d.transaction("devices", "readwrite");
  await Promise.all(rows.map((r) => tx.store.put(r)));
  await tx.done;
}

// ----- trusted key directory ------------------------------------------

function trustedDeviceId(uid: number, sid: string): string {
  return `${uid}:${sid}`;
}

/**
 * Atomically pins an authenticated directory snapshot. It never silently
 * accepts identity changes, epoch rollback, a split view at the same epoch,
 * or changed keys for an already-pinned SID.
 */
export async function pinTrustedDirectory(snapshot: TrustedDirectorySnapshot): Promise<void> {
  if (!Number.isSafeInteger(snapshot.uid) || snapshot.uid < 0) throw new Error("invalid uid");
  if (!Number.isSafeInteger(snapshot.security_epoch) || snapshot.security_epoch < 0) throw new Error("invalid security epoch");
  if (!snapshot.identity_sig_pub || !snapshot.directory_hash) throw new Error("incomplete directory snapshot");
  const d = await db();
  const tx = d.transaction(["accountTrust", "trustedDevices"], "readwrite");
  const accountStore = tx.objectStore("accountTrust");
  const deviceStore = tx.objectStore("trustedDevices");
  const existingAccount = await accountStore.get(snapshot.uid);
  if (existingAccount) {
    if (existingAccount.identity_sig_pub !== snapshot.identity_sig_pub) {
      throw new TrustViolationError("identity_changed", "계정 신원 키가 변경되었습니다.");
    }
    if (snapshot.security_epoch < existingAccount.security_epoch) {
      throw new TrustViolationError("rollback", "키 디렉터리 롤백이 감지되었습니다.");
    }
    if (snapshot.security_epoch === existingAccount.security_epoch
      && snapshot.directory_hash !== existingAccount.directory_hash) {
      throw new TrustViolationError("equivocation", "같은 보안 버전에서 서로 다른 키 목록이 감지되었습니다.");
    }
  }

  const now = Date.now();
  const seen = new Set<string>();
  for (const candidate of snapshot.devices) {
    if (!candidate.sid || seen.has(candidate.sid)) {
      throw new Error("duplicate or empty device sid");
    }
    seen.add(candidate.sid);
    const id = trustedDeviceId(snapshot.uid, candidate.sid);
    const pinned = await deviceStore.get(id);
    if (pinned && (pinned.pub_key !== candidate.pub_key || pinned.sig_pub !== candidate.sig_pub)) {
      throw new TrustViolationError("device_key_changed", `기기 ${candidate.sid}의 공개키가 변경되었습니다.`);
    }
  }

  const calculatedHash = serverDirectoryHash(snapshot.devices.map((device) => ({
    sid: device.sid,
    pub_key: device.pub_key,
    sig_pub: device.sig_pub,
    kind: device.kind,
  })));
  if (calculatedHash !== snapshot.directory_hash) {
    throw new TrustViolationError("equivocation", "서버 디렉터리 해시와 공개키 목록이 일치하지 않습니다.");
  }

  // Validation is complete. No trust row is written before this point.
  for (const candidate of snapshot.devices) {
    const id = trustedDeviceId(snapshot.uid, candidate.sid);
    const pinned = await deviceStore.get(id);
    await deviceStore.put({
      id,
      uid: snapshot.uid,
      ...candidate,
      first_seen_at: pinned?.first_seen_at ?? now,
      updated_at: now,
    });
  }
  await accountStore.put({
    uid: snapshot.uid,
    identity_sig_pub: snapshot.identity_sig_pub,
    security_epoch: snapshot.security_epoch,
    directory_hash: snapshot.directory_hash,
    updated_at: now,
  });
  await tx.done;
}

export async function getAccountTrust(uid: number): Promise<AccountTrustRow | null> {
  return (await (await db()).get("accountTrust", uid)) ?? null;
}

export async function listTrustedDevices(uid: number): Promise<TrustedDeviceRow[]> {
  return await (await db()).getAllFromIndex("trustedDevices", "by-account", uid);
}

// ----- messages ---------------------------------------------------------

function msgKey(cid: string, seq: number): string {
  return `${cid}:${seq}`;
}

export async function putMessage(m: MessageRow): Promise<void> {
  const d = await db();
  const key = msgKey(m.cid, m.seq);
  // Single transaction: a concurrent setCarrierStatus() (socket event racing a
  // REST sync page) must not be able to slip a fresher carrier state in
  // between our read and write, or it would be silently overwritten.
  const tx = d.transaction("messages", "readwrite");
  const existing = await tx.store.get(key);
  const keepNewerCarrierState = existing
    && (existing.carrier_updated_at ?? 0) > (m.carrier_updated_at ?? 0);
  await tx.store.put({
    ...m,
    ...(keepNewerCarrierState ? {
      carrier_status: existing.carrier_status,
      carrier_error: existing.carrier_error,
      carrier_updated_at: existing.carrier_updated_at,
    } : {}),
    id: key,
  });
  await tx.done;
}

export async function listMessages(cid: string): Promise<MessageRow[]> {
  const d = await db();
  const rows = await d.getAllFromIndex("messages", "by-cid", cid);
  return rows.sort((a, b) => a.seq - b.seq);
}

export async function listAllMessages(): Promise<MessageRow[]> {
  const d = await db();
  return await d.getAll("messages");
}

export async function setBlocked(cid: string, seq: number, blocked: boolean): Promise<void> {
  const d = await db();
  const tx = d.transaction("messages", "readwrite");
  const existing = await tx.store.get(msgKey(cid, seq));
  if (!existing) {
    return;
  }
  await tx.store.put({ ...existing, blocked });
  await tx.done;
}

export async function setCarrierStatus(
  cid: string,
  seq: number,
  status: string,
  error: string | null = null,
  updatedAt: number | null = Date.now(),
): Promise<void> {
  const d = await db();
  const tx = d.transaction("messages", "readwrite");
  const existing = await tx.store.get(msgKey(cid, seq));
  if (!existing) {
    return;
  }
  if (!canAdvanceCarrierStatus(existing.carrier_status ?? "none", status)) {
    return;
  }
  if ((existing.carrier_updated_at ?? 0) > (updatedAt ?? 0)) {
    return;
  }
  await tx.store.put({
    ...existing,
    carrier_status: status,
    carrier_error: error,
    carrier_updated_at: updatedAt,
  });
  await tx.done;
}

// ----- cursors ----------------------------------------------------------

export async function getCursor(cid: string): Promise<number> {
  const d = await db();
  const row = await d.get("cursors", cid);
  return row?.last_seq ?? 0;
}

export async function setCursor(cid: string, last_seq: number): Promise<void> {
  const d = await db();
  const tx = d.transaction("cursors", "readwrite");
  const existing = await tx.store.get(cid);
  await tx.store.put({ cid, last_seq: Math.max(existing?.last_seq ?? 0, last_seq) });
  await tx.done;
}

const CARRIER_ORDER: Record<string, number> = {
  none: 0,
  queued: 1,
  unknown: 2,
  dispatched: 3,
  sent: 4,
  delivery_failed: 5,
  failed: 5,
  delivered: 5,
};

const TERMINAL_CARRIER_STATES = new Set(["failed", "delivery_failed", "delivered"]);

export function canAdvanceCarrierStatus(current: string, next: string): boolean {
  if (!(next in CARRIER_ORDER)) return false;
  if (current === next) return true;
  if (TERMINAL_CARRIER_STATES.has(current)) return false;
  return (CARRIER_ORDER[next] ?? -1) >= (CARRIER_ORDER[current] ?? 0);
}

// ----- blocklist --------------------------------------------------------

export async function addBlockKeyword(keyword: string): Promise<BlockRow> {
  const d = await db();
  const normalized = keyword.trim().normalize("NFKC").toLowerCase();
  if (!normalized) throw new Error("keyword is empty");
  const tx = d.transaction("blocklist", "readwrite");
  const existing = await tx.store.index("by-keyword").get(normalized);
  if (existing) {
    return existing;
  }
  const row: BlockRow = {
    id: crypto.randomUUID(),
    keyword: normalized,
    created_at: Date.now(),
  };
  await tx.store.put(row);
  await tx.done;
  return row;
}

export async function removeBlockKeyword(id: string): Promise<void> {
  const d = await db();
  await d.delete("blocklist", id);
}

export async function listBlockKeywords(): Promise<BlockRow[]> {
  const d = await db();
  return await d.getAll("blocklist");
}

/** Insert/overwrite a keyword row with an explicit id (server-synced rows
 * use `srv:<server_id>` so removals can be mapped back to the server). */
export async function putBlockKeywordRow(row: BlockRow): Promise<void> {
  const d = await db();
  await d.put("blocklist", row);
}

export async function clearBlockKeywords(): Promise<void> {
  const d = await db();
  await d.clear("blocklist");
}

// ----- blocked senders (shared, synced via server) -----------------------

export async function addBlockedSender(sender: string): Promise<SenderRow> {
  const d = await db();
  // Canonicalize new rows only; legacy IndexedDB rows remain intact and are
  // handled compatibly by matchesBlockedSender() at read time.
  const normalized = normalizePhone(sender);
  if (!normalized) throw new Error("sender is empty");
  const tx = d.transaction("blockedSenders", "readwrite");
  const existing = await tx.store.index("by-sender").get(normalized);
  if (existing) return existing;
  const row: SenderRow = {
    id: crypto.randomUUID(),
    sender: normalized,
    created_at: Date.now(),
  };
  await tx.store.put(row);
  await tx.done;
  return row;
}

export async function removeBlockedSender(id: string): Promise<void> {
  const d = await db();
  await d.delete("blockedSenders", id);
}

export async function listBlockedSenders(): Promise<SenderRow[]> {
  const d = await db();
  return await d.getAll("blockedSenders");
}

export async function putBlockedSenderRow(row: SenderRow): Promise<void> {
  const d = await db();
  await d.put("blockedSenders", row);
}

export async function clearBlockedSenders(): Promise<void> {
  const d = await db();
  await d.clear("blockedSenders");
}

// ----- message search -----------------------------------------------------

/** Case-insensitive substring search over stored decrypted messages.
 * Returns newest-first, capped at `limit` rows. */
export async function searchMessages(query: string, limit = 50): Promise<MessageRow[]> {
  const q = query.trim().normalize("NFKC").toLowerCase();
  if (!q) return [];
  const all = await listAllMessages();
  const hits = all.filter((m) => {
    if (m.blocked) return false;
    const hay = `${m.plaintext}\n${m.subject ?? ""}`.normalize("NFKC").toLowerCase();
    return hay.includes(q);
  });
  hits.sort((a, b) => b.created_at - a.created_at);
  return hits.slice(0, limit);
}
