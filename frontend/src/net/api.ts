/**
 * REST + Socket.IO client wrappers. All endpoints are relative — in dev Vite
 * proxies to the Flask server; in prod Caddy reverse-proxies.
 */
import { io, type Socket } from "socket.io-client";
import type { Envelope } from "../crypto/keys";

const API = "/api";
const REQUEST_TIMEOUT_MS = 12_000;
/**
 * A message page carries up to 200 envelopes and the relay accepts
 * MAX_ENVELOPE_BYTES (1.5 MiB) each, so an MMS-heavy page is tens of MB. At
 * the shared budget such a page aborts, the sync leaves its cursor where it
 * was, and the next pass asks for the identical page forever.
 */
const MESSAGE_PAGE_TIMEOUT_MS = 60_000;
const SOCKET_ACK_TIMEOUT_MS = 10_000;

interface ApiResult {
  ok: boolean;
  error?: string;
  status?: number;
  /**
   * Stable machine-readable failure reason from the relay. Branch on this,
   * never on `error` (localized prose) and never on `status` alone where one
   * status covers outcomes needing opposite handling — see DEVICE_REVOKED.
   */
  code?: string;
}

/**
 * The relay says this device's own row is revoked, as opposed to the 401 it
 * also returns for an expired or superseded token. Only the first justifies
 * discarding local device state.
 */
export const DEVICE_REVOKED = "device_revoked";

export interface LoginResult extends ApiResult {
  uid?: number;
  username?: string;
  has_approved_devices?: boolean;
  has_pending_devices?: boolean;
}

export interface DeviceRegisterResult extends ApiResult {
  sid?: string;
  token?: string;
  uid?: number;
  trust_state?: DeviceTrustState;
  challenge?: string;
  security_epoch?: number;
  directory_hash?: string;
  identity_sig_pub?: string;
  challenge_id?: string;
  expires_at?: number;
  session_version?: number;
}

export function canonicalDeviceLoginProof(fields: {
  uid: number; sid: string; challenge_id: string; challenge: string; session_version: number;
}): string {
  return "securemsg-device-login-v1\n" +
    `uid=${fields.uid}\nsid=${fields.sid}\nchallenge_id=${fields.challenge_id}\n` +
    `challenge=${fields.challenge}\nsession_version=${fields.session_version}\n`;
}

/**
 * One recipient device in a conversation. Carries keys and identity only —
 * the relay does not hand out other accounts' device names (see the server's
 * /conversation/<cid>/members).
 */
export interface ConvMember {
  user_id: number;
  device_id: number;
  sid: string;
  pub_key: string;
  sig_pub: string;
  kind: "web" | "android_gateway";
}

/**
 * Whether an account directory is signed end to end (verified_v2) or only
 * trusted on first use (legacy_v1). Spelled once: a mode added to the union
 * but missed in one validator is a verification hole nothing would surface.
 */
export type SecurityMode = "legacy_v1" | "verified_v2";

export function isSecurityMode(value: unknown): value is SecurityMode {
  return value === "legacy_v1" || value === "verified_v2";
}

export interface DirectoryCheckpoint {
  user_id: number;
  identity_sig_pub: string;
  security_epoch: number;
  directory_hash: string;
  security_mode?: SecurityMode;
}

export interface DeviceHistoryEntry {
  sid: string;
  kind: "web" | "android_gateway";
  pub_key: string;
  sig_pub: string;
  fingerprint: string;
  trust_state: "approved" | "revoked";
  challenge: string;
  approved_by_sid: string;
  approved_at?: number | null;
  approval_signature?: string | null;
  revoked_at?: number | null;
  verification_state?: "legacy_unverified" | "verified";
}

export interface ApprovalCertificate {
  subject_sid: string;
  approver_sid: string;
  parent_epoch: number;
  resulting_epoch: number;
  statement: string;
  signature: string;
  created_at: number;
}

export interface RevocationCertificate {
  subject_sid: string;
  actor_sid: string;
  parent_epoch: number;
  resulting_epoch: number;
  reason: "user_revoked";
  statement: string;
  signature: string;
  created_at: number;
}

