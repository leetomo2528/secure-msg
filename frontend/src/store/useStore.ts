/**
 * Global state + message handling orchestration.
 *
 * Responsibilities:
 *   - Hold current auth (token, username, sid, keypair).
 *   - Wire Socket.IO events to message decryption + blocklist + IndexedDB.
 *   - Expose actions: onboarding, sending, fetching history, blocklist editing.
 */
import { create } from "zustand";
import {
  api,
  DEVICE_REVOKED,
  getSocket,
  isCompleteKeyDirectory,
  ownDirectoryProof,
  disconnectSocket,
  waitForSocketConnected,
  sendMessage,
  type ServerMessage,
  type BlockRule,
  type BlockRuleType,
  type ConversationMembersResult,
} from "../net/api";
import {
  initCrypto,
  generateKeypair,
  hashPassword,
  saltForUser,
  encryptMessage,
  decryptMessageWithSender,
  unb64u,
  signDetached,
  type DeviceKeypair,
  type Envelope,
  type RecipientDevice,
} from "../crypto/keys";
import { canonicalDeviceLoginProof } from "../net/api";
import { deviceFingerprint, recipientKeysetHash, verifyDirectoryProof } from "../crypto/deviceTrust";
import {
  setMeta,
  getMeta,
  clearSessionData,
  clearDeviceForReregistration,
  clearAllData,
  putMessage,
  listMessages,
  listAllMessages,
  setBlocked,
  setCarrierStatus,
  getCursor,
  setCursor,
  hasUnclassifiedMessages,
  isDirectionBackfilled,
  markDirectionBackfilled,
  patchMessageDirections,
  highestStoredSeq,
  markConversationRead,
  conversationSummaries,
  getUndecryptableFloor,
  setUndecryptableFloor,
  addBlockKeyword,
  removeBlockKeyword,
  listBlockKeywords,
  putBlockKeywordRow,
  blockKeywordRejection,
  addBlockedSender,
  removeBlockedSender,
  listBlockedSenders,
  putBlockedSenderRow,
  blockedSenderRejection,
  replaceBlockRules,
  type MessageRow,
  type BlockRow,
  type SenderRow,
  type ConversationSummary,
  type MessageAttachment,
  pinTrustedDirectory,
  pinTrustedDirectories,
  listTrustedDevices,
  TrustViolationError,
} from "./db";
import { matchBlockKeywords } from "./blocklist";
import { normalizePhone, ownedSmsPhone, SMS_PHONE_RE } from "./conversationPolicy";
import { sessionCoordinator } from "./sessionCoordinator";
import {
  decodeRelayContent,
  conversationDisplayName,
  errorText,
  isSafeMimeType,
  MAX_ATTACHMENTS,
  MAX_ATTACHMENT_BYTES,
  MAX_SUBJECT_CHARS,
  MAX_TEXT_CHARS,
  matchesBlockedSender,
  messageDirection,
  recordedDirection,
  ruleToKeywordRow,
  ruleToSenderRow,
} from "./helpers";

// Re-exported so existing tests/consumers keep importing from this module.
export { decodeRelayContent };

const NOTIFY_PREF_KEY = "securemsg-notify";

const SESSION_EXPIRED_ERROR = "로그인이 만료되었거나 이 기기가 폐기되었습니다. 다시 로그인하세요.";

/** Set by loginExistingDevice when the server answered 404/403 — the stored
 * device is gone (revoked) and re-registration with a fresh keypair is safe.
 * Read and reset by login(); keeps the boolean return contract stable. */
let lastExistingDeviceGone = false;

function readNotifyPref(): boolean {
  try {
    return typeof localStorage !== "undefined"
      && localStorage.getItem(NOTIFY_PREF_KEY) === "1";
  } catch {
    return false;
  }
}

export interface Conversation {
  cid: string;
  conv_id: number;
  /** Stable phone/SMS identity; never substitute a contact label in routing. */
  name: string;
  /** Account-wide contact label supplied by the server for presentation only. */
  synced_contact_name?: string | null;
  members: string[];
  created_at: number;
}

export interface RelayContent {
  v: 1;
  type: "text" | "mms";
  text: string;
  subject?: string;
  /**
   * Which way the message travelled, sealed inside the envelope so the relay
   * can neither read nor forge it. Optional: it stays absent on everything
   * relayed before the field existed, and on clients that have not shipped it
   * yet. Stays under `v: 1` on purpose — see decodeRelayContent.
   */
  dir?: "in" | "out";
  attachments?: MessageAttachment[];
}

/**
 * Identity of one auth/trust lifetime. The bearer token is deliberately not a
 * member: sliding renewal rotates it under a live session, while every real
 * identity change (login attempt, logout, forget-device) bumps `generation`.
 * Operations carry this snapshot and read the credential live.
 */
export interface SecurityContext {
  generation: number;
  uid: number | null;
  sid: string | null;
  keypair: DeviceKeypair | null;
}

/**
 * A login that cannot reuse a stored device. `fresh` is a browser with no
 * local device for this account; `replaced` is one whose stored device the
 * relay no longer accepts (revoked elsewhere), so its keypair is discarded and
 * a new one registered. Both lose access to existing history until another
 * device shares it, which is what the user is asked to confirm.
 */
export interface NewDeviceNotice {
  username: string;
  reason: "fresh" | "replaced";
}

interface State {
  ready: boolean;
  authed: boolean;
  approvalPending: boolean;
  securityLocked: boolean;
  /** Invalidates every async operation captured under an older auth/trust state. */
  securityGeneration: number;
  username: string | null;
  uid: number | null;
  sid: string | null;
  deviceName: string | null;
  keypair: DeviceKeypair | null;
  conversations: Conversation[];
  activeCid: string | null;
  activeMessages: MessageRow[];
  /**
   * Newest line, its time and the unread count per conversation, keyed by cid.
   *
   * Without it an arrival is invisible unless the affected thread happens to
   * be the one on screen: the sync writes the row to IndexedDB and the only
   * state it touches is `activeMessages`, which a closed thread never reads.
   * The desktop notification fires precisely when the app is NOT being looked
   * at, so the two conditions are complementary and the sidebar would sit
   * unchanged next to a notification about a message it is not showing.
   */
  convMeta: Record<string, ConversationSummary>;
  /** Live QR pairing session + registration challenge while this device awaits approval. */
  pendingPairing: { pairingId: string; nonceApprover: string; expiresAt: number } | null;
  pendingChallenge: string | null;
  blockKeywords: BlockRow[];
  blockedSenders: SenderRow[];
  notifyEnabled: boolean;
  /**
   * Set when a login would register this browser as a BRAND-NEW device. The
   * user must acknowledge that the new device starts with no readable history
   * before any keypair is registered, so this is a real UI gate, not a hint.
   */
  pendingNewDevice: NewDeviceNotice | null;
  error: string | null;

  init: () => Promise<void>;
  requestEmailRegistration: (username: string, email: string, password: string) => Promise<string | null>;
  verifyEmailRegistration: (username: string, email: string, password: string, challengeId: string, code: string) => Promise<boolean>;
  login: (username: string, password: string) => Promise<boolean>;
  confirmNewDevice: (password: string) => Promise<boolean>;
  cancelNewDevice: () => void;
  addDevice: (username: string, password: string, deviceName: string) => Promise<boolean>;
  loginExistingDevice: (username: string, password: string) => Promise<boolean>;
  logout: () => Promise<void>;
  forgetLocalDevice: () => Promise<void>;
  /** Drop a device the relay reports revoked, keeping the account's trust pins. */
  discardRevokedDevice: () => Promise<void>;
  refreshPendingApproval: () => Promise<"pending" | "approved" | "revoked" | "error">;
  refreshConversations: () => Promise<void>;
  newConversation: (members: string[]) => Promise<string | null>;
  newSmsConversation: (phone: string) => Promise<string | null>;
  selectConversation: (cid: string) => Promise<void>;
  syncConversation: (cid: string, context?: SecurityContext) => Promise<void>;
  /** Recompute the sidebar's arrival state from the rows on disk. */
  refreshConvMeta: () => Promise<void>;
  /** Stamp direction onto history stored before the field existed. */
  backfillDirections: () => Promise<void>;
  send: (cid: string, text: string) => Promise<boolean>;
  sendContent: (cid: string, content: RelayContent) => Promise<boolean>;
  addBlock: (kw: string) => Promise<void>;
  removeBlock: (id: string) => Promise<void>;
  addBlockedSenderRule: (sender: string) => Promise<void>;
  removeBlockedSenderRule: (id: string) => Promise<void>;
  refreshBlocklist: () => Promise<void>;
  syncBlockRules: () => Promise<void>;
  renameConversation: (cid: string, name: string) => Promise<boolean>;
  setNotifyEnabled: (enabled: boolean) => Promise<void>;
}

