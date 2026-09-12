/**
 * IndexedDB persistence layer.
 *
 * Stores client-side in IndexedDB. The browser profile/OS storage protection is
 * the at-rest boundary; message plaintext and private keys are not additionally
 * encrypted by this application:
 *   - meta: { username, uid, sid, deviceName, keypair, pubKey }  (current device)
 *   - messages: { [cid+seq] -> { id, seq, cid, sender_sid, plaintext, created_at } }
 *   - cursors: { cid -> last_seq }
 *   - blocklist: { id, keyword, created_at }  (substring filter, applied after decrypt)
 *   - blockedSenders: { id, sender, created_at }  (same filter, by carrier sender)
 *   - accountTrust / trustedDevices: the pinned key directory (see pinTrustedDirectories)
 */
import { openDB, unwrap, type DBSchema, type IDBPDatabase } from "idb";
import type { DeviceKeypair } from "../crypto/keys";
import { serverDirectoryHash } from "../crypto/deviceTrust";
import { isSecurityMode, type SecurityMode } from "../net/api";
import { normalizePhone } from "./conversationPolicy";
// Type-only in the other direction, so this stays a one-way dependency.
import { attachmentPreviewLabel, messageDirection } from "./helpers";

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

export interface AccountTrustRow {
  uid: number;
  identity_sig_pub: string;
  security_epoch: number;
  directory_hash: string;
  security_mode: SecurityMode;
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
  /**
   * Set once a verified directory stopped listing this device, i.e. it was
   * revoked. The row survives revocation because history the device wrapped
   * while it was still trusted has to stay openable, so presence in this store
   * answers "did this browser ever verify it", never "is it still authorized".
   * Absent on rows written before this field existed; treated as active.
   */
  revoked_at?: number | null;
}