export interface SecurityUpgradeCertificate {
  identity_sid: string;
  parent_epoch: number;
  resulting_epoch: number;
  statement: string;
  signature: string;
  created_at: number;
}

export interface DirectoryProof extends DirectoryCheckpoint {
  trust_enforced_at?: number | null;
  device_history: DeviceHistoryEntry[];
  approval_certificates: ApprovalCertificate[];
  revocation_certificates: RevocationCertificate[];
  security_upgrade_certificates: SecurityUpgradeCertificate[];
}

export interface ConversationMembersResult extends ApiResult {
  conv_id?: number;
  cid?: string;
  recipient_keyset_hash?: string;
  directory_checkpoints?: DirectoryCheckpoint[];
  directory_proofs?: DirectoryProof[];
  members?: ConvMember[];
}

export type DeviceTrustState = "pending" | "approved" | "rejected" | "revoked";

export interface AccountDevice {
  sid: string;
  name: string;
  kind: "web" | "android_gateway";
  pub_key: string;
  sig_pub: string;
  key_fingerprint?: string;
  fingerprint?: string;
  trust_state?: DeviceTrustState;
  created_at: number;
  last_seen: number;
  approved_at?: number | null;
  approved_by_sid?: string | null;
  approval_epoch?: number | null;
  challenge?: string;
  parent_epoch?: number;
}

export interface DeviceDirectoryResult extends ApiResult {
  devices?: AccountDevice[];
  security_epoch?: number;
  directory_hash?: string;
  identity_sig_pub?: string;
  security_mode?: SecurityMode;
}

export interface KeyDirectoryResult extends ApiResult {
  user_id?: number;
  devices?: AccountDevice[];
  security_epoch?: number;
  directory_hash?: string;
  identity_sig_pub?: string;
  trust_enforced_at?: number | null;
  security_mode?: SecurityMode;
  device_history?: DeviceHistoryEntry[];
  approval_certificates?: ApprovalCertificate[];
  revocation_certificates?: RevocationCertificate[];
  security_upgrade_certificates?: SecurityUpgradeCertificate[];
}

/** A /key-directory answer carrying every field a DirectoryProof needs. */
export type CompleteKeyDirectory = KeyDirectoryResult & Required<Pick<
  KeyDirectoryResult,
  "devices" | "security_epoch" | "directory_hash" | "identity_sig_pub"
  | "security_mode" | "device_history" | "approval_certificates"
  | "revocation_certificates" | "security_upgrade_certificates"
>>;

/**
 * One completeness test for /key-directory, and one place that turns the
 * answer into the proof.
 *
 * The two callers — post-login verification and the device panel — spelled the
 * same field list out by hand, and they disagree about what to do when it
 * fails (login treats an incomplete directory as a hostile relay; the panel
 * skips verification to stay usable). That disagreement is a policy question
 * for the call sites, but the LIST is not: a field added to DirectoryProof and
 * checked on only one path is a verification hole nothing would surface.
 */
export function isCompleteKeyDirectory(
  directory: KeyDirectoryResult,
): directory is CompleteKeyDirectory {
  return Boolean(
    directory.identity_sig_pub
    && directory.directory_hash
    && Number.isSafeInteger(directory.security_epoch)
    && directory.devices
    && directory.device_history
    && directory.approval_certificates
    && directory.revocation_certificates
    && directory.security_upgrade_certificates
    && isSecurityMode(directory.security_mode),
  );
}

/** The DirectoryProof a complete /key-directory answer stands for. */
export function ownDirectoryProof(
  uid: number,
  directory: CompleteKeyDirectory,
): DirectoryProof {
  return {
    user_id: uid,
    identity_sig_pub: directory.identity_sig_pub,
    security_epoch: directory.security_epoch,
    directory_hash: directory.directory_hash,
    trust_enforced_at: directory.trust_enforced_at,
    security_mode: directory.security_mode,
    device_history: directory.device_history,
    approval_certificates: directory.approval_certificates,
    revocation_certificates: directory.revocation_certificates,
    security_upgrade_certificates: directory.security_upgrade_certificates,
  };
}