export const useStore = create<State>((set, get) => ({
  ready: false,
  authed: false,
  approvalPending: false,
  securityLocked: false,
  securityGeneration: 0,
  username: null,
  uid: null,
  sid: null,
  deviceName: null,
  keypair: null,
  conversations: [],
  activeCid: null,
  activeMessages: [],
  convMeta: {},
  pendingPairing: null,
  pendingChallenge: null,
  blockKeywords: [],
  blockedSenders: [],
  notifyEnabled: readNotifyPref(),
  pendingNewDevice: null,
  error: null,

  init: async () => {
    // Expired JWTs / revoked devices surface as REST 401s. Drop back to the
    // password screen instead of stranding the user in a broken authed state.
    api.onUnauthorized = () => {
      const context = captureSecurityContext();
      if (!canUseCrypto(context)) return;
      logoutExpiredSession(context);
    };
    try {
      await initCrypto();
      const meta = await getMeta();
      if (meta) {
        set({
          // JWTs intentionally live only in memory. A reload must return to the
          // password screen before any account data or socket is exposed.
          authed: false,
          username: meta.username,
        });
      }
    } catch (error) {
      set({ error: errorText(error) });
    } finally {
      set({ ready: true });
    }
  },

  requestEmailRegistration: async (username, email, password) => {
    const entryGeneration = get().securityGeneration;
    try {
      if (!/^[a-z0-9_]{3,20}$/.test(username)) {
        set({ error: "아이디는 영소문자·숫자·_ 3~20자로 입력하세요" });
        return null;
      }
      if (!/^[^@\s]{1,64}@[^@\s]{1,255}\.[^@\s]{2,63}$/.test(email)) {
        set({ error: "올바른 이메일 주소를 입력하세요" });
        return null;
      }
      if (password.length < 8 || password.length > 1024) {
        set({ error: "비밀번호는 8~1,024자로 입력하세요" });
        return null;
      }
      const pwHash = await hashPassword(password, saltForUser(username));
      if (get().securityGeneration !== entryGeneration) return null;
      const result = await api.registerEmailRequest(username, email, pwHash);
      if (get().securityGeneration !== entryGeneration) return null;
      if (!result.ok || !result.challenge_id) {
        set({ error: result.error ?? "인증 메일을 보내지 못했습니다." });
        return null;
      }
      set({ error: null });
      return result.challenge_id;
    } catch (error) {
      set({ error: errorText(error) });
      return null;
    }
  },

  verifyEmailRegistration: async (username, email, password, challengeId, code) => {
    const entryGeneration = get().securityGeneration;
    try {
      const result = await api.registerEmailVerify(challengeId, code);
      if (get().securityGeneration !== entryGeneration) return false;
      if (!result.ok || result.username !== username || result.email !== email) {
        set({ error: result.error ?? "인증 코드가 올바르지 않습니다." });
        return false;
      }
      return await get().addDevice(username, password, "web-browser");
    } catch (error) {
      set({ error: errorText(error) });
      return false;
    }
  },

  login: async (username, password) => {
    const entryGeneration = get().securityGeneration;
    set({ pendingNewDevice: null });
    try {
      if (!/^[a-z0-9_]{3,20}$/.test(username) || password.length < 1 || password.length > 1024) {
        set({ error: "아이디 또는 비밀번호 형식을 확인하세요" });
        return false;
      }
      const salt = saltForUser(username);
      const pwHash = await hashPassword(password, salt);
      if (get().securityGeneration !== entryGeneration) return false;
      const r = await api.login(username, pwHash);
      if (get().securityGeneration !== entryGeneration) return false;
      if (!r.ok) { set({ error: r.error || "login failed" }); return false; }
      // Decide: existing device or new device?
      const meta = await getMeta();
      if (get().securityGeneration !== entryGeneration) return false;
      if (meta && meta.username !== username) {
        set({
          error: `이 브라우저에는 ${meta.username} 기기 키가 남아 있습니다. 먼저 아래의 로컬 기기 초기화를 실행하세요.`,
        });
        return false;
      }
      if (meta && meta.username === username) {
        const reused = await get().loginExistingDevice(username, password);
        if (reused) return true;
        // A device revoked from another session must get a fresh keypair. Do not
        // discard keys on transient network errors. Prefer the structured
        // HTTP status classification; the prose match only covers old servers.
        const deviceGone = lastExistingDeviceGone
          || /^(device not found|device revoked)$/.test(get().error ?? "");
        lastExistingDeviceGone = false;
        if (!deviceGone) return false;
        // Registering a replacement keypair is exactly as destructive to
        // history as a first registration, so it waits for the same consent.
        set({ pendingNewDevice: { username, reason: "replaced" }, error: null });
        return false;
      }
      // No local device for this user → registering one makes this browser a
      // new device that cannot read anything sent before it existed. Stop and
      // let the user confirm that in the UI; confirmNewDevice() continues.
      set({ pendingNewDevice: { username, reason: "fresh" }, error: null });
      return false;
    } catch (error) {
      set({ error: errorText(error) });
      return false;
    }
  },

  /**
   * Continue a login the new-device warning stopped. The password is passed
   * back in rather than held in the store: nothing about this flow needs a
   * credential to survive between two user gestures.
   */
  confirmNewDevice: async (password) => {
    const pending = get().pendingNewDevice;
    if (!pending) return false;
    if (pending.reason === "replaced") {
      // Trust-preserving cleanup: drops the rejected keypair and this device's
      // session data while keeping the pinned account/device trust anchors.
      const fallbackGeneration = get().securityGeneration;
      await sessionCoordinator.exclusive(async () => {
        if (get().securityGeneration !== fallbackGeneration) return;
        await clearDeviceForReregistration();
      });
      if (get().securityGeneration !== fallbackGeneration) return false;
      if (get().pendingNewDevice !== pending) return false;
    }
    set({ pendingNewDevice: null });
    return await get().addDevice(
      pending.username, password, "device-" + Math.random().toString(36).slice(2, 6),
    );
  },

  cancelNewDevice: () => {
    set({ pendingNewDevice: null, error: null });
  },

  addDevice: async (username, password, deviceName) => {
    const attemptGeneration = beginAuthAttempt();
    const salt = saltForUser(username);
    const pwHash = await hashPassword(password, salt);
    if (get().securityGeneration !== attemptGeneration) return false;
    const kp = generateKeypair();
    const r = await api.deviceRegister(username, pwHash, deviceName, kp.box.pk, kp.sign.pk);
    if (get().securityGeneration !== attemptGeneration) return false;
    if (!r.ok || !r.token || !r.sid) {
      set({ error: r.error || "device register failed" });
      return false;
    }
    const approvalPending = r.trust_state === "pending";
    const registeredSid = r.sid;
    const meta = { username, uid: r.uid!, sid: registeredSid, deviceName, keypair: kp };
    return await installSession(attemptGeneration, meta, r.token, approvalPending);
  },

  loginExistingDevice: async (username, password) => {
    const attemptGeneration = beginAuthAttempt();
    const meta = await getMeta();
    if (get().securityGeneration !== attemptGeneration) return false;
    if (!meta || meta.username !== username) { set({ error: "no local device" }); return false; }
    const salt = saltForUser(username);
    const pwHash = await hashPassword(password, salt);
    if (get().securityGeneration !== attemptGeneration) return false;
    const challenge = await api.deviceLogin(username, pwHash, meta.sid);
    if (get().securityGeneration !== attemptGeneration) return false;
    if (!challenge.ok || challenge.uid === undefined || !challenge.challenge_id ||
        !challenge.challenge || challenge.session_version === undefined) {
      if (challenge.status === 404 || challenge.status === 403) lastExistingDeviceGone = true;
      set({ error: challenge.error || "device login challenge failed" });
      return false;
    }
    const proof = signDetached(canonicalDeviceLoginProof({
      uid: challenge.uid, sid: meta.sid, challenge_id: challenge.challenge_id,
      challenge: challenge.challenge, session_version: challenge.session_version,
    }), meta.keypair.sign.sk);
    const r = await api.deviceLoginProof(
      username, pwHash, meta.sid, challenge.challenge_id, challenge.challenge, proof,
    );
    if (get().securityGeneration !== attemptGeneration) return false;
    if (!r.ok || !r.token) {
      if (r.status === 404 || r.status === 403) lastExistingDeviceGone = true;
      set({ error: r.error || "device login failed" });
      return false;
    }
    const approvalPending = r.trust_state === "pending";
    return await installSession(attemptGeneration, meta, r.token, approvalPending);
  },

  refreshPendingApproval: async () => {
    const context = captureSecurityContext();
    if (!get().approvalPending || !api.token) return get().authed ? "approved" : "error";
    const result = await api.deviceApprovalStatus();
    if (!sameContext(context)) return "error";
    if (!result.ok) {
      // Only the relay saying THIS device's row is revoked counts. A bare 401
      // is also what an expired or superseded token returns — and the caller
      // reacts by discarding local device state, so treating the two alike let
      // any 401, including one a hostile relay simply chose to send, throw the
      // registration away.
      if (result.code === DEVICE_REVOKED) return "revoked";
      set({ error: result.error ?? "기기 승인 상태를 확인하지 못했습니다." });
      return "error";
    }
    if (result.trust_state === "approved") {
      set({
        approvalPending: false,
        error: null,
        pendingPairing: null,
        pendingChallenge: null,
      });
      await postLogin(context);
      return "approved";
    }
    if (result.trust_state === "revoked" || result.trust_state === "rejected") return "revoked";
    // Pending: expose the registration challenge (QR payload) and any live
    // pairing session so the UI can render the safety number.
    set({
      pendingChallenge: result.challenge ?? null,
      pendingPairing: result.pairing
        ? {
            pairingId: result.pairing.pairing_id,
            nonceApprover: result.pairing.nonce_approver,
            expiresAt: result.pairing.expires_at,
          }
        : null,
    });
    return "pending";
  },

  logout: async () => {
    const rememberedUsername = get().username;
    const logoutToken = api.token;
    const logoutGeneration = get().securityGeneration + 1;
    disconnectSocket();
    set({
      securityGeneration: logoutGeneration,
      ...clearedSessionState(),
      pendingNewDevice: null,
    });

    // Queue cleanup immediately after invalidation. Any DB effect which already
    // acquired the coordinator finishes first; no stale effect can acquire it
    // afterwards, and a subsequent login installation queues behind the clear.
    const cleanup = sessionCoordinator.exclusive(async () => {
      let localUsername = rememberedUsername;
      let failed = false;
      try {
        localUsername = (await getMeta())?.username ?? localUsername;
      } catch {
        failed = true;
      }
      try {
        await clearSessionData();
      } catch {
        failed = true;
      }
      return { localUsername, failed };
    });
    try {
      // Best effort: revoke the bearer token while it is still available.
      // A rejected/expired token or offline server must never trap the user in
      // a local authenticated state.
      if (logoutToken && api.token === logoutToken) await api.logout();
    } catch {
      // The normal API path returns a structured failure, but also tolerate an
      // unexpected transport/runtime exception and complete local logout.
    }

    if (api.token === logoutToken) api.setToken(null);
    const { localUsername, failed: cleanupFailed } = await cleanup;
    if (get().securityGeneration !== logoutGeneration || get().authed) return;
    set({
      ...clearedSessionState(),
      username: localUsername ?? null,
      error: cleanupFailed
        ? "로그아웃됐지만 브라우저의 로컬 캐시를 완전히 지우지 못했습니다. 브라우저 사이트 데이터를 삭제하세요."
        : null,
    });
  },

  forgetLocalDevice: async () => { await resetLocalDevice(clearAllData); },

  discardRevokedDevice: async () => { await resetLocalDevice(clearDeviceForReregistration); },

  refreshConversations: async () => {
    const context = captureSecurityContext();
    if (!sameContext(context)) return;
    const r = await api.listConversations();
    if (!sameContext(context)) return;
    if (r.ok && r.conversations) {
      set({ conversations: r.conversations, error: null });
    } else {
      set({ error: r.error || "대화 목록을 불러오지 못했습니다" });
    }
    // The relay knows nothing about message bodies or read state, so the list
    // it just returned carries no arrival information at all. Fill that in
    // from disk on the same pass, or a reload shows every thread as if it had
    // never received anything until each one is synced.
    if (!sameContext(context)) return;
    await get().refreshConvMeta();
  },

  newConversation: async (members) => {
    const context = captureSecurityContext();
    if (!sameContext(context)) return null;
    const r = await api.createConversation(members);
    if (!sameContext(context)) return null;
    if (!r.ok || !r.cid) { set({ error: r.error || "create failed" }); return null; }
    await get().refreshConversations();
    if (!sameContext(context)) return null;
    return r.cid as string;
  },

  newSmsConversation: async (phone) => {
    const context = captureSecurityContext();
    if (!sameContext(context)) return null;
    const username = get().username;
    const normalized = normalizePhone(phone);
    if (!username || !SMS_PHONE_RE.test(normalized)) {
      set({ error: "유효한 전화번호를 입력하세요" });
      return null;
    }
    const existing = get().conversations.find(
      (item) => ownedSmsPhone(item, username) === normalized,
    );
    if (existing) return existing.cid;
    const r = await api.createConversation([username], normalized);
    if (!sameContext(context)) return null;
    if (!r.ok || !r.cid) {
      set({ error: r.error || "SMS 대화 생성 실패" });
      return null;
    }
    await get().refreshConversations();
    if (!sameContext(context)) return null;
    return r.cid as string;
  },

  selectConversation: async (cid) => {
    const context = captureSecurityContext();
    // Split the update: awaiting listMessages() inside set() let a fast
    // A→B→A click sequence land one conversation's messages under another's
    // header. Guard the async result against the still-active conversation.
    set({ activeCid: cid, activeMessages: [] });
    await refreshActiveMessages(cid, () => sameContext(context));
    if (!sameContext(context)) return;
    // Clear the badge from what is already on disk before the pull, so opening
    // a thread reads as read immediately rather than after a network round
    // trip; the sync marks the newly arrived tail read on its way out.
    const seen = await highestStoredSeq(cid);
    if (!sameContext(context)) return;
    await markConversationRead(cid, seen);
    if (!sameContext(context)) return;
    await get().refreshConvMeta();
    if (!sameContext(context)) return;
    await queueConversationSync(cid, context);
  },

  syncConversation: async (cid, suppliedContext) => {
    const context = suppliedContext ?? captureSecurityContext();
    if (!canUseCrypto(context)) return;
    // 1. Fetch the member devices: the key directory proof below is verified
    // against this list, and it names which SIDs are the carrier gateway.
    const mr = await api.convMembers(cid);
    if (!canUseCrypto(context)) return;
    if (!mr.ok || !mr.members) {
      if (sameContext(context)) set({ error: mr.error || "대화 기기 목록을 불러오지 못했습니다" });
      return;
    }
    try {
      await verifyConversationKeyDirectory(mr, () => canUseCrypto(context));
      if (!canUseCrypto(context)) return;
    } catch (error) {
      lockForTrustViolation(error, context);
      return;
    }
    if (!canUseCrypto(context)) return;
    // History shared by another of our own devices is sealed by THAT device, so
    // it opens with the wrapper's key rather than the sender's. Only a pinned
    // key is accepted: a relay that could name a wrapper and hand over the
    // matching public key would be choosing what this device decrypts.
    const wrapperPubkeys = await ownDeviceWrapperKeys(context);
    if (!canUseCrypto(context)) return;

    // 2. Pull every page since our last cursor. Advance even past an envelope
    // this device cannot decrypt, so one malformed row cannot starve all newer
    // history forever.
    if (!canUseCrypto(context)) return;
    const mySid = context.sid!;
    const myKeypair = context.keypair!;
    // Sender blocking: an SMS thread's carrier messages arrive via the Android
    // gateway device. If the thread's phone number is blocked, hide them.
    const conv = useStore.getState().conversations.find((c) => c.cid === cid);
    const smsPhone = conv ? ownedSmsPhone(conv, useStore.getState().username) : null;
    const blockedSenders = await listBlockedSenders();
    if (!canUseCrypto(context)) return;
    const senderBlocked = smsPhone != null && matchesBlockedSender(smsPhone, blockedSenders);
    const blockKeywords = await listBlockKeywords();
    if (!canUseCrypto(context)) return;
    const gatewaySids = new Set(
      mr.members.filter((m) => m.kind === "android_gateway").map((m) => m.sid),
    );
    const pageSize = 200;
    const deliveredCursor = await getCursor(cid);
    if (!canUseCrypto(context)) return;
    const previousFloor = await getUndecryptableFloor(cid);
    if (!canUseCrypto(context)) return;
    // A cursor ahead of the rows actually held here hides them permanently:
    // every pull asks for `seq > cursor`, so the relay never offers them
    // again even though it still has them. The two writes are separate
    // transactions (putMessage, then setCursor), and clearSessionData() is
    // origin-wide while the security context is per-tab, so a second tab
    // logging out between them leaves exactly that state. Fall back to the
    // real high-water mark on disk — unless the re-read floor already
    // accounts for the gap, in which case those rows are undecryptable
    // rather than lost and re-downloading them every pass buys nothing.
    const storedTop = await highestStoredSeq(cid);
    if (!canUseCrypto(context)) return;
    const gapIsUnexplained =
      storedTop < deliveredCursor && (previousFloor == null || previousFloor > storedTop + 1);
    const startCursor = gapIsUnexplained ? storedTop : deliveredCursor;
    // Keys another device shared for old messages arrive with no event of any
    // kind, and this device's cursor has long since moved past them, so the
    // gap is only ever found by looking again. Re-reading from the floor on
    // every pass would re-download the whole thread each time an unopenable
    // message sits at the bottom of it, and a once-per-session allowance is
    // worse: it is spent by whichever pass happens to run before the other
    // device shares, and then the keys are invisible until the page reloads.
    // Instead probe the ONE message at the floor. It is the sharer's own
    // starting point (share runs from the relay's missing-keys list, oldest
    // first), so it becoming readable is the signal that new key material
    // landed — and a gap that stays shut costs one message per sync, not one
    // thread.
    let cursor = startCursor;
    if (previousFloor != null && previousFloor - 1 < startCursor) {
      const probe = await api.fetchMessages(cid, previousFloor - 1, 1);
      if (!canUseCrypto(context)) return;
      const candidate = probe.ok ? probe.messages?.[0] : undefined;
      if (candidate && candidate.seq === previousFloor) {
        let probeSenderKey: string;
        try {
          probeSenderKey = verifiedSenderPublicKey(
            mr, candidate.sender_id, candidate.sender_sid, candidate.sender_pub_key,
          );
        } catch (error) {
          lockForTrustViolation(error, context);
          return;
        }
        const opened = decryptMessageWithSender(
          candidate.payload, mySid, myKeypair, probeSenderKey,
          (sid) => wrapperPubkeys.get(sid) ?? null,
        );
        if (opened != null) cursor = previousFloor - 1;
      }
    }
    const rescan = cursor < startCursor;
    let lowestUndecryptable: number | null = null;
    let notifyBody: string | null = null;
    let notifyIsIncoming = false;
    while (true) {
      // Abort if the user logged out or switched accounts mid-sync; otherwise
      // decrypted rows would be written back after clearSessionData().
      if (!canUseCrypto(context)) return;
      const fr = await api.fetchMessages(cid, cursor, pageSize);
      if (!canUseCrypto(context)) return;
      if (!fr.ok || !fr.messages) {
        if (sameContext(context)) set({ error: fr.error || "메시지 동기화 실패" });
        return;
      }
      if (fr.messages.length === 0) break;

      let maxSeq = cursor;
      for (const sm of fr.messages) {
        maxSeq = Math.max(maxSeq, sm.seq);
        if (!canUseCrypto(context)) return;
        let senderPubKey: string;
        try {
          senderPubKey = verifiedSenderPublicKey(
            mr,
            sm.sender_id,
            sm.sender_sid,
            sm.sender_pub_key,
          );
        } catch (error) {
          lockForTrustViolation(error, context);
          return;
        }
        if (!canUseCrypto(context)) return;
        const plaintext = decryptMessageWithSender(
          sm.payload, mySid, myKeypair, senderPubKey,
          (sid) => wrapperPubkeys.get(sid) ?? null,
        );
        if (plaintext == null) {
          if (lowestUndecryptable == null || sm.seq < lowestUndecryptable) {
            lowestUndecryptable = sm.seq;
          }
          continue;
        }
        const content = decodeRelayContent(plaintext);
        // Decided here so the putMessage below is the only write of `blocked`.
        // The two setBlocked transactions this replaced ran before the row
        // existed — no-ops on a forward sync — and on a rescan wrote a value
        // that same putMessage overwrote one line later, once per message
        // inside the session coordinator.
        const shouldShow = !matchBlockKeywords(
          [content.subject, content.text].filter(Boolean).join("\n"),
          blockKeywords,
        ).blocked && !(senderBlocked && gatewaySids.has(sm.sender_sid));
        // Gated on the DELIVERED cursor, not the healed start: a row being
        // re-read to repair a gap was already acked once, and notifying for
        // it again would announce the repair rather than a new message.
        // Sealed field first, relay-supplied id shape second, nothing third.
        // Resolved once here rather than at render: the mid is not persisted,
        // so a later read would have no way to work it out again.
        //
        // The id shape is read ONLY for our own account's devices. Every other
        // client mints a UUID too — a peer's browser included — so applying it
        // more widely would put someone else's message on our side. Within the
        // account it is unambiguous: `in_` is minted on exactly one code path,
        // the gateway's carrier-receive, and nothing else ever writes it.
        const direction = recordedDirection(
          content.dir, sm.client_mid, sm.sender_id, context.uid,
        );
        // A text the owner typed on their own phone arrives under the gateway's
        // sid like any other relayed message, so the old sender check announced
        // the owner's own messages back to them.
        if (shouldShow && sm.seq > deliveredCursor
          && messageDirection(
            { direction, sender_sid: sm.sender_sid, sender_id: sm.sender_id }, mySid, context.uid,
          ) !== "out") {
          notifyBody = content.text || content.subject || "(첨부파일)";
          notifyIsIncoming = true;
        }
        if (!canUseCrypto(context)) return;
        const wrote = await runSessionEffect(context, () => putMessage({
          id: "", seq: sm.seq, cid, sender_id: sm.sender_id,
          sender_sid: sm.sender_sid, plaintext: content.text, created_at: sm.created_at * 1000,
          direction,
          blocked: !shouldShow, content_type: content.type, subject: content.subject ?? null,
          attachments: content.attachments, carrier_status: sm.carrier_status ?? "none",
          carrier_error: sm.carrier_error,
          carrier_updated_at: sm.carrier_updated_at ? sm.carrier_updated_at * 1000 : null,
        }));
        if (!wrote) return;
      }
      if (!canUseCrypto(context)) return;
      if (!await runSessionEffect(context, () => setCursor(cid, maxSeq))) return;
      if (!canUseCrypto(context)) return;
      const socket = liveSocket();
      if (socket?.connected) {
        if (!canUseCrypto(context)) return;
        socket.emit("message_delivered", { cid, seq: maxSeq });
      }
      if (fr.messages.length < pageSize || maxSeq <= cursor) break;
      cursor = maxSeq;
    }
    // A pass that started at the floor covers every gap; one that skipped the
    // re-read only learned about gaps above the stored cursor, so the older
    // floor stands.
    const nextFloor = rescan || previousFloor == null
      ? lowestUndecryptable
      : Math.min(previousFloor, lowestUndecryptable ?? Number.MAX_SAFE_INTEGER);
    if (nextFloor !== previousFloor) {
      if (!await runSessionEffect(context, () => setUndecryptableFloor(cid, nextFloor))) return;
    }
    // Re-read state: the `me` snapshot predates the pagination loop, and the
    // user may have switched conversations while pages were being pulled.
    await refreshActiveMessages(cid, () => canUseCrypto(context));
    // A thread the user is looking at is read by definition; anything else
    // keeps its unread count so the sidebar can say a message landed.
    if (canUseCrypto(context) && useStore.getState().activeCid === cid) {
      const seen = await highestStoredSeq(cid);
      if (!await runSessionEffect(context, () => markConversationRead(cid, seen))) return;
    }
    if (!canUseCrypto(context)) return;
    await get().refreshConvMeta();
    if (canUseCrypto(context) && notifyIsIncoming && notifyBody != null) {
      maybeNotify(conversationDisplayName(conv, "새 메시지"), notifyBody);
    }
  },

  backfillDirections: async () => {
    // Rows stored before direction existed carry none, and it cannot be worked
    // out from what is on disk — the signal is a server column, not part of the
    // sealed body. So re-read the relay's metadata for those threads and stamp
    // the rows in place.
    //
    // Nothing here decrypts, advances a cursor, or notifies: a full re-sync
    // would do the job too, but rewinding the cursor re-announces months of
    // history as if it had just arrived. Conversations that are already
    // classified cost one indexed read and no network, so this is safe to run
    // on every login rather than behind a flag that can go stale.
    const context = captureSecurityContext();
    if (!canUseCrypto(context)) return;
    for (const conv of useStore.getState().conversations) {
      if (!canUseCrypto(context)) return;
      let skip: boolean;
      try {
        // The marker, not the row scan, is what ends this. Some rows can never
        // be classified — a peer's message, or one whose relay id has no
        // recognisable shape — and scanning alone would re-read those threads
        // in full on every login to write nothing.
        skip = await isDirectionBackfilled(conv.cid) || !await hasUnclassifiedMessages(conv.cid);
      } catch {
        continue;
      }
      if (skip) continue;
      let cursor = 0;
      while (true) {
        if (!canUseCrypto(context)) return;
        const page = await api.fetchMessages(conv.cid, cursor, 200);
        if (!canUseCrypto(context)) return;
        if (!page.ok || !page.messages || page.messages.length === 0) break;
        const entries: { seq: number; direction: "in" | "out" }[] = [];
        let maxSeq = cursor;
        for (const sm of page.messages) {
          maxSeq = Math.max(maxSeq, sm.seq);
          // Same rule as the live ingest, from the same helper: a peer's row
          // is left unrecorded rather than guessed at.
          const direction = recordedDirection(
            undefined, sm.client_mid, sm.sender_id, context.uid,
          );
          if (direction) entries.push({ seq: sm.seq, direction });
        }
        const stamped = await runSessionEffect(
          context,
          async () => { await patchMessageDirections(conv.cid, entries); },
        );
        if (!stamped) return;
        if (page.messages.length < 200 || maxSeq <= cursor) break;
        cursor = maxSeq;
      }
      if (!await runSessionEffect(context, () => markDirectionBackfilled(conv.cid))) return;
    }
    if (!sameContext(context)) return;
    await get().refreshConvMeta();
  },

  refreshConvMeta: async () => {
    const context = captureSecurityContext();
    if (!canUseCrypto(context)) return;
    const mySid = context.sid;
    if (!mySid) return;
    let summaries: Record<string, ConversationSummary>;
    try {
      summaries = await conversationSummaries(mySid, context.uid);
    } catch {
      // A sidebar without previews is a worse UI, not a broken one; never let
      // a read failure here take down the sync pass that called it.
      return;
    }
    if (!sameContext(context)) return;
    set({ convMeta: summaries });
  },

  send: async (cid, text) => {
    return await get().sendContent(cid, {
      v: 1,
      type: "text",
      text,
      attachments: [],
    });
  },

  sendContent: async (cid, content) => {
    const context = captureSecurityContext();
    try {
      const me = useStore.getState();
      if (!canUseCrypto(context)) return false;
      if (content.type !== "text" && content.type !== "mms") {
        if (sameContext(context)) set({ error: "지원하지 않는 메시지 형식입니다" });
        return false;
      }
      if (content.text.length > MAX_TEXT_CHARS) {
        set({ error: `메시지는 ${MAX_TEXT_CHARS.toLocaleString("en-US")}자까지 보낼 수 있습니다` });
        return false;
      }
      if ((content.subject?.length ?? 0) > MAX_SUBJECT_CHARS) {
        set({ error: `MMS 제목은 ${MAX_SUBJECT_CHARS}자까지 입력할 수 있습니다` });
        return false;
      }
      if ((content.attachments?.length ?? 0) > MAX_ATTACHMENTS) {
        set({ error: `첨부파일은 최대 ${MAX_ATTACHMENTS}개까지 가능합니다` });
        return false;
      }
      let attachmentBytes = 0;
      for (const attachment of content.attachments ?? []) {
        if (!Number.isInteger(attachment.size) || attachment.size < 0) {
          set({ error: "잘못된 첨부파일입니다" });
          return false;
        }
        if (!isSafeMimeType(attachment.content_type)) {
          set({ error: "첨부파일 형식 정보가 올바르지 않습니다" });
          return false;
        }
        try {
          if (unb64u(attachment.data).byteLength !== attachment.size) {
            set({ error: "첨부파일 크기 정보가 올바르지 않습니다" });
            return false;
          }
        } catch {
          set({ error: "첨부파일 데이터가 올바르지 않습니다" });
          return false;
        }
        attachmentBytes += attachment.size;
      }
      if (attachmentBytes > MAX_ATTACHMENT_BYTES) {
        set({ error: `첨부파일 전체 크기는 ${MAX_ATTACHMENT_BYTES / 1024}KB까지 가능합니다` });
        return false;
      }
      if (content.type === "mms" && (content.attachments?.length ?? 0) === 0 && !content.subject) {
        set({ error: "MMS에는 첨부파일 또는 제목이 필요합니다" });
        return false;
      }
      const contentJson = JSON.stringify({
        v: 1,
        type: content.type,
        text: content.text,
        ...(content.subject ? { subject: content.subject } : {}),
        // Sealed, so every device that opens this envelope agrees on the side
        // it belongs on without trusting the relay. Older clients ignore the
        // key; `v` deliberately stays 1 so they keep parsing the rest.
        dir: "out",
        attachments: content.attachments ?? [],
      });
      const socket = liveSocket();
      if (!socket || !await waitForSocketConnected(socket)) {
        if (sameContext(context)) set({ error: "실시간 서버에 연결할 수 없습니다" });
        return false;
      }
      if (!canUseCrypto(context)) return false;
      // Recipient list: every device of every conversation member.
      const mr = await api.convMembers(cid);
      if (!canUseCrypto(context)) return false;
      if (!mr.ok || !mr.members) {
        if (sameContext(context)) set({ error: mr.error || "members fetch failed" });
        return false;
      }
      try {
        await verifyConversationKeyDirectory(mr, () => canUseCrypto(context));
        if (!canUseCrypto(context)) return false;
      } catch (error) {
        lockForTrustViolation(error, context);
        return false;
      }
      const recipients: RecipientDevice[] = mr.members.map((m) => ({ sid: m.sid, pub_key: m.pub_key }));
      if (recipients.length === 0) {
        set({ error: "암호화할 수신 기기가 없습니다" });
        return false;
      }
      if (!canUseCrypto(context)) return false;
      const envelope: Envelope = await encryptMessage(contentJson, recipients, context.keypair!);
      if (!canUseCrypto(context)) return false;
      const ack = await sendMessage(socket, cid, envelope, () => canUseCrypto(context));
      if (!canUseCrypto(context)) return false;
      if (!ack.ok || !ack.seq) { set({ error: ack.error || "send failed" }); return false; }
      const sentSeq = ack.seq;
      // Optimistic local insert. The ordered REST sync advances the cursor.
      const conversation = useStore.getState().conversations.find((item) => item.cid === cid);
      const isSms = conversation ? ownedSmsPhone(conversation, me.username) !== null : false;
      if (!canUseCrypto(context)) return false;
      const wrote = await runSessionEffect(context, () => putMessage({
        id: "", seq: sentSeq, cid, sender_id: me.uid!,
        sender_sid: context.sid!, plaintext: content.text, created_at: Date.now(),
        direction: "out",
        blocked: false, content_type: content.type, subject: content.subject ?? null,
        attachments: content.attachments ?? [],
        carrier_status: isSms ? "queued" : "none",
        carrier_error: null,
        carrier_updated_at: null,
      }));
      if (!wrote) return false;
      if (!canUseCrypto(context)) return false;
      set({ error: null });
      await refreshActiveMessages(cid, () => canUseCrypto(context));
      return true;
    } catch (error) {
      if (sameContext(context)) set({ error: errorText(error) });
      return false;
    }
  },

  addBlock: async (kw) => { await addSharedBlockRule(KEYWORD_RULES, kw); },

  removeBlock: async (id) => { await removeSharedBlockRule(KEYWORD_RULES, id); },

  addBlockedSenderRule: async (sender) => { await addSharedBlockRule(SENDER_RULES, sender); },

  removeBlockedSenderRule: async (id) => { await removeSharedBlockRule(SENDER_RULES, id); },

  refreshBlocklist: async () => {
    const context = captureSecurityContext();
    const [blockKeywords, blockedSenders] = await Promise.all([
      listBlockKeywords(), listBlockedSenders(),
    ]);
    if (sameContext(context)) set({ blockKeywords, blockedSenders });
  },

  /** Reconcile local block rules with the server. Server rows are authoritative,
   * while local-only rows are retained until their individual upload succeeds. */
  syncBlockRules: async () => {
    const context = captureSecurityContext();
    if (!sameContext(context) || !api.token) return;
    const list = await api.listBlockRules();
    if (!sameContext(context)) return;
    if (!list.ok || !list.rules) return; // offline: keep local rules as-is
    const keywords = await reconcileRuleKind(KEYWORD_RULES, list.rules, context);
    if (!keywords) return;
    const senders = await reconcileRuleKind(SENDER_RULES, list.rules, context);
    if (!senders) return;
    if (!sameContext(context)) return;
    await replaceBlockRules(keywords, senders);
    if (!sameContext(context)) return;
    await get().refreshBlocklist();
  },

  renameConversation: async (cid, name) => {
    const context = captureSecurityContext();
    if (!sameContext(context)) return false;
    const r = await api.renameConversation(cid, name);
    if (!sameContext(context)) return false;
    if (!r.ok) {
      set({ error: r.error || "대화 이름을 변경하지 못했습니다" });
      return false;
    }
    set({
      conversations: get().conversations.map((c) =>
        c.cid === cid ? { ...c, name } : c,
      ),
    });
    return true;
  },

  setNotifyEnabled: async (enabled) => {
    if (!enabled) {
      try { localStorage.removeItem(NOTIFY_PREF_KEY); } catch { /* ignore */ }
      set({ notifyEnabled: false });
      return;
    }
    if (typeof Notification === "undefined") {
      set({ error: "이 브라우저는 데스크톱 알림을 지원하지 않습니다" });
      return;
    }
    let permission = Notification.permission;
    if (permission === "default") permission = await Notification.requestPermission();
    if (permission !== "granted") {
      set({ error: "알림 권한이 거부되어 있습니다" });
      return;
    }
    try { localStorage.setItem(NOTIFY_PREF_KEY, "1"); } catch { /* ignore */ }
    set({ notifyEnabled: true });
  },
}));

