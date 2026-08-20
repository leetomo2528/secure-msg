/**
 * REST + Socket.IO client wrappers. All endpoints are relative — in dev Vite
 * proxies to the Flask server; in prod Caddy reverse-proxies.
 */
import { io, type Socket } from "socket.io-client";
import type { Envelope, RecipientDevice } from "../crypto/keys";

const API = "/api";
const REQUEST_TIMEOUT_MS = 12_000;
const SOCKET_ACK_TIMEOUT_MS = 10_000;

interface ApiResult {
  ok: boolean;
  error?: string;
  status?: number;
}

export interface LoginResult {
  ok: boolean;
  error?: string;
  status?: number;
  uid?: number;
  username?: string;
  has_devices?: boolean;
}

export interface DeviceRegisterResult {
  ok: boolean;
  error?: string;
  status?: number;
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

export interface DirectoryCheckpoint {
  user_id: number;
  identity_sig_pub: string;
  security_epoch: number;
  directory_hash: string;
  security_mode?: "legacy_v1" | "verified_v2";
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
  security_mode?: "legacy_v1" | "verified_v2";
}

export interface KeyDirectoryResult extends ApiResult {
  user_id?: number;
  devices?: AccountDevice[];
  security_epoch?: number;
  directory_hash?: string;
  identity_sig_pub?: string;
  trust_enforced_at?: number | null;
  security_mode?: "legacy_v1" | "verified_v2";
  device_history?: DeviceHistoryEntry[];
  approval_certificates?: ApprovalCertificate[];
  revocation_certificates?: RevocationCertificate[];
  security_upgrade_certificates?: SecurityUpgradeCertificate[];
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

export interface BlocklistResult {
  ok: boolean;
  rules?: BlockRule[];
  rule?: BlockRule;
  error?: string;
}

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
  carrier_status?: string;
  carrier_error?: string | null;
  carrier_updated_at?: number | null;
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
  ): Promise<T> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
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

  private post<T extends ApiResult = any>(path: string, body: unknown): Promise<T> {
    return this.request<T>(path, { method: "POST", body: JSON.stringify(body) });
  }

  private get<T extends ApiResult = any>(path: string): Promise<T> {
    return this.request<T>(path);
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
  deviceLoginChallenge(username: string, pwHash: string, sid: string): Promise<DeviceRegisterResult> {
    return this.deviceLogin(username, pwHash, sid);
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
    challenge: string,
    parentEpoch: number,
    signature: string,
    pairing?: { pairing_id: string; nonce_new: string; nonce_approver: string },
  ): Promise<ApiResult> {
    // challenge is covered by the signature and retained in this method's
    // interface for callers; the server resolves it from the pending row.
    void challenge;
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
  renameConversation(cid: string, name: string): Promise<{ ok: boolean; cid?: string; name?: string; error?: string }> {
    return this.post("/conversation/rename", { cid, name });
  }
  createConversation(members: string[], name?: string) {
    return this.post("/conversation", { members, ...(name ? { name } : {}) });
  }
  listConversations() { return this.get("/conversations"); }
  convMembers(cid: string): Promise<ConversationMembersResult> {
    return this.get(`/conversation/${encodeURIComponent(cid)}/members`);
  }
  fetchMessages(cid: string, since: number, limit = 200): Promise<{ ok: boolean; messages?: ServerMessage[]; error?: string }> {
    return this.get(`/conversation/${encodeURIComponent(cid)}/messages?since=${since}&limit=${limit}`);
  }
}

export const api = new Api();

// ----- socket -----------------------------------------------------------

let _socket: Socket | null = null;
let _socketToken: string | null = null;
let _socketBase: string | undefined;

/** Test/deploy override. Browsers derive the origin automatically. */
export function setSocketBase(base: string | undefined): void {
  _socketBase = base;
}

export function getSocket(token: string): Socket {
  if (_socket && _socketToken === token) return _socket;
  if (_socket) {
    _socket.removeAllListeners();
    _socket.disconnect();
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
  _socketToken = token;
  return _socket;
}

export function disconnectSocket(): void {
  if (_socket) {
    _socket.removeAllListeners();
    _socket.disconnect();
    _socket = null;
  }
  _socketToken = null;
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
    let finished = false;
    const timer = setTimeout(() => {
      if (finished) return;
      finished = true;
      resolve({ ok: false, error: "메시지 전송 확인 시간 초과", timedOut: true });
    }, SOCKET_ACK_TIMEOUT_MS);
    socket.emit("message_send", { cid, mid: messageId, payload: envelope }, (ack: unknown) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      resolve(isMessageAck(ack) ? ack : { ok: false, error: "서버 확인 응답 없음" });
    });
  });
}

function isMessageAck(value: unknown): value is MessageAck {
  return typeof value === "object" && value !== null
    && typeof (value as MessageAck).ok === "boolean";
}

export type { Envelope, RecipientDevice };