export interface PairingSessionInfo {
  pairing_id: string;
  nonce_approver: string;
  expires_at: number;
}

export interface PairingSessionResult extends ApiResult, Partial<PairingSessionInfo> {}

export interface DeviceApprovalStatusResult extends ApiResult {
  trust_state?: DeviceTrustState;
  sid?: string;
  challenge?: string;
  parent_epoch?: number;
  /** Live QR pairing session for this pending device, if an approver scanned. */
  pairing?: PairingSessionInfo | null;
}

export type BlockRuleType = "keyword" | "sender";

export interface BlockRule {
  id: number;
  type: BlockRuleType;
  value: string;
  created_at: number;
}

export interface BlocklistResult extends ApiResult {
  rules?: BlockRule[];
  rule?: BlockRule;
}

/** One re-wrapped message key offered to a later-registered device. */
export interface ShareKeyEntry {
  seq: number;
  ek: string;
  n: string;
}

export interface MissingKeysResult extends ApiResult {
  cid?: string;
  sid?: string;
  seqs?: number[];
}

export interface ShareKeysResult extends ApiResult {
  added?: number;
  skipped?: number;
}

/** Server-enforced per-request cap on /share-keys entries. */
export const SHARE_KEYS_BATCH_MAX = 200;

/**
 * Server-enforced size of a /missing-keys answer. It is the LOWEST unkeyed
 * sequences and takes no offset, so a full page a sharer cannot open is a
 * wall rather than a page to advance past.
 */
export const MISSING_KEYS_PAGE_SIZE = 500;

export interface ServerMessage {
  id: number;
  seq: number;
  cid: string;
  conv_id: number;
  sender_id: number;
  sender_sid: string;
  sender_pub_key?: string;
  payload: Envelope;
  created_at: number;
  /**
   * The sending client's own idempotency key. Presentation hint only: it is
   * outside the sealed envelope, so it may be read to place a bubble but never
   * to decide anything that matters.
   */
  client_mid?: string | null;
  carrier_status?: string;
  carrier_error?: string | null;
  carrier_updated_at?: number | null;
}

/**
 * One conversation row as the relay serves it. `name` is the routing identity
 * (a phone number for an SMS thread); a contact label never substitutes for it.
 */
export interface Conversation {
  cid: string;
  conv_id: number;
  name: string;
  /** Account-wide contact label supplied by the server for presentation only. */
  synced_contact_name?: string | null;
  members: string[];
  created_at: number;
}

export interface ConversationsResult extends ApiResult {
  conversations?: Conversation[];
}

export interface CreateConversationResult extends ApiResult {
  cid?: string;
}

export interface RenameConversationResult extends ApiResult {
  cid?: string;
  name?: string;
}

export interface MessagesResult extends ApiResult {
  messages?: ServerMessage[];
}

export class Api {
  token: string | null = null;
  /**
   * Invoked when an authenticated request gets HTTP 401 (JWT expired or the
   * device was revoked from another session). The store wires logout() here.
   * Never fires for unauthenticated bootstrap calls (login/register/device-*),
   * because those run without a token set.
   */
  onUnauthorized: (() => void) | null = null;

  setToken(t: string | null) { this.token = t; }