interface PostLoginJob {
  context: SecurityContext;
  promise: Promise<void>;
}

class RetryablePostLoginError extends Error {}

const postLoginJobs = new Map<number, PostLoginJob>();

function postLogin(suppliedContext?: SecurityContext): Promise<void> {
  const context = suppliedContext ?? captureSecurityContext();
  if (!canUseCrypto(context)) return Promise.resolve();

  const existing = postLoginJobs.get(context.generation);
  if (existing && contextsEqual(existing.context, context)) return existing.promise;

  // A generation uniquely identifies an auth/trust lifetime. Replacing a
  // different-context entry lets a newly authenticated session proceed even
  // while an invalidated session's setup is still awaiting I/O.
  let promise!: Promise<void>;
  promise = runPostLogin(context)
    .catch((error) => {
      if (postLoginJobs.get(context.generation)?.promise === promise) {
        postLoginJobs.delete(context.generation);
      }
      if (error instanceof RetryablePostLoginError) {
        if (sameContext(context)) useStore.setState({ error: error.message });
        return;
      }
      throw error;
    })
    .finally(() => {
      const current = postLoginJobs.get(context.generation);
      if (current?.promise === promise && !canUseCrypto(context)) {
        postLoginJobs.delete(context.generation);
      }
    });
  postLoginJobs.set(context.generation, { context, promise });
  return promise;
}