export interface TrustedDirectorySnapshot {
  uid: number;
  identity_sig_pub: string;
  security_epoch: number;
  directory_hash: string;
  security_mode: SecurityMode;
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
  /**
   * Which way this message travelled, when that is knowable.
   *
   * `sender_sid` cannot answer it for an SMS thread: the Android gateway relays
   * both the texts it receives from the carrier and the ones the owner types on
   * the phone, and both arrive under the gateway's own sid. Left undefined for
   * rows stored before this existed and for anything genuinely unclassifiable —
   * never guess, an unknown row keeps the old neutral rendering.
   */
  direction?: "in" | "out";
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
  /**
   * Lowest sequence this device pulled but could not decrypt. The cursor
   * deliberately advances past such messages so one bad row cannot starve
   * newer history, which would otherwise make them unreachable forever — and
   * history shared by another device (see historyShare.ts) arrives with no
   * notification at all. Keeping the floor lets a later sync re-read them.
   */
  retry_from?: number | null;
  /**
   * Highest sequence the user has actually looked at in this conversation.
   * Distinct from `last_seq`, which only says the row reached this device:
   * a message can be delivered, stored and never opened, and that is exactly
   * the state the sidebar has to advertise. Absent on rows written before
   * unread counts existed, where `last_seq` is the honest fallback — treating
   * those as unread would mark every old thread new on the first upgrade.
   */
  read_seq?: number | null;
  /**
   * Set once the one-time direction backfill has read this conversation.
   *
   * Without it the pass repeats forever on any thread holding a row it can
   * never classify — a peer's message, or one whose relay id has no
   * recognisable shape. "Is anything unclassified?" stays true, so every login
   * re-downloads the whole history to write nothing.
   */
  dir_backfilled?: boolean;
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
  if (_db) return _db;
  let abandoned = false;
  const opening = new Promise<IDBPDatabase<SecureMsgDB>>((resolve, reject) => {
    openDB<SecureMsgDB>("secure-msg", 5, {
      upgrade(d, oldVersion, _newVersion, transaction) {
        if (oldVersion < 1) {
          d.createObjectStore("meta");
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
        if (oldVersion >= 3 && oldVersion < 4) {
          // Rows written by v3 predate mode pinning. Treat them as legacy so
          // an authenticated verified_v2 proof can upgrade them, while never
          // guessing that an unverifiable old row was already verified.
          const accountTrust = transaction.objectStore("accountTrust");
          void accountTrust.openCursor().then(function migrate(cursor): Promise<void> | void {
            if (!cursor) return;
            const row = cursor.value as AccountTrustRow;
            if (!isSecurityMode(row.security_mode)) {
              cursor.update({ ...row, security_mode: "legacy_v1" });
            }
            return cursor.continue().then(migrate);
          });
        }
        if (oldVersion >= 1 && oldVersion < 5) {
          // The `devices` cache lost its last reader when sender keys moved to
          // the pinned trust directory, so v5 drops it. Unwrapped because the
          // store is gone from SecureMsgDB, and guarded by contains() because a
          // failed deleteObjectStore would leave the profile stuck below v5
          // with no way to open the database again.
          const legacy = unwrap(d);
          if (legacy.objectStoreNames.contains("devices")) legacy.deleteObjectStore("devices");
        }
      },
      /**
       * Another tab is still on the previous build and holds its connection, so
       * this upgrade can never start. Left alone the open promise never settles
       * and init() sits on the loading screen with no error at all, so give up
       * and let the next call try again once that tab is gone.
       */
      blocked() {
        abandoned = true;
        reject(new Error("다른 탭에서 앱이 열려 있어 저장소를 열 수 없습니다. 다른 탭을 닫고 새로고침하세요."));
      },
      /** The mirror image: this connection is what a newer build in another
       * tab is waiting on. Yield it rather than strand that tab. */
      blocking() {
        const open = _db;
        _db = null;
        void open?.then((connection) => connection.close(), () => {});
      },
      /** The browser dropped the connection (storage eviction, crash recovery);
       * the memoised promise no longer refers to a live one. */
      terminated() {
        _db = null;
      },
    }).then((connection) => {
      if (abandoned) {
        connection.close();
        return;
      }
      resolve(connection);
    }, reject);
  });
  // A transient open failure (quota, UnknownError) must not be memoised, or
  // every later call rejects until the page is reloaded.
  _db = opening;
  void opening.catch(() => {
    if (_db === opening) _db = null;
  });
  return opening;
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

/** Clear account content while retaining this browser's device keypair. */
export async function clearSessionData(): Promise<void> {
  const d = await db();
  const tx = d.transaction(["messages", "cursors"], "readwrite");
  await Promise.all([
    tx.objectStore("messages").clear(),
    tx.objectStore("cursors").clear(),
  ]);
  await tx.done;
}

/**
 * Discard a revoked/missing local device so login can register a fresh one.
 *
 * Account trust anchors are deliberately excluded: a server-side revocation
 * must not be able to erase the pinned account identity, security epoch, or
 * historical device keys that protect the subsequent registration.
 */
export async function clearDeviceForReregistration(): Promise<void> {
  const d = await db();
  const tx = d.transaction(
    ["meta", "messages", "cursors", "blocklist", "blockedSenders"],
    "readwrite",
  );
  await Promise.all([
    tx.objectStore("meta").clear(),
    tx.objectStore("messages").clear(),
    tx.objectStore("cursors").clear(),
    tx.objectStore("blocklist").clear(),
    tx.objectStore("blockedSenders").clear(),
  ]);
  await tx.done;
}

/** Explicitly forget this account and every locally pinned trust anchor. */
export async function clearAllData(): Promise<void> {
  const d = await db();
  const tx = d.transaction(
    ["meta", "messages", "cursors", "blocklist", "blockedSenders", "accountTrust", "trustedDevices"],
    "readwrite",
  );
  await Promise.all([
    tx.objectStore("meta").clear(),
    tx.objectStore("messages").clear(),
    tx.objectStore("cursors").clear(),
    tx.objectStore("blocklist").clear(),
    tx.objectStore("blockedSenders").clear(),
    tx.objectStore("accountTrust").clear(),
    tx.objectStore("trustedDevices").clear(),
  ]);
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
  await pinTrustedDirectories([snapshot]);
}

/**
 * Atomically pins a set of authenticated directory snapshots. Every account
 * and device invariant is preflighted before any row is written, and all rows
 * are committed by one IndexedDB transaction.
 */
export async function pinTrustedDirectories(
  snapshots: TrustedDirectorySnapshot[],
  shouldContinue: () => boolean = () => true,
): Promise<void> {
  const snapshotUids = new Set<number>();
  for (const snapshot of snapshots) {
    if (!Number.isSafeInteger(snapshot.uid) || snapshot.uid < 0) throw new Error("invalid uid");
    if (snapshotUids.has(snapshot.uid)) throw new Error("duplicate directory uid");
    snapshotUids.add(snapshot.uid);
    if (!Number.isSafeInteger(snapshot.security_epoch) || snapshot.security_epoch < 0) throw new Error("invalid security epoch");
    if (!snapshot.identity_sig_pub || !snapshot.directory_hash) throw new Error("incomplete directory snapshot");
    if (!isSecurityMode(snapshot.security_mode)) throw new Error("invalid security mode");

    const seen = new Set<string>();
    for (const candidate of snapshot.devices) {
      if (!candidate.sid || seen.has(candidate.sid)) {
        throw new Error("duplicate or empty device sid");
      }
      seen.add(candidate.sid);
    }

  }

  const d = await db();
  const tx = d.transaction(["accountTrust", "trustedDevices"], "readwrite");
  const accountStore = tx.objectStore("accountTrust");
  const deviceStore = tx.objectStore("trustedDevices");
  const pinnedDevices = new Map<string, TrustedDeviceRow | undefined>();
  const abortIfInvalidated = async (): Promise<boolean> => {
    if (shouldContinue()) return false;
    tx.abort();
    try {
      await tx.done;
    } catch (error) {
      if (!(error instanceof DOMException) || error.name !== "AbortError") throw error;
    }
    return true;
  };

  // Preflight the complete batch. No write is issued before this finishes.
  for (const snapshot of snapshots) {
    if (await abortIfInvalidated()) return;
    const existingAccount = await accountStore.get(snapshot.uid);
    if (existingAccount) {
      if (existingAccount.identity_sig_pub !== snapshot.identity_sig_pub) {
        throw new TrustViolationError("identity_changed", "계정 신원 키가 변경되었습니다.");
      }
      if (snapshot.security_epoch < existingAccount.security_epoch) {
        throw new TrustViolationError("rollback", "키 디렉터리 롤백이 감지되었습니다.");
      }
      // Missing values can only exist in a pre-v4 row and are deliberately
      // interpreted as legacy for backward-compatible verified upgrades.
      const pinnedMode = existingAccount.security_mode ?? "legacy_v1";
      if (pinnedMode === "verified_v2" && snapshot.security_mode !== "verified_v2") {
        throw new TrustViolationError("rollback", "검증된 키 디렉터리의 레거시 모드 역행이 감지되었습니다.");
      }
      if (snapshot.security_epoch === existingAccount.security_epoch
        && snapshot.directory_hash !== existingAccount.directory_hash) {
        throw new TrustViolationError("equivocation", "같은 보안 버전에서 서로 다른 키 목록이 감지되었습니다.");
      }
    }

    for (const candidate of snapshot.devices) {
      if (await abortIfInvalidated()) return;
      const id = trustedDeviceId(snapshot.uid, candidate.sid);
      const pinned = await deviceStore.get(id);
      pinnedDevices.set(id, pinned);
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
  }
  if (await abortIfInvalidated()) return;

  const now = Date.now();
  for (const snapshot of snapshots) {
    if (await abortIfInvalidated()) return;
    // A snapshot always carries the account's COMPLETE set of active devices —
    // directory proof verification rejects any other list — so a pinned row it
    // omits has been revoked. Recording that is the only way this store can
    // distinguish "verified once" from "still authorized"; every grant of new
    // decryption authority depends on the latter.
    const active = new Set(snapshot.devices.map((device) => device.sid));
    const pinnedForAccount = await deviceStore.index("by-account").getAll(snapshot.uid);
    for (const row of pinnedForAccount) {
      if (active.has(row.sid) || row.revoked_at != null) continue;
      if (await abortIfInvalidated()) return;
      await deviceStore.put({ ...row, revoked_at: now, updated_at: now });
    }
    for (const candidate of snapshot.devices) {
      if (await abortIfInvalidated()) return;
      const id = trustedDeviceId(snapshot.uid, candidate.sid);
      await deviceStore.put({
        id,
        uid: snapshot.uid,
        ...candidate,
        first_seen_at: pinnedDevices.get(id)?.first_seen_at ?? now,
        // Tracks the newest verified directory rather than latching: a
        // verified_v2 chain can never re-approve a revoked SID, and latching
        // would let one legacy directory permanently poison a live device.
        revoked_at: null,
        updated_at: now,
      });
    }
    if (await abortIfInvalidated()) return;
    await accountStore.put({
      uid: snapshot.uid,
      identity_sig_pub: snapshot.identity_sig_pub,
      security_epoch: snapshot.security_epoch,
      directory_hash: snapshot.directory_hash,
      security_mode: snapshot.security_mode,
      updated_at: now,
    });
  }
  if (await abortIfInvalidated()) return;
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
  const keepNewerCarrierState = existing && !carrierStateSupersedes(
    existing, m.carrier_status ?? "none", m.carrier_updated_at ?? null,
  );
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
  if (!carrierStateSupersedes(existing, status, updatedAt)) {
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

/**
 * True while any stored row of `cid` has no direction on it.
 *
 * Rows written before direction existed cannot be re-derived locally — the
 * signal lives on the server row, not in the sealed body — so the one-time
 * backfill has to re-read them. Asking this first keeps that pass free on
 * every later run: it stops at the first classified row and costs no network.
 */
export async function hasUnclassifiedMessages(cid: string): Promise<boolean> {
  const d = await db();
  let cursor = await d
    .transaction("messages")
    .store.index("by-cid")
    .openCursor(IDBKeyRange.only(cid));
  while (cursor) {
    if (cursor.value.direction == null) return true;
    cursor = await cursor.continue();
  }
  return false;
}

/**
 * Stamp direction onto rows that are already stored, touching nothing else.
 *
 * Deliberately not a putMessage: the backfill knows only what the relay row
 * says, and re-writing the body from it would undo a locally applied blocklist
 * decision or a fresher carrier state.
 */
export async function patchMessageDirections(
  cid: string,
  entries: { seq: number; direction: "in" | "out" }[],
): Promise<number> {
  if (!entries.length) return 0;
  const d = await db();
  const tx = d.transaction("messages", "readwrite");
  let patched = 0;
  for (const entry of entries) {
    const existing = await tx.store.get(msgKey(cid, entry.seq));
    if (!existing || existing.direction != null) continue;
    await tx.store.put({ ...existing, direction: entry.direction });
    patched += 1;
  }
  await tx.done;
  return patched;
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
  await tx.store.put({
    cid,
    last_seq: Math.max(existing?.last_seq ?? 0, last_seq),
    retry_from: existing?.retry_from ?? null,
    // Seed an upgraded row from the cursor as it stands BEFORE this advance.
    // Reading it lazily instead would peg "read" to a value that climbs with
    // every delivery, so no message could ever be unread; seeding it from the
    // post-advance value would swallow the very rows this call is acking.
    read_seq: existing?.read_seq ?? existing?.last_seq ?? 0,
    dir_backfilled: existing?.dir_backfilled ?? false,
  });
  await tx.done;
}

/**
 * Highest sequence of `cid` actually on disk here, 0 when the thread is empty.
 *
 * The delivery cursor and the rows are written by two separate transactions
 * (putMessage, then setCursor), so anything that clears the store between them
 * — a second tab logging out mid-sync — leaves a cursor that outran its own
 * messages. Every later pull asks the relay for `seq > cursor`, so those rows
 * are never offered again and the thread is short a message for good. Reading
 * the real high-water mark is what lets the sync notice and re-read.
 */
export async function highestStoredSeq(cid: string): Promise<number> {
  const d = await db();
  const cursor = await d
    .transaction("messages")
    .store.index("by-cid-seq")
    .openCursor(
      IDBKeyRange.bound([cid, Number.NEGATIVE_INFINITY], [cid, Number.POSITIVE_INFINITY]),
      "prev",
    );
  return cursor?.value.seq ?? 0;
}

/** True once the direction backfill has already read `cid` from the relay. */
export async function isDirectionBackfilled(cid: string): Promise<boolean> {
  const row = await (await db()).get("cursors", cid);
  return row?.dir_backfilled === true;
}

/** Record that `cid` has been through the backfill, however little it stamped. */
export async function markDirectionBackfilled(cid: string): Promise<void> {
  const d = await db();
  const tx = d.transaction("cursors", "readwrite");
  const existing = await tx.store.get(cid);
  await tx.store.put({
    cid,
    last_seq: existing?.last_seq ?? 0,
    retry_from: existing?.retry_from ?? null,
    read_seq: existing?.read_seq ?? null,
    dir_backfilled: true,
  });
  await tx.done;
}

/** Remember that everything up to `seq` in `cid` has been seen by the user. */
export async function markConversationRead(cid: string, seq: number): Promise<void> {
  const d = await db();
  const tx = d.transaction("cursors", "readwrite");
  const existing = await tx.store.get(cid);
  await tx.store.put({
    cid,
    last_seq: existing?.last_seq ?? 0,
    retry_from: existing?.retry_from ?? null,
    read_seq: Math.max(existing?.read_seq ?? 0, seq),
    dir_backfilled: existing?.dir_backfilled ?? false,
  });
  await tx.done;
}

export interface ConversationSummary {
  lastSeq: number;
  /** Milliseconds, matching MessageRow.created_at. */
  lastAt: number;
  preview: string;
  unread: number;
}

/**
 * Per-conversation arrival state for the sidebar, read straight from the rows.
 *
 * Kept out of the server's conversation list on purpose: the relay stores only
 * ciphertext, so the newest line of a thread exists nowhere but here. Blocked
 * rows are skipped so a filtered message never surfaces as a preview, and the
 * user's own messages never count as unread.
 */
export async function conversationSummaries(
  mySid: string,
  myUid?: number | null,
): Promise<Record<string, ConversationSummary>> {
  const d = await db();
  const [rows, cursors] = await Promise.all([d.getAll("messages"), d.getAll("cursors")]);
  const readSeq = new Map<string, number>();
  for (const row of cursors) readSeq.set(row.cid, row.read_seq ?? row.last_seq ?? 0);
  const out: Record<string, ConversationSummary> = {};
  for (const row of rows) {
    if (row.blocked) continue;
    const summary =
      out[row.cid] ?? (out[row.cid] = { lastSeq: 0, lastAt: 0, preview: "", unread: 0 });
    const outgoing = messageDirection(row, mySid, myUid) === "out";
    if (row.seq > summary.lastSeq) {
      summary.lastSeq = row.seq;
      summary.lastAt = row.created_at;
      // Marked in the list too: the preview line is often the only thing the
      // owner reads, and "who said this" is half of what it has to convey.
      summary.preview = outgoing ? `나: ${previewOf(row)}` : previewOf(row);
    }
    // Only what someone else sent can be unread. A text the owner typed on
    // their own phone comes back through the gateway under its sid, and the
    // sender check alone would badge the owner's own messages.
    if (!outgoing && row.seq > (readSeq.get(row.cid) ?? 0)) summary.unread += 1;
  }
  return out;
}

function previewOf(row: MessageRow): string {
  const text = row.plaintext.trim();
  if (text) return row.subject ? `${row.subject} — ${text}` : text;
  if (row.subject) return row.subject;
  return attachmentPreviewLabel(row.attachments);
}

/** Lowest sequence in `cid` this device pulled but could not decrypt. */
export async function getUndecryptableFloor(cid: string): Promise<number | null> {
  const row = await (await db()).get("cursors", cid);
  return row?.retry_from ?? null;
}

/** Record (or clear, with null) the re-read floor for `cid`. */
export async function setUndecryptableFloor(cid: string, seq: number | null): Promise<void> {
  const d = await db();
  const tx = d.transaction("cursors", "readwrite");
  const existing = await tx.store.get(cid);
  await tx.store.put({
    cid,
    last_seq: existing?.last_seq ?? 0,
    retry_from: seq,
    read_seq: existing?.read_seq ?? null,
    dir_backfilled: existing?.dir_backfilled ?? false,
  });
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

/**
 * Whether an incoming carrier state replaces the stored one. putMessage and
 * setCarrierStatus race each other by design, which is why they share a
 * transaction; two hand-written copies of this rule could drift apart and
 * reintroduce exactly the overwrite that transaction was added to prevent.
 *
 * Lifecycle order is authoritative across sources. Producers do not share a
 * precise clock — client optimistic rows use the browser clock while relay
 * timestamps have second precision — so a timestamp only breaks ties within
 * one state, never blocks forward progress.
 */
function carrierStateSupersedes(
  existing: Pick<MessageRow, "carrier_status" | "carrier_updated_at">,
  status: string,
  updatedAt: number | null,
): boolean {
  const current = existing.carrier_status ?? "none";
  if (!canAdvanceCarrierStatus(current, status)) return false;
  return !(current === status && (existing.carrier_updated_at ?? 0) > (updatedAt ?? 0));
}

// ----- blocklist --------------------------------------------------------

/**
 * The rule values the relay itself accepts, mirrored from `_validate` and
 * `SENDER_RE` in server/blocklist.py. A value outside them is refused by the
 * relay on every single upload, so the rule would end up filtering this
 * browser alone while the list presents it as an account rule — and the
 * Android gateway, which receives carrier SMS before any of this, would never
 * apply it.
 *
 * The sender classes are written out rather than `\s`: SENDER_RE is compiled
 * with re.ASCII, and the relay rejects Cc/Cs characters before that pattern
 * runs, so a plain space is the only separator both sides accept. The 3-32
 * length bound falls out of the pattern itself.
 */
const RELAY_SENDER_RE = /^[+*#0-9][+*#0-9\- ]{1,30}[0-9]$/;
const RELAY_KEYWORD_MAX = 120;
const RELAY_REJECTED_CHARS = /[\p{Cc}\p{Cs}]/u;

function normalizeBlockKeyword(keyword: string): string {
  return keyword.trim().normalize("NFKC").toLowerCase();
}

/** Why the relay would refuse this keyword as an account rule, or null. */
export function blockKeywordRejection(keyword: string): string | null {
  const normalized = normalizeBlockKeyword(keyword);
  if (!normalized) return "차단 키워드를 입력하세요";
  // NFKC can expand a string, so the editor's 120-character input cap is not
  // the bound the relay ends up applying.
  if (normalized.length > RELAY_KEYWORD_MAX) return `차단 키워드는 ${RELAY_KEYWORD_MAX}자 이하여야 합니다`;
  if (RELAY_REJECTED_CHARS.test(normalized)) return "차단 키워드에 사용할 수 없는 문자가 있습니다";
  return null;
}

/** Why the relay would refuse this sender as an account rule, or null. */
export function blockedSenderRejection(sender: string): string | null {
  const normalized = normalizePhone(sender);
  if (!normalized) return "차단할 발신번호를 입력하세요";
  if (!RELAY_SENDER_RE.test(normalized)) {
    return "차단 발신번호는 숫자로 끝나는 3~32자여야 합니다 (+, *, #, -, 공백만 사용 가능)";
  }
  return null;
}

export async function addBlockKeyword(keyword: string): Promise<BlockRow> {
  const d = await db();
  const normalized = normalizeBlockKeyword(keyword);
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

/** Atomically replace both account block-rule stores after reconciliation. */
export async function replaceBlockRules(
  keywords: BlockRow[],
  senders: SenderRow[],
): Promise<void> {
  const d = await db();
  const tx = d.transaction(["blocklist", "blockedSenders"], "readwrite");
  const keywordStore = tx.objectStore("blocklist");
  const senderStore = tx.objectStore("blockedSenders");
  await Promise.all([
    keywordStore.clear(),
    senderStore.clear(),
    ...keywords.map((row) => keywordStore.put(row)),
    ...senders.map((row) => senderStore.put(row)),
  ]);
  await tx.done;
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