  private async request<T extends ApiResult>(
    path: string,
    init: RequestInit = {},
    notifyUnauthorized = true,
    timeoutMs = REQUEST_TIMEOUT_MS,
  ): Promise<T> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    // Bind the request and any 401 side effect to the same session. A delayed
    // response from an old JWT must not log out a newer session.
    const requestToken = this.token;
    try {
      const response = await fetch(`${API}${path}`, {
        ...init,
        signal: controller.signal,
        headers: {
          ...(init.body ? { "Content-Type": "application/json" } : {}),
          ...(requestToken ? { Authorization: `Bearer ${requestToken}` } : {}),
          ...init.headers,
        },
      });
      if (notifyUnauthorized && response.status === 401
        && requestToken && this.token === requestToken && this.onUnauthorized) {
        this.onUnauthorized();
      }
      const text = await response.text();
      let data: ApiResult;
      try {
        data = text ? JSON.parse(text) : { ok: response.ok };
      } catch {
        return {
          ok: false,
          status: response.status,
          error: `서버 응답 형식 오류 (HTTP ${response.status})`,
        } as T;
      }
      if (!response.ok || !data.ok) {
        return {
          ...data,
          ok: false,
          status: response.status,
          error: data.error || `요청 실패 (HTTP ${response.status})`,
        } as T;
      }
      return data as T;
    } catch (error) {
      const message = error instanceof DOMException && error.name === "AbortError"
        ? "서버 응답 시간 초과"
        : "서버에 연결할 수 없습니다";
      return { ok: false, status: 0, error: message } as T;
    } finally {
      clearTimeout(timer);
    }
  }

  private post<T extends ApiResult>(path: string, body: unknown): Promise<T> {
    return this.request<T>(path, { method: "POST", body: JSON.stringify(body) });
  }

  private get<T extends ApiResult>(path: string, timeoutMs?: number): Promise<T> {
    return this.request<T>(path, {}, true, timeoutMs);
  }

  login(username: string, pwHash: string): Promise<LoginResult> {
    return this.post("/login", { username, pw_hash: pwHash });
  }
  deviceRegister(username: string, pwHash: string, deviceName: string, pubKey: string, sigPub: string): Promise<DeviceRegisterResult> {
    return this.post("/device-register", { username, pw_hash: pwHash, device_name: deviceName, pub_key: pubKey, sig_pub: sigPub });
  }
  deviceLogin(username: string, pwHash: string, sid: string): Promise<DeviceRegisterResult> {
    return this.post("/device-login", { username, pw_hash: pwHash, sid });
  }
  deviceLoginProof(username: string, pwHash: string, sid: string, challengeId: string, challenge: string, proof: string): Promise<DeviceRegisterResult> {
    return this.post("/device-login", {
      username, pw_hash: pwHash, sid, challenge_id: challengeId, challenge, proof,
    });
  }
  registerEmailRequest(username: string, email: string, pwHash: string): Promise<ApiResult & { challenge_id?: string; expires_at?: number }> {
    return this.post("/register/email/request", { username, email, pw_hash: pwHash });
  }
  registerEmailVerify(challengeId: string, code: string): Promise<ApiResult & { uid?: number; username?: string; email?: string }> {
    return this.post("/register/email/verify", { challenge_id: challengeId, code });
  }
  passwordResetRequest(username: string, email: string): Promise<ApiResult & { challenge_id?: string; expires_at?: number }> {
    return this.post("/password-reset/request", { username, email });
  }
  passwordResetConfirm(username: string, email: string, challengeId: string, code: string, pwHash: string): Promise<ApiResult> {
    return this.post("/password-reset/confirm", {
      username, email, challenge_id: challengeId, code, pw_hash: pwHash,
    });
  }
  /** Sliding session renewal; the store applies the returned token. */
  tokenRefresh(): Promise<ApiResult & { token?: string }> {
    return this.post("/token-refresh", {});
  }
  /** Invalidate the current bearer token without recursively firing onUnauthorized. */
  logout(): Promise<ApiResult> {
    return this.request("/logout", { method: "POST", body: JSON.stringify({}) }, false);
  }
  listDevices(): Promise<DeviceDirectoryResult> { return this.get("/devices"); }
  keyDirectory(): Promise<KeyDirectoryResult> { return this.get("/key-directory"); }
  deviceApprovalStatus(): Promise<DeviceApprovalStatusResult> { return this.get("/device-pending-status"); }
  pendingDeviceRevoke(): Promise<ApiResult> { return this.post("/device-pending-revoke", {}); }
  /**
   * Approve a pending device. Passing `pairing` signs and commits the v2
   * (QR) form, which binds the approval to one scanned session; omitting it
   * keeps the v1 fingerprint-compare form.
   */
  deviceApprove(
    sid: string,
    parentEpoch: number,
    signature: string,
    pairing?: { pairing_id: string; nonce_new: string; nonce_approver: string },
  ): Promise<ApiResult> {
    // The pending device's challenge is NOT sent: it is covered by the
    // signature and the server reads it from the pending row. Taking it as an
    // argument read as if this call transmitted and bound it.
    return this.post("/device-approve", {
      subject_sid: sid,
      parent_epoch: parentEpoch,
      signature,
      ...(pairing ?? {}),
    });
  }
  /** Approver side of QR pairing: bind a scanned nonce to one pending device. */
  pairingSession(sid: string, challenge: string, nonceNew: string): Promise<PairingSessionResult> {
    return this.post("/pairing/session", { sid, challenge, nonce_new: nonceNew });
  }
  deviceRevoke(sid: string, parentEpoch: number, signature: string): Promise<ApiResult> {
    return this.post("/device-revoke", {
      sid, parent_epoch: parentEpoch, signature, reason: "user_revoked",
    });
  }
  deviceRejectPending(sid: string, challenge: string, parentEpoch: number): Promise<ApiResult> {
    return this.post("/device-reject-pending", { sid, challenge, parent_epoch: parentEpoch });
  }
  securityUpgrade(parentEpoch: number, signature: string): Promise<ApiResult> {
    return this.post("/security-upgrade", { parent_epoch: parentEpoch, signature });
  }
  listBlockRules(): Promise<BlocklistResult> { return this.get("/blocklist"); }
  addBlockRule(type: BlockRuleType, value: string): Promise<BlocklistResult> {
    return this.post("/blocklist", { type, value });
  }
  removeBlockRule(id: number): Promise<BlocklistResult> {
    return this.post("/blocklist/remove", { id });
  }
  renameConversation(cid: string, name: string): Promise<RenameConversationResult> {
    return this.post("/conversation/rename", { cid, name });
  }
  createConversation(members: string[], name?: string): Promise<CreateConversationResult> {
    return this.post("/conversation", { members, ...(name ? { name } : {}) });
  }
  listConversations(): Promise<ConversationsResult> { return this.get("/conversations"); }
  convMembers(cid: string): Promise<ConversationMembersResult> {
    return this.get(`/conversation/${encodeURIComponent(cid)}/members`);
  }
  fetchMessages(cid: string, since: number, limit = 200): Promise<MessagesResult> {
    return this.get(
      `/conversation/${encodeURIComponent(cid)}/messages?since=${since}&limit=${limit}`,
      MESSAGE_PAGE_TIMEOUT_MS,
    );
  }
  /** Sequences in `cid` that `sid` holds no envelope key for. */
  missingKeys(cid: string, sid: string): Promise<MissingKeysResult> {
    return this.get(
      `/conversation/${encodeURIComponent(cid)}/missing-keys?sid=${encodeURIComponent(sid)}`,
    );
  }
  /**
   * Grant `sid` re-wrapped keys for past messages. The server caps one request
   * at SHARE_KEYS_BATCH_MAX entries and rejects a larger body outright, so the
   * split happens here rather than in every caller. Counts are summed across
   * the requests; the first failure stops the run and is returned as-is,
   * because the entries already accepted are committed and re-offering them is
   * harmless (the server never overwrites an existing key).
   */
  async shareKeys(cid: string, sid: string, entries: ShareKeyEntry[]): Promise<ShareKeysResult> {
    let added = 0;
    let skipped = 0;
    for (let start = 0; start < entries.length; start += SHARE_KEYS_BATCH_MAX) {
      const batch = entries.slice(start, start + SHARE_KEYS_BATCH_MAX);
      const result = await this.post<ShareKeysResult>(
        `/conversation/${encodeURIComponent(cid)}/share-keys`,
        { sid, entries: batch },
      );
      if (!result.ok) return { ...result, added, skipped };
      added += result.added ?? 0;
      skipped += result.skipped ?? 0;
    }
    return { ok: true, added, skipped };
  }
}