/**
 * Every event runPostLogin wires, named once for the detach that precedes the
 * wiring: a handler registered below but missing from this list re-registers
 * on top of itself if runPostLogin ever runs again on a live socket.
 */
const SESSION_SOCKET_EVENTS = [
  "connect", "connect_error", "message_new", "message_status",
  "blocklist_updated", "conv_updated", "contacts_updated", "device_pending",
] as const;

async function runPostLogin(context: SecurityContext): Promise<void> {
  const me = useStore.getState();
  // Verify the account key directory before fetching any encrypted history.
  // A known identity/key rollback is fail-closed for the entire message UI,
  // not merely a warning shown when the user happens to open DeviceManager.
  if (me.uid != null) {
    try {
      const directory = await api.keyDirectory();
      if (!canUseCrypto(context)) return;
      if (!directory.ok) {
        // A 404 used to skip verification and pinning entirely, for relays
        // predating /key-directory. Every supported server implements it now,
        // so a missing directory is a hostile or broken relay, not an old one:
        // suppressing this one response must not buy an attacker a session
        // with no identity pin.
        if (directory.status === 404) {
          throw new Error("서버가 키 디렉터리를 제공하지 않습니다. 신뢰할 수 없는 릴레이입니다.");
        }
        if (directory.status === 0 || (directory.status != null && directory.status >= 500)) {
          throw new RetryablePostLoginError(
            `${directory.error ?? "키 디렉터리를 불러오지 못했습니다."} 잠시 후 다시 시도하세요.`,
          );
        }
        throw new Error(directory.error ?? "키 디렉터리를 불러오지 못했습니다.");
      }
      if (!isCompleteKeyDirectory(directory)) {
        throw new Error("서버 키 디렉터리 응답이 불완전합니다.");
      }
      verifyDirectoryProof(ownDirectoryProof(me.uid, directory), directory.devices);
      if (!canUseCrypto(context)) return;
      const own = directory.devices.find((device) => device.sid === me.sid);
      if (!own || !me.keypair || own.pub_key !== me.keypair.box.pk || own.sig_pub !== me.keypair.sign.pk) {
        throw new Error("현재 SID의 로컬 키와 검증된 공개키 디렉터리가 일치하지 않습니다.");
      }
      const ownUid = me.uid!;
      const identitySigPub = directory.identity_sig_pub;
      const directoryHash = directory.directory_hash;
      const securityMode = directory.security_mode!;
      const directoryDevices = directory.devices;
      const pinned = await runSessionEffect(context, () => pinTrustedDirectory({
        uid: ownUid,
        identity_sig_pub: identitySigPub,
        security_epoch: directory.security_epoch!,
        directory_hash: directoryHash,
        security_mode: securityMode,
        devices: directoryDevices.map((device) => ({
          sid: device.sid,
          pub_key: device.pub_key,
          sig_pub: device.sig_pub,
          kind: device.kind,
          fingerprint: deviceFingerprint(device.pub_key, device.sig_pub).hash,
        })),
      }));
      if (!pinned) return;
      if (sameContext(context)) useStore.setState({ error: null });
    } catch (error) {
      if (error instanceof RetryablePostLoginError) throw error;
      lockForTrustViolation(error, context);
      return;
    }
  }
  if (!canUseCrypto(context)) return;
  // Sliding renewal: every signed-in app load trades the token for a fresh
  // 7-day one, so a session in regular use never hits the TTL cliff. Failure
  // is ignored — the token that made this call still works for now.
  // Rotating the credential must not invalidate this run: identity is
  // `context`, so every sync step below still passes its guard. Nothing orders
  // this renewal against the socket wiring below, and it must not matter:
  // getSocket keeps the live connection under the auth the server already
  // accepted, and the renewed token only reaches the next handshake
  // (SmsBridgeService.refreshAuthToken slides the Android bridge the same way).
  void api.tokenRefresh().then((renewed) => {
    if (renewed.ok && renewed.token && sameContext(context)) api.setToken(renewed.token);
  });
  await me.refreshBlocklist();
  if (!canUseCrypto(context)) return;
  // Pull shared block rules from the server (and push any local-only ones).
  await me.syncBlockRules().catch(() => undefined);
  if (!canUseCrypto(context)) return;
  await me.refreshBlocklist();
  if (!canUseCrypto(context)) return;
  await me.refreshConversations();
  if (!canUseCrypto(context)) return;
  // Before the socket: the sidebar and every thread read direction off the
  // stored rows, so history that predates the field should be classified
  // before the user can look at it. Costs nothing once it has run.
  await me.backfillDirections();
  if (!canUseCrypto(context)) return;
  // Wire socket.
  const socket = liveSocket();
  if (!socket || !canUseCrypto(context)) return;
  for (const event of SESSION_SOCKET_EVENTS) socket.off(event);
  const syncAll = async () => {
    if (!canUseCrypto(context)) return;
    const state = useStore.getState();
    useStore.setState({ error: null });
    await state.syncBlockRules().catch(() => undefined);
    if (!canUseCrypto(context)) return;
    await state.refreshBlocklist();
    if (!canUseCrypto(context)) return;
    await state.refreshConversations();
    if (!canUseCrypto(context)) return;
    // Re-read after refresh: `state` predates the conversation reload.
    for (const conv of useStore.getState().conversations) {
      if (!canUseCrypto(context)) return;
      await queueConversationSync(conv.cid, context);
    }
  };
  socket.on("connect", syncAll);
  socket.on("connect_error", (error: Error) => {
    if (!sameContext(context)) return;
    const detail = error?.message ?? "";
    // Servers >= v0.10.8 prefix refusals with a stable "auth_rejected:" code;
    // the prose match is the fallback for older servers.
    if (/^auth_rejected|auth required|invalid token|device unknown|unauthenticated/i.test(detail)) {
      logoutExpiredSession(context);
      return;
    }
    useStore.setState({ error: "실시간 서버 연결 실패 — 자동 재시도 중" });
  });
  socket.on("message_new", async (env: ServerMessage) => {
    if (!canUseCrypto(context)) return;
    // A conversation created on another device (e.g. the Android gateway
    // opening a new SMS thread) is not in our list yet — refresh so the
    // sidebar shows the thread the incoming message belongs to.
    if (!useStore.getState().conversations.some((c) => c.cid === env.cid)) {
      await useStore.getState().refreshConversations();
      if (!canUseCrypto(context)) return;
    }
    // Pull from the last contiguous local cursor. This avoids jumping over
    // older offline messages when the new event is for a later sequence.
    await queueConversationSync(env.cid, context);
  });
  socket.on("message_status", async (event: {
    cid: string;
    seq: number;
    carrier_status: string;
    carrier_error?: string | null;
    carrier_updated_at?: number | null;
  }) => {
    if (!canUseCrypto(context)) return;
    if (!await runSessionEffect(context, () => setCarrierStatus(
      event.cid,
      event.seq,
      event.carrier_status,
      event.carrier_error ?? null,
      event.carrier_updated_at ? event.carrier_updated_at * 1000 : Date.now(),
    ))) return;
    await refreshActiveMessages(event.cid, () => canUseCrypto(context));
  });
  socket.on("blocklist_updated", async () => {
    if (!canUseCrypto(context)) return;
    // Another device of the same account changed the shared block rules.
    const state = useStore.getState();
    await state.syncBlockRules().catch(() => undefined);
    if (!canUseCrypto(context)) return;
    await state.refreshBlocklist();
    if (!canUseCrypto(context)) return;
    await reapplyBlocklist(() => canUseCrypto(context));
  });
  socket.on("conv_updated", async () => {
    if (!canUseCrypto(context)) return;
    await useStore.getState().refreshConversations();
  });
  socket.on("contacts_updated", async () => {
    if (!canUseCrypto(context)) return;
    // Bulk contact syncs fan out one account-scoped invalidation event. Reload
    // the authoritative labels immediately on every connected browser.
    await useStore.getState().refreshConversations();
  });
  socket.on("device_pending", () => {
    if (!canUseCrypto(context)) return;
    // DeviceManager owns the approval UI; a DOM event avoids coupling that
    // account-security surface to the message Zustand state.
    if (typeof window !== "undefined") {
      window.dispatchEvent(new CustomEvent("securemsg:device-pending"));
    }
  });
  if (socket.connected && canUseCrypto(context)) await syncAll();
}

const syncJobs = new Map<string, Promise<void>>();

function queueConversationSync(cid: string, suppliedContext?: SecurityContext): Promise<void> {
  const context = suppliedContext ?? captureSecurityContext();
  const key = `${context.generation}:${cid}`;
  const previous = syncJobs.get(key) ?? Promise.resolve();
  const next = previous
    .catch(() => undefined)
    .then(() => {
      if (!canUseCrypto(context)) return;
      return useStore.getState().syncConversation(cid, context);
    })
    .finally(() => {
      if (syncJobs.get(key) === next) syncJobs.delete(key);
    });
  syncJobs.set(key, next);
  return next;
}

export async function verifyConversationKeyDirectory(
  result: ConversationMembersResult,
  shouldContinue: () => boolean = () => true,
): Promise<void> {
  if (!result.members || !result.directory_checkpoints || !result.directory_proofs || !result.recipient_keyset_hash) {
    throw new TrustViolationError("equivocation", "대화 키 디렉터리 checkpoint가 누락되었습니다.");
  }
  const calculatedKeyset = recipientKeysetHash(result.members.map((member) => ({
    user_id: member.user_id,
    sid: member.sid,
    pub_key: member.pub_key,
    sig_pub: member.sig_pub,
  })));
  if (calculatedKeyset !== result.recipient_keyset_hash) {
    throw new TrustViolationError("equivocation", "대화 수신 기기 keyset 해시가 일치하지 않습니다.");
  }

  const checkpointByUser = new Map(result.directory_checkpoints.map((checkpoint) => [checkpoint.user_id, checkpoint]));
  const proofByUser = new Map(result.directory_proofs.map((proof) => [proof.user_id, proof]));
  const membersByUser = new Map<number, typeof result.members>();
  for (const member of result.members) {
    const group = membersByUser.get(member.user_id) ?? [];
    group.push(member);
    membersByUser.set(member.user_id, group);
  }
  if (checkpointByUser.size !== result.directory_checkpoints.length
    || proofByUser.size !== result.directory_proofs.length) {
    throw new TrustViolationError("equivocation", "대화 참여자 디렉터리 checkpoint 수가 일치하지 않습니다.");
  }
  // Membership is an account property: a peer who revoked their last device is
  // still in the conversation and still gets a checkpoint and a proof, but
  // contributes no member row. Comparing the two counts made that peer lock
  // this client out of the thread until they registered again, so require that
  // every member is covered rather than that the sets are the same size.
  for (const userId of membersByUser.keys()) {
    if (!checkpointByUser.has(userId) || !proofByUser.has(userId)) {
      throw new TrustViolationError("equivocation", `사용자 ${userId}의 키 디렉터리가 누락되었습니다.`);
    }
  }
  const snapshots = [];
  for (const [userId, checkpoint] of checkpointByUser) {
    if (!shouldContinue()) return;
    const members = membersByUser.get(userId) ?? [];
    const proof = proofByUser.get(userId);
    if (!proof) {
      throw new TrustViolationError("equivocation", `사용자 ${userId}의 키 디렉터리가 누락되었습니다.`);
    }
    if (proof.security_mode !== "legacy_v1" && proof.security_mode !== "verified_v2") {
      throw new TrustViolationError("equivocation", `사용자 ${userId}의 proof 보안 모드가 누락되었습니다.`);
    }
    if (proof.identity_sig_pub !== checkpoint.identity_sig_pub
      || proof.security_epoch !== checkpoint.security_epoch
      || proof.directory_hash !== checkpoint.directory_hash
      || proof.security_mode !== checkpoint.security_mode) {
      throw new TrustViolationError("equivocation", `사용자 ${userId}의 checkpoint와 proof가 일치하지 않습니다.`);
    }
    verifyDirectoryProof(proof, members);
    if (!shouldContinue()) return;
    snapshots.push({
      uid: userId,
      identity_sig_pub: checkpoint.identity_sig_pub,
      security_epoch: checkpoint.security_epoch,
      directory_hash: checkpoint.directory_hash,
      security_mode: proof.security_mode,
      devices: members.map((member) => ({
        sid: member.sid,
        pub_key: member.pub_key,
        sig_pub: member.sig_pub,
        kind: member.kind,
        fingerprint: deviceFingerprint(member.pub_key, member.sig_pub).hash,
      })),
    });
  }
  if (!shouldContinue()) return;
  await pinTrustedDirectories(snapshots, shouldContinue);
  if (!shouldContinue()) return;
}