export const api = new Api();

// ----- socket -----------------------------------------------------------

let _socket: Socket | null = null;
let _socketBase: string | undefined;

/** Test/deploy override. Browsers derive the origin automatically. */
export function setSocketBase(base: string | undefined): void {
  _socketBase = base;
}

/**
 * The session's one socket, opened on first call.
 *
 * A later call carrying a different token is a sliding renewal, not a new
 * identity: the server already accepted this handshake, so the live connection
 * and every handler wired onto it survive, and only a future reconnect
 * handshake picks up the fresh credential. Ending a session is
 * `disconnectSocket()`'s job — the store calls it on every login attempt,
 * logout, forget-device and trust lock, so another account's token can never
 * reach a socket still open under this one.
 */
export function getSocket(token: string): Socket {
  if (_socket) {
    _socket.auth = { token };
    return _socket;
  }
  const options = {
    auth: { token },
    // Start with long-polling and upgrade when the complete proxy chain permits
    // WebSocket. Oracle's outer edge may reject an upgrade while polling works.
    reconnection: true,
    reconnectionAttempts: Infinity,
    reconnectionDelay: 500,
    reconnectionDelayMax: 5000,
  };
  _socket = _socketBase ? io(_socketBase, options) : io(options);
  return _socket;
}

export function disconnectSocket(): void {
  if (_socket) {
    _socket.removeAllListeners();
    _socket.disconnect();
    _socket = null;
  }
}