/**
 * Resolve a message sender key exclusively from the verified device history.
 * `sender_pub_key` is a relay-provided historical snapshot, not an authority:
 * it may help old clients, but it must exactly match the key bound to the
 * sender's user ID and SID by the signed directory proof.
 */
export function verifiedSenderPublicKey(
  result: ConversationMembersResult,
  senderUserId: number,
  senderSid: string,
  senderKeySnapshot?: string,
): string {
  const proofs = result.directory_proofs;
  if (!proofs) {
    throw new TrustViolationError("equivocation", "송신자 키 디렉터리 proof가 누락되었습니다.");
  }
  const proof = proofs.find((candidate) => candidate.user_id === senderUserId);
  const sender = proof?.device_history.find((candidate) => candidate.sid === senderSid);
  if (!sender) {
    throw new TrustViolationError("equivocation", "메시지 송신 기기가 검증된 키 이력에 없습니다.");
  }
  if (senderKeySnapshot && senderKeySnapshot !== sender.pub_key) {
    throw new TrustViolationError("device_key_changed", "메시지 송신 키가 검증된 기기 키와 일치하지 않습니다.");
  }
  return sender.pub_key;
}

/**
 * Public keys of this account's own devices, taken ONLY from the locally
 * pinned trust store. This is the resolver behind `EnvelopeKey.by` and behind
 * history sharing; both grant or use decryption authority, so a key that
 * merely arrived in a relay response must never reach either. An empty map
 * (nothing pinned yet) therefore means "refuse", not "ask the server".
 */
export async function ownDeviceWrapperKeys(context: SecurityContext): Promise<Map<string, string>> {
  if (context.uid == null) return new Map();
  const pinned = await listTrustedDevices(context.uid);
  return new Map(pinned.map((device) => [device.sid, device.pub_key]));
}

/**
 * Own devices that the newest verified directory still lists as active.
 *
 * `ownDeviceWrapperKeys` deliberately keeps revoked devices — history they
 * wrapped before revocation must stay readable — so it answers "did this
 * browser ever verify it", not "may it still be given access". Anything that
 * GRANTS decryption authority must use this narrower map instead, or a lost
 * device stays a valid recipient for as long as its pinned row exists.
 */
export async function ownActiveDeviceKeys(context: SecurityContext): Promise<Map<string, string>> {
  if (context.uid == null) return new Map();
  const pinned = await listTrustedDevices(context.uid);
  return new Map(
    pinned.filter((device) => device.revoked_at == null).map((device) => [device.sid, device.pub_key]),
  );
}

export function captureSecurityContext(): SecurityContext {
  const state = useStore.getState();
  return {
    generation: state.securityGeneration,
    uid: state.uid,
    sid: state.sid,
    keypair: state.keypair,
  };
}

/**
 * The session's socket, opened with the credential in force right now. A token
 * captured before a sliding renewal would be stale here, so it is read live;
 * getSocket keeps one socket per session, so a call after the slide refreshes
 * the handshake credential of the already-wired connection rather than
 * replacing it. Null means the session holds no token (a logout raced this
 * call) and no socket may be opened.
 */
function liveSocket(): ReturnType<typeof getSocket> | null {
  const token = api.token;
  return token ? getSocket(token) : null;
}

/**
 * Drop this browser's device and fall back to the sign-in screen.
 *
 * ``cleanup`` chooses how much local state goes with it. Forgetting the
 * account on the user's own instruction erases the pinned trust anchors too;
 * a device the RELAY reports revoked must not, because those pins are exactly
 * what would expose a relay that answers the next registration with
 * substituted keys (see clearDeviceForReregistration).
 */
async function resetLocalDevice(cleanup: () => Promise<void>): Promise<void> {
  const forgetGeneration = useStore.getState().securityGeneration + 1;
  disconnectSocket();
  api.setToken(null);
  useStore.setState({
    securityGeneration: forgetGeneration,
    ...clearedSessionState(),
    username: null, blockKeywords: [], blockedSenders: [], pendingNewDevice: null,
  });
  await sessionCoordinator.exclusive(cleanup);
  const state = useStore.getState();
  if (state.securityGeneration !== forgetGeneration || state.authed) return;
  useStore.setState({
    ...clearedSessionState(),
    username: null, blockKeywords: [], blockedSenders: [],
  });
}

/**
 * End a session the relay has stopped accepting, from either the REST 401 hook
 * or the socket handshake refusal. The generation check is what keeps the
 * notice off a session that signed in again while this logout was in flight:
 * only the lifetime this logout itself ended may be annotated.
 */
function logoutExpiredSession(context: SecurityContext): void {
  void useStore.getState().logout().then(() => {
    const state = useStore.getState();
    if (state.securityGeneration === context.generation + 1 && !state.authed) {
      useStore.setState({ error: SESSION_EXPIRED_ERROR });
    }
  });
}

/**
 * Every field that belongs to ONE authenticated session, at its signed-out
 * value. Logout, forgetting the local device and starting a fresh auth attempt
 * all spread this instead of restating the list, so a field added to State
 * cannot be cleared by four of the five paths and forgotten by the fifth.
 * Per-account data that deliberately outlives a session (block rules) and
 * one-shot UI gates stay explicit at the call sites that want them cleared.
 */
function clearedSessionState() {
  return {
    authed: false,
    approvalPending: false,
    securityLocked: false,
    uid: null,
    sid: null,
    deviceName: null,
    keypair: null,
    conversations: [],
    activeCid: null,
    activeMessages: [],
    convMeta: {},
    // Both describe a pending device's in-flight approval. Carrying them into
    // the next registration renders the QR with the NEW device's keys under
    // the OLD challenge, which the approver is told to read as an attack.
    pendingPairing: null,
    pendingChallenge: null,
    error: null,
  } satisfies Partial<State>;
}

function beginAuthAttempt(): number {
  disconnectSocket();
  api.setToken(null);
  const generation = useStore.getState().securityGeneration + 1;
  useStore.setState({
    securityGeneration: generation,
    ...clearedSessionState(),
  });
  return generation;
}

/**
 * Close an auth attempt by installing its session.
 *
 * Registration and existing-device login share this so the order around the
 * credential cannot drift apart in one of them: the device row is written
 * first — a concurrent forget-device cleanup may have dropped it while the
 * response was in flight, so this doubles as a reinstall — and only then is
 * the token handed to the API layer, with the generation re-checked on both
 * sides of the write so an attempt invalidated mid-install stays dead.
 */
async function installSession(
  attemptGeneration: number,
  meta: { username: string; uid: number; sid: string; deviceName: string; keypair: DeviceKeypair },
  token: string,
  approvalPending: boolean,
): Promise<boolean> {
  const installed = await sessionCoordinator.exclusive(async () => {
    if (useStore.getState().securityGeneration !== attemptGeneration) return false;
    await setMeta(meta);
    if (useStore.getState().securityGeneration !== attemptGeneration) return false;
    api.setToken(token);
    // Listed field by field rather than spread: `meta` is the persisted device
    // row, and a field added to it must not silently become store state.
    useStore.setState((state) => ({
      authed: true, approvalPending, securityLocked: false, error: null,
      username: meta.username, uid: meta.uid, sid: meta.sid,
      deviceName: meta.deviceName, keypair: meta.keypair,
      securityGeneration: state.securityGeneration + 1,
    }));
    return true;
  });
  if (!installed) return false;
  if (!approvalPending) await postLogin(captureSecurityContext());
  return true;
}

export function sameContext(context: SecurityContext): boolean {
  const state = useStore.getState();
  // Token value is deliberately not compared: it rotates under a live session
  // (sliding renewal), and every identity change bumps the generation.
  return state.securityGeneration === context.generation
    && state.uid === context.uid
    && state.sid === context.sid
    && state.keypair === context.keypair;
}

function contextsEqual(left: SecurityContext, right: SecurityContext): boolean {
  return left.generation === right.generation
    && left.uid === right.uid
    && left.sid === right.sid
    && left.keypair === right.keypair;
}

export function canUseCrypto(context: SecurityContext): boolean {
  const state = useStore.getState();
  // Presence of the credential is read live: a captured value cannot prove the
  // session still holds one, since logout clears the token without touching
  // any snapshot.
  return sameContext(context)
    && state.authed && !state.approvalPending && !state.securityLocked
    && Boolean(api.token && context.sid && context.keypair);
}

async function runSessionEffect(
  context: SecurityContext,
  effect: () => Promise<void>,
): Promise<boolean> {
  return await sessionCoordinator.exclusive(async () => {
    if (!canUseCrypto(context)) return false;
    await effect();
    return true;
  });
}

async function runContextEffect(
  context: SecurityContext,
  effect: () => Promise<void>,
): Promise<boolean> {
  return await sessionCoordinator.exclusive(async () => {
    if (!sameContext(context)) return false;
    await effect();
    return sameContext(context);
  });
}

export function lockForTrustViolation(error: unknown, context: SecurityContext): void {
  if (!sameContext(context)) return;
  const message = error instanceof Error ? error.message : "알 수 없는 키 디렉터리 오류";
  disconnectSocket();
  useStore.setState((state) => ({
    securityGeneration: state.securityGeneration + 1,
    securityLocked: true,
    error: `보안 경고: ${message} 메시지 암복호화를 중단했습니다.`,
  }));
}

export const __testing = {
  postLogin,
  queueConversationSync,
  lockForTrustViolation: (error: unknown) => lockForTrustViolation(error, captureSecurityContext()),
  resetSyncJobs: () => {
    syncJobs.clear();
    postLoginJobs.clear();
  },
};

/**
 * The two block-rule families differ only in their row type and their four db
 * functions. The sequence around them — optimistic local insert, refresh,
 * re-apply to already-stored messages, POST, swap the local row for the
 * server's — is identical, and every step of it is guarded by the same session
 * checks. Written out twice, a fix to that sequence (say, deleting the local
 * row only after the server row is written, so an interrupted swap cannot lose
 * the rule) lands on one family and silently not the other.
 */