export function waitForSocketConnected(socket: Socket, timeoutMs = 5_000): Promise<boolean> {
  if (socket.connected) return Promise.resolve(true);
  return new Promise((resolve) => {
    let finished = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const done = (connected: boolean) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      socket.off("connect", onConnect);
      socket.off("connect_error", onError);
      resolve(connected);
    };
    const onConnect = () => done(true);
    const onError = () => done(false);
    socket.once("connect", onConnect);
    socket.once("connect_error", onError);
    timer = setTimeout(() => done(false), timeoutMs);
    // Close the narrow race where the socket connected between the first
    // check above and listener registration.
    if (socket.connected) done(true);
  });
}

export interface MessageAck {
  ok: boolean;
  seq?: number;
  id?: number;
  error?: string;
  /** Client-side marker for a lost acknowledgement (never server-set). */
  timedOut?: boolean;
}

export async function sendMessage(
  socket: Socket,
  cid: string,
  envelope: Envelope,
  shouldContinue: () => boolean = () => true,
): Promise<MessageAck> {
  const messageId = crypto.randomUUID();
  let result: MessageAck = {
    ok: false,
    error: "메시지 전송 실패",
  };
  // Retry one lost acknowledgement with the same id. The server returns the
  // original sequence without fanning out a second carrier-bound message.
  for (let attempt = 0; attempt < 2; attempt += 1) {
    if (!shouldContinue()) return { ok: false, error: "메시지 전송이 취소되었습니다" };
    result = await emitMessageOnce(socket, cid, messageId, envelope);
    if (result.ok || !result.timedOut) break;
  }
  return result;
}

function emitMessageOnce(
  socket: Socket,
  cid: string,
  messageId: string,
  envelope: Envelope,
): Promise<MessageAck> {
  return new Promise((resolve) => {
    // socket.io's own timeout rather than a private one: an emit made while the
    // connection is down is parked in sendBuffer and flushed on reconnect, and
    // only this form splices it back out when the timer expires. A private
    // timer reported the send as failed while the packet was still queued, so
    // the relay delivered it minutes later and the user's re-send became a
    // second real SMS to the contact.
    socket.timeout(SOCKET_ACK_TIMEOUT_MS).emit(
      "message_send",
      { cid, mid: messageId, payload: envelope },
      (error: Error | null, ack: unknown) => {
        if (error) {
          resolve({ ok: false, error: "메시지 전송 확인 시간 초과", timedOut: true });
          return;
        }
        resolve(isMessageAck(ack) ? ack : { ok: false, error: "서버 확인 응답 없음" });
      },
    );
  });
}

function isMessageAck(value: unknown): value is MessageAck {
  return typeof value === "object" && value !== null
    && typeof (value as MessageAck).ok === "boolean";
}