interface BlockRuleKind<Row extends { id: string }> {
  type: BlockRuleType;
  addLocal: (value: string) => Promise<Row>;
  removeLocal: (id: string) => Promise<void>;
  listLocal: () => Promise<Row[]>;
  putRow: (row: Row) => Promise<void>;
  /** A server rule in this family's local row shape. */
  toRow: (rule: BlockRule) => Row;
  /** The value the relay stores and the other devices apply. */
  sharedValue: (row: Row) => string;
  /** Why the relay would refuse this value outright, or null. */
  rejectShared: (value: string) => string | null;
  removeError: string;
}

const KEYWORD_RULES: BlockRuleKind<BlockRow> = {
  type: "keyword",
  addLocal: addBlockKeyword,
  removeLocal: removeBlockKeyword,
  listLocal: listBlockKeywords,
  putRow: putBlockKeywordRow,
  toRow: ruleToKeywordRow,
  sharedValue: (row) => row.keyword,
  rejectShared: blockKeywordRejection,
  removeError: "차단 키워드를 삭제하지 못했습니다",
};

const SENDER_RULES: BlockRuleKind<SenderRow> = {
  type: "sender",
  addLocal: addBlockedSender,
  removeLocal: removeBlockedSender,
  listLocal: listBlockedSenders,
  putRow: putBlockedSenderRow,
  toRow: ruleToSenderRow,
  sharedValue: (row) => row.sender,
  rejectShared: blockedSenderRejection,
  removeError: "차단 번호를 삭제하지 못했습니다",
};

/**
 * Values the relay refused outright, keyed `type:value`. syncBlockRules
 * re-POSTs every local-only row on each reconnect, each blocklist_updated
 * event and each login; for a value the relay rejects on its own merits that
 * is one wasted request per pass for the lifetime of the row, and the user is
 * never told why the rule stays on this browser alone. Scoped to one auth
 * lifetime, since a rule set belongs to an account.
 */
let refusedRuleGeneration = -1;
const refusedRuleValues = new Set<string>();

function refusedBlockRule(context: SecurityContext, type: BlockRuleType, value: string): boolean {
  return refusedRuleGeneration === context.generation && refusedRuleValues.has(`${type}:${value}`);
}

/**
 * Classify a rejected upload. 400 is the relay refusing the value itself
 * (shape or length), which no retry can change; a 409 (account rule limit) or
 * a 429 clears on its own, so those are surfaced but stay retryable, and a
 * transport failure or 5xx is left silent for the offline case. The local row
 * is kept either way — it does filter this browser, it just never becomes a
 * shared rule.
 */
function noteBlockRuleRefusal(
  context: SecurityContext,
  type: BlockRuleType,
  value: string,
  result: { error?: string; status?: number },
): void {
  const status = result.status;
  if (status == null || status < 400 || status >= 500 || status === 429) return;
  if (status === 400) {
    if (refusedRuleGeneration !== context.generation) {
      refusedRuleGeneration = context.generation;
      refusedRuleValues.clear();
    }
    refusedRuleValues.add(`${type}:${value}`);
  }
  if (sameContext(context)) {
    useStore.setState({ error: result.error ?? "차단 규칙을 다른 기기와 공유하지 못했습니다" });
  }
}

/**
 * Reconcile one rule family against the relay: server rows are authoritative,
 * a local-only row survives until its own upload succeeds. Returns null when
 * the session moved on mid-loop, so the caller writes nothing.
 */
async function reconcileRuleKind<Row extends { id: string }>(
  kind: BlockRuleKind<Row>,
  serverRules: BlockRule[],
  context: SecurityContext,
): Promise<Row[] | null> {
  const authoritative = new Map<string, BlockRule>(
    serverRules.filter((rule) => rule.type === kind.type).map((rule) => [rule.value, rule]),
  );
  const failed: Row[] = [];
  for (const row of await kind.listLocal()) {
    if (!sameContext(context)) return null;
    if (row.id.startsWith("srv:")) continue;
    const value = kind.sharedValue(row);
    if (!refusedBlockRule(context, kind.type, value)) {
      const r = await api.addBlockRule(kind.type, value);
      if (!sameContext(context)) return null;
      if (r.ok && r.rule) {
        authoritative.set(r.rule.value, r.rule);
        continue;
      }
      noteBlockRuleRefusal(context, kind.type, value, r);
    }
    if (!authoritative.has(value)) failed.push(row);
  }
  return [...authoritative.values()].map(kind.toRow).concat(failed);
}

async function addSharedBlockRule<Row extends { id: string }>(
  kind: BlockRuleKind<Row>,
  value: string,
): Promise<void> {
  const context = captureSecurityContext();
  if (!sameContext(context)) return;
  // Refuse what the relay's own validator refuses (see blockKeywordRejection).
  // Creating the row anyway would leave a rule the sync re-POSTs and the relay
  // rejects on every pass, listed next to real account rules; an existing row
  // in that state is a different case and stays, handled by refusedRuleValues.
  const rejection = kind.rejectShared(value);
  if (rejection) {
    useStore.setState({ error: rejection });
    return;
  }
  // Apply locally first (instant UI), then share with the other devices.
  let inserted: Row | null = null;
  const added = await runContextEffect(context, async () => {
    inserted = await kind.addLocal(value);
  });
  const localRow = inserted as Row | null;
  if (!added || !localRow || !sameContext(context)) return;
  await useStore.getState().refreshBlocklist();
  if (!sameContext(context)) return;
  if (!await runContextEffect(context, () => reapplyBlocklist(() => sameContext(context)))) return;
  if (!sameContext(context)) return;
  const r = await api.addBlockRule(kind.type, kind.sharedValue(localRow));
  if (!sameContext(context)) return;
  if (r.ok && r.rule) {
    const rule = r.rule;
    if (!await runContextEffect(context, async () => {
      await kind.removeLocal(localRow.id);
      if (!sameContext(context)) return;
      await kind.putRow(kind.toRow(rule));
    })) return;
    if (!sameContext(context)) return;
    await useStore.getState().refreshBlocklist();
    return;
  }
  // Offline or transient: keep the local row, syncBlockRules pushes it later.
  noteBlockRuleRefusal(context, kind.type, kind.sharedValue(localRow), r);
}

async function removeSharedBlockRule<Row extends { id: string }>(
  kind: BlockRuleKind<Row>,
  id: string,
): Promise<void> {
  const context = captureSecurityContext();
  if (!sameContext(context)) return;
  if (id.startsWith("srv:")) {
    const r = await api.removeBlockRule(Number(id.slice(4)));
    if (!sameContext(context)) return;
    if (!r.ok) {
      useStore.setState({ error: r.error || kind.removeError });
      return;
    }
  }
  if (!await runContextEffect(context, () => kind.removeLocal(id))) return;
  if (!sameContext(context)) return;
  await useStore.getState().refreshBlocklist();
  if (!sameContext(context)) return;
  await runContextEffect(context, () => reapplyBlocklist(() => sameContext(context)));
}

/**
 * Re-render the open conversation from IndexedDB after a write. The pane is
 * reloaded, not patched, so the read has to be guarded on both sides: the user
 * can switch threads and the session can end while it is in flight, and either
 * would otherwise land one conversation's rows under another's header.
 */
async function refreshActiveMessages(cid: string, stillValid: () => boolean): Promise<void> {
  if (useStore.getState().activeCid !== cid) return;
  const rows = await listMessages(cid);
  if (stillValid() && useStore.getState().activeCid === cid) {
    useStore.setState({ activeMessages: rows });
  }
}

async function reapplyBlocklist(shouldContinue: () => boolean = () => true): Promise<void> {
  const keywords = await listBlockKeywords();
  if (!shouldContinue()) return;
  const senders = await listBlockedSenders();
  if (!shouldContinue()) return;
  const username = useStore.getState().username;
  const senderBlockByCid = new Map<string, boolean>();
  for (const conv of useStore.getState().conversations) {
    const phone = ownedSmsPhone(conv, username);
    if (phone && matchesBlockedSender(phone, senders)) senderBlockByCid.set(conv.cid, true);
  }
  const messages = await listAllMessages();
  if (!shouldContinue()) return;
  const gatewaySenders = new Set<string>();
  for (const uid of new Set(messages.map((message) => message.sender_id))) {
    const devices = await listTrustedDevices(uid);
    if (!shouldContinue()) return;
    for (const device of devices) {
      if (device.kind === "android_gateway") gatewaySenders.add(`${uid}:${device.sid}`);
    }
  }
  for (const message of messages) {
    const result = matchBlockKeywords(
      [message.subject, message.plaintext].filter(Boolean).join("\n"),
      keywords,
    );
    const blocked = result.blocked || (
      (senderBlockByCid.get(message.cid) ?? false)
      && gatewaySenders.has(`${message.sender_id}:${message.sender_sid}`)
    );
    if (Boolean(message.blocked) !== blocked) {
      if (!shouldContinue()) return;
      await setBlocked(message.cid, message.seq, blocked);
      if (!shouldContinue()) return;
    }
  }
  const cid = useStore.getState().activeCid;
  if (cid) await refreshActiveMessages(cid, shouldContinue);
}

/** Desktop notification for a freshly arrived incoming message. */
function maybeNotify(title: string, body: string): void {
  const state = useStore.getState();
  if (!state.notifyEnabled) return;
  if (typeof Notification === "undefined" || Notification.permission !== "granted") return;
  if (typeof document !== "undefined" && document.visibilityState === "visible"
      && document.hasFocus()) {
    return; // user is already looking at the app
  }
  try {
    const n = new Notification(title, { body, tag: `securemsg-${title}` });
    n.onclick = () => { window.focus(); n.close(); };
  } catch { /* notification construction can fail on some platforms */ }
}
