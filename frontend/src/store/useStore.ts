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
  getSocket,
  disconnectSocket,
  waitForSocketConnected,
  sendMessage,
  type ServerMessage,
  type ConvMember,
  type BlockRule,
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
  cacheDevice,
  cacheDevices,
  putMessage,
  listMessages,
  listAllMessages,
  setBlocked,
  setCarrierStatus,
  getCursor,
  setCursor,
  addBlockKeyword,
  removeBlockKeyword,
  listBlockKeywords,
  putBlockKeywordRow,
  addBlockedSender,
  removeBlockedSender,
  listBlockedSenders,
  putBlockedSenderRow,
  replaceBlockRules,
  type MessageRow,
  type BlockRow,
  type SenderRow,
  type MessageAttachment,
  pinTrustedDirectory,
  pinTrustedDirectories,
  listTrustedDevices,
  TrustViolationError,
} from "./db";
import { applyBlock, matchBlockKeywords } from "./blocklist";
import { normalizePhone, ownedSmsPhone } from "./conversationPolicy";
import { sessionCoordinator } from "./sessionCoordinator";
import {
  decodeRelayContent,
  conversationDisplayName,
  errorText,
  isSafeMimeType,
  matchesBlockedSender,
  ruleToKeywordRow,
  ruleToSenderRow,
} from "./helpers";

// Re-exported so existing tests/consumers keep importing from this module.
export { decodeRelayContent };

const NOTIFY_PREF_KEY = "securemsg-notify";

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
  attachments?: MessageAttachment[];
}

interface SecurityContext {
  generation: number;
  token: string | null;
  uid: number | null;
  sid: string | null;
  keypair: DeviceKeypair | null;
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
  /** Live QR pairing session + registration challenge while this device awaits approval. */
  pendingPairing: { pairingId: string; nonceApprover: string; expiresAt: number } | null;
  pendingChallenge: string | null;
  blockKeywords: BlockRow[];
  blockedSenders: SenderRow[];
  notifyEnabled: boolean;
  deviceCache: Map<string, ConvMember>; // sid -> member info
  error: string | null;

  init: () => Promise<void>;
  requestEmailRegistration: (username: string, email: string, password: string) => Promise<string | null>;
  verifyEmailRegistration: (username: string, email: string, password: string, challengeId: string, code: string) => Promise<boolean>;
  login: (username: string, password: string) => Promise<boolean>;
  addDevice: (username: string, password: string, deviceName: string) => Promise<boolean>;
  loginExistingDevice: (username: string, password: string) => Promise<boolean>;
  logout: () => Promise<void>;
  forgetLocalDevice: () => Promise<void>;
  refreshPendingApproval: () => Promise<"pending" | "approved" | "revoked" | "error">;
  refreshConversations: () => Promise<void>;
  newConversation: (members: string[]) => Promise<string | null>;
  newSmsConversation: (phone: string) => Promise<string | null>;
  selectConversation: (cid: string) => Promise<void>;
  syncConversation: (cid: string, context?: SecurityContext) => Promise<void>;
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
  pendingPairing: null,
  pendingChallenge: null,
  blockKeywords: [],
  blockedSenders: [],
  notifyEnabled: readNotifyPref(),
  deviceCache: new Map(),
  error: null,

  init: async () => {
    // Expired JWTs / revoked devices surface as REST 401s. Drop back to the
    // password screen instead of stranding the user in a broken authed state.
    api.onUnauthorized = () => {
      const context = captureSecurityContext();
      if (!canUseCrypto(context)) return;
      void useStore.getState().logout().then(() => {
        const state = useStore.getState();
        if (state.securityGeneration === context.generation + 1 && !state.authed) {
          useStore.setState({
            error: "로그인이 만료되었거나 이 기기가 폐기되었습니다. 다시 로그인하세요.",
          });
        }
      });
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
        const fallbackGeneration = get().securityGeneration;
        await sessionCoordinator.exclusive(async () => {
          if (get().securityGeneration !== fallbackGeneration) return;
          await clearDeviceForReregistration();
        });
        if (get().securityGeneration !== fallbackGeneration) return false;
        return await get().addDevice(
          username, password, "device-" + Math.random().toString(36).slice(2, 6),
        );
      }
      // No local device for this user → create a new one.
      return await get().addDevice(
        username, password, "device-" + Math.random().toString(36).slice(2, 6),
      );
    } catch (error) {
      set({ error: errorText(error) });
      return false;
    }
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
    const installed = await sessionCoordinator.exclusive(async () => {
      if (get().securityGeneration !== attemptGeneration) return false;
      await setMeta(meta);
      await cacheDevice({ sid: registeredSid, user_id: r.uid!, name: deviceName, pub_key: kp.box.pk });
      if (get().securityGeneration !== attemptGeneration) return false;
      api.setToken(r.token!);
      set((state) => ({
        authed: true, approvalPending, securityLocked: false, error: null, ...meta,
        securityGeneration: state.securityGeneration + 1,
      }));
      return true;
    });
    if (!installed) return false;
    if (!approvalPending) await postLogin(captureSecurityContext());
    return true;
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
    const installed = await sessionCoordinator.exclusive(async () => {
      if (get().securityGeneration !== attemptGeneration) return false;
      // A concurrent forget-device cleanup may have removed this row after we
      // read it but before the remote response arrived. Reinstall it atomically
      // with the authenticated in-memory session.
      await setMeta(meta);
      if (get().securityGeneration !== attemptGeneration) return false;
      api.setToken(r.token!);
      set((state) => ({
        authed: true, approvalPending, securityLocked: false, username: meta.username, uid: meta.uid, sid: meta.sid,
        deviceName: meta.deviceName, keypair: meta.keypair, error: null,
        securityGeneration: state.securityGeneration + 1,
      }));
      return true;
    });
    if (!installed) return false;
    if (!approvalPending) await postLogin(captureSecurityContext());
    return true;
  },

  refreshPendingApproval: async () => {
    const context = captureSecurityContext();
    if (!get().approvalPending || !api.token) return get().authed ? "approved" : "error";
    const result = await api.deviceApprovalStatus();
    if (!sameContext(context)) return "error";
    if (!result.ok) {
      if (result.status === 401 || result.status === 403) return "revoked";
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
      authed: false, approvalPending: false, securityLocked: false,
      uid: null, sid: null, deviceName: null, keypair: null, conversations: [],
      activeCid: null, activeMessages: [], deviceCache: new Map(), error: null,
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
      authed: false, approvalPending: false, securityLocked: false, username: localUsername ?? null, uid: null, sid: null,
      deviceName: null, keypair: null, conversations: [],
      activeCid: null, activeMessages: [], deviceCache: new Map(),
      error: cleanupFailed
        ? "로그아웃됐지만 브라우저의 로컬 캐시를 완전히 지우지 못했습니다. 브라우저 사이트 데이터를 삭제하세요."
        : null,
    });
  },

  forgetLocalDevice: async () => {
    const forgetGeneration = get().securityGeneration + 1;
    disconnectSocket();
    api.setToken(null);
    set({
      securityGeneration: forgetGeneration,
      authed: false, approvalPending: false, securityLocked: false, username: null, uid: null, sid: null,
      deviceName: null, keypair: null, conversations: [], activeCid: null, activeMessages: [],
      blockKeywords: [], deviceCache: new Map(), error: null,
    });
    await sessionCoordinator.exclusive(clearAllData);
    if (get().securityGeneration !== forgetGeneration || get().authed) return;
    set({
      authed: false, approvalPending: false, securityLocked: false, username: null, uid: null, sid: null,
      deviceName: null, keypair: null, conversations: [],
      activeCid: null, activeMessages: [], blockKeywords: [],
      deviceCache: new Map(), error: null,
    });
  },

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
    if (!username || !/^\+?[0-9*#]{3,24}$/.test(normalized)) {
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
    const rows = await listMessages(cid);
    if (!sameContext(context)) return;
    if (get().activeCid === cid) set({ activeMessages: rows });
    await queueConversationSync(cid, context);
  },

  syncConversation: async (cid, suppliedContext) => {
    const context = suppliedContext ?? captureSecurityContext();
    if (!canUseCrypto(context)) return;
    // 1. Fetch + cache member devices so we can decrypt (need sender pubkeys).
    const mr = await api.convMembers(cid);
    if (!canUseCrypto(context)) return;
    if (!mr.ok || !mr.members) {
      if (sameContext(context)) set({ error: mr.error || "대화 기기 목록을 불러오지 못했습니다" });
      return;
    }
    const members = mr.members;
    try {
      await verifyConversationKeyDirectory(mr, () => canUseCrypto(context));
      if (!canUseCrypto(context)) return;
    } catch (error) {
      lockForTrustViolation(error, context);
      return;
    }
    const memberMap = new Map<string, ConvMember>();
    for (const m of members) memberMap.set(m.sid, m);
    if (!canUseCrypto(context)) return;
    set({ deviceCache: memberMap });
    if (!canUseCrypto(context)) return;
    const cached = await runSessionEffect(context, () => cacheDevices(members.map((m) => ({
      sid: m.sid, user_id: m.user_id, pub_key: m.pub_key, sig_pub: m.sig_pub,
    }))));
    if (!cached) return;

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
    const gatewaySids = new Set(
      mr.members.filter((m) => m.kind === "android_gateway").map((m) => m.sid),
    );
    const pageSize = 200;
    let cursor = await getCursor(cid);
    if (!canUseCrypto(context)) return;
    const startCursor = cursor;
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
        );
        if (plaintext == null) continue;
        const content = decodeRelayContent(plaintext);
        let shouldShow = true;
        if (!await runSessionEffect(context, async () => {
          shouldShow = await applyBlock(
            cid,
            sm.seq,
            [content.subject, content.text].filter(Boolean).join("\n"),
          );
        })) return;
        if (shouldShow && senderBlocked && gatewaySids.has(sm.sender_sid)) {
          if (!await runSessionEffect(context, () => setBlocked(cid, sm.seq, true))) return;
          shouldShow = false;
        }
        if (shouldShow && sm.seq > startCursor && sm.sender_sid !== mySid) {
          notifyBody = content.text || content.subject || "(첨부파일)";
          notifyIsIncoming = true;
        }
        if (!canUseCrypto(context)) return;
        const wrote = await runSessionEffect(context, () => putMessage({
          id: "", seq: sm.seq, cid, sender_id: sm.sender_id,
          sender_sid: sm.sender_sid, plaintext: content.text, created_at: sm.created_at * 1000,
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
      const socket = getSocket(context.token!);
      if (socket?.connected) {
        if (!canUseCrypto(context)) return;
        socket.emit("message_delivered", { cid, seq: maxSeq });
      }
      if (fr.messages.length < pageSize || maxSeq <= cursor) break;
      cursor = maxSeq;
    }
    // Re-read state: the `me` snapshot predates the pagination loop, and the
    // user may have switched conversations while pages were being pulled.
    if (canUseCrypto(context) && useStore.getState().activeCid === cid) {
      const messages = await listMessages(cid);
      if (canUseCrypto(context) && useStore.getState().activeCid === cid) set({ activeMessages: messages });
    }
    if (canUseCrypto(context) && notifyIsIncoming && notifyBody != null) {
      maybeNotify(conversationDisplayName(conv, "새 메시지"), notifyBody, gatewaySids.size > 0);
    }
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
      if (content.text.length > 20_000) {
        set({ error: "메시지는 20,000자까지 보낼 수 있습니다" });
        return false;
      }
      if ((content.subject?.length ?? 0) > 120) {
        set({ error: "MMS 제목은 120자까지 입력할 수 있습니다" });
        return false;
      }
      if ((content.attachments?.length ?? 0) > 8) {
        set({ error: "첨부파일은 최대 8개까지 가능합니다" });
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
      if (attachmentBytes > 512 * 1024) {
        set({ error: "첨부파일 전체 크기는 512KB까지 가능합니다" });
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
        attachments: content.attachments ?? [],
      });
      const socket = getSocket(context.token!);
      if (!await waitForSocketConnected(socket)) {
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
        blocked: false, content_type: content.type, subject: content.subject ?? null,
        attachments: content.attachments ?? [],
        carrier_status: isSms ? "queued" : "none",
        carrier_error: null,
        carrier_updated_at: null,
      }));
      if (!wrote) return false;
      if (!canUseCrypto(context)) return false;
      set({ error: null });
      if (useStore.getState().activeCid === cid) {
        const messages = await listMessages(cid);
        if (canUseCrypto(context) && useStore.getState().activeCid === cid) set({ activeMessages: messages });
      }
      return true;
    } catch (error) {
      if (sameContext(context)) set({ error: errorText(error) });
      return false;
    }
  },

  addBlock: async (kw) => {
    const context = captureSecurityContext();
    if (!sameContext(context)) return;
    // Apply locally first (instant UI), then share with the other devices.
    let row: BlockRow | null = null;
    const added = await runContextEffect(context, async () => {
      row = await addBlockKeyword(kw);
    });
    if (!added || !row || !sameContext(context)) return;
    const addedRow = row as BlockRow;
    await get().refreshBlocklist();
    if (!sameContext(context)) return;
    if (!await runContextEffect(context, () => reapplyBlocklist(() => sameContext(context)))) return;
    if (!sameContext(context)) return;
    const r = await api.addBlockRule("keyword", addedRow.keyword);
    if (!sameContext(context)) return;
    if (r.ok && r.rule) {
      if (!await runContextEffect(context, async () => {
        await removeBlockKeyword(addedRow.id);
        if (!sameContext(context)) return;
        await putBlockKeywordRow(ruleToKeywordRow(r.rule!));
      })) return;
      if (!sameContext(context)) return;
      await get().refreshBlocklist();
    } // offline/failed: keep the local row; syncBlockRules pushes it later
  },

  removeBlock: async (id) => {
    const context = captureSecurityContext();
    if (!sameContext(context)) return;
    if (id.startsWith("srv:")) {
      const r = await api.removeBlockRule(Number(id.slice(4)));
      if (!sameContext(context)) return;
      if (!r.ok) {
        set({ error: r.error || "차단 키워드를 삭제하지 못했습니다" });
        return;
      }
    }
    if (!await runContextEffect(context, () => removeBlockKeyword(id))) return;
    if (!sameContext(context)) return;
    await get().refreshBlocklist();
    if (!sameContext(context)) return;
    await runContextEffect(context, () => reapplyBlocklist(() => sameContext(context)));
  },

  addBlockedSenderRule: async (sender) => {
    const context = captureSecurityContext();
    if (!sameContext(context)) return;
    let row: SenderRow | null = null;
    const added = await runContextEffect(context, async () => {
      row = await addBlockedSender(sender);
    });
    if (!added || !row || !sameContext(context)) return;
    const addedRow = row as SenderRow;
    await get().refreshBlocklist();
    if (!sameContext(context)) return;
    if (!await runContextEffect(context, () => reapplyBlocklist(() => sameContext(context)))) return;
    if (!sameContext(context)) return;
    const r = await api.addBlockRule("sender", addedRow.sender);
    if (!sameContext(context)) return;
    if (r.ok && r.rule) {
      if (!await runContextEffect(context, async () => {
        await removeBlockedSender(addedRow.id);
        if (!sameContext(context)) return;
        await putBlockedSenderRow(ruleToSenderRow(r.rule!));
      })) return;
      if (!sameContext(context)) return;
      await get().refreshBlocklist();
    }
  },

  removeBlockedSenderRule: async (id) => {
    const context = captureSecurityContext();
    if (!sameContext(context)) return;
    if (id.startsWith("srv:")) {
      const r = await api.removeBlockRule(Number(id.slice(4)));
      if (!sameContext(context)) return;
      if (!r.ok) {
        set({ error: r.error || "차단 번호를 삭제하지 못했습니다" });
        return;
      }
    }
    if (!await runContextEffect(context, () => removeBlockedSender(id))) return;
    if (!sameContext(context)) return;
    await get().refreshBlocklist();
    if (!sameContext(context)) return;
    await runContextEffect(context, () => reapplyBlocklist(() => sameContext(context)));
  },

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
    const serverKeywords = new Map<string, BlockRule>(
      list.rules.filter((r) => r.type === "keyword").map((r) => [r.value, r]),
    );
    const serverSenders = new Map<string, BlockRule>(
      list.rules.filter((r) => r.type === "sender").map((r) => [r.value, r]),
    );
    const failedKeywords: BlockRow[] = [];
    for (const row of await listBlockKeywords()) {
      if (!sameContext(context)) return;
      if (row.id.startsWith("srv:")) continue;
      const r = await api.addBlockRule("keyword", row.keyword);
      if (!sameContext(context)) return;
      if (r.ok && r.rule) serverKeywords.set(r.rule.value, r.rule);
      else if (!serverKeywords.has(row.keyword)) failedKeywords.push(row);
    }
    const failedSenders: SenderRow[] = [];
    for (const row of await listBlockedSenders()) {
      if (!sameContext(context)) return;
      if (row.id.startsWith("srv:")) continue;
      const r = await api.addBlockRule("sender", row.sender);
      if (!sameContext(context)) return;
      if (r.ok && r.rule) serverSenders.set(r.rule.value, r.rule);
      else if (!serverSenders.has(row.sender)) failedSenders.push(row);
    }
    if (!sameContext(context)) return;
    await replaceBlockRules(
      [...serverKeywords.values()].map(ruleToKeywordRow).concat(failedKeywords),
      [...serverSenders.values()].map(ruleToSenderRow).concat(failedSenders),
    );
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
      if (!directory.identity_sig_pub || !directory.directory_hash
        || !Number.isSafeInteger(directory.security_epoch) || !directory.devices
        || !directory.device_history || !directory.approval_certificates
        || !directory.revocation_certificates || !directory.security_upgrade_certificates
        || (directory.security_mode !== "legacy_v1" && directory.security_mode !== "verified_v2")) {
        throw new Error("서버 키 디렉터리 응답이 불완전합니다.");
      }
      verifyDirectoryProof({
        user_id: me.uid,
        identity_sig_pub: directory.identity_sig_pub,
        security_epoch: directory.security_epoch!,
        directory_hash: directory.directory_hash,
        trust_enforced_at: directory.trust_enforced_at,
        security_mode: directory.security_mode,
        device_history: directory.device_history,
        approval_certificates: directory.approval_certificates,
        revocation_certificates: directory.revocation_certificates,
        security_upgrade_certificates: directory.security_upgrade_certificates,
      }, directory.devices);
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
  await me.refreshBlocklist();
  if (!canUseCrypto(context)) return;
  // Pull shared block rules from the server (and push any local-only ones).
  await me.syncBlockRules().catch(() => undefined);
  if (!canUseCrypto(context)) return;
  await me.refreshBlocklist();
  if (!canUseCrypto(context)) return;
  await me.refreshConversations();
  if (!canUseCrypto(context)) return;
  // Wire socket.
  const socket = getSocket(context.token!);
  if (!canUseCrypto(context)) return;
  socket.off("connect");
  socket.off("connect_error");
  socket.off("message_new");
  socket.off("message_status");
  socket.off("typing");
  socket.off("blocklist_updated");
  socket.off("conv_updated");
  socket.off("contacts_updated");
  socket.off("device_pending");
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
      void useStore.getState().logout().then(() => {
        if (useStore.getState().securityGeneration === context.generation + 1 && !useStore.getState().authed) {
          useStore.setState({ error: "로그인이 만료되었거나 이 기기가 폐기되었습니다. 다시 로그인하세요." });
        }
      });
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
    if (useStore.getState().activeCid === event.cid) {
      const messages = await listMessages(event.cid);
      if (canUseCrypto(context) && useStore.getState().activeCid === event.cid) {
        useStore.setState({ activeMessages: messages });
      }
    }
  });
  socket.on("typing", (_data: { cid: string; user_id: number; is_typing: boolean }) => {
    // Surface typing state via a separate lightweight subscription.
    // no-op; UI can subscribe via socket directly if needed.
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
    || proofByUser.size !== result.directory_proofs.length
    || checkpointByUser.size !== membersByUser.size
    || proofByUser.size !== membersByUser.size) {
    throw new TrustViolationError("equivocation", "대화 참여자 디렉터리 checkpoint 수가 일치하지 않습니다.");
  }
  const snapshots = [];
  for (const [userId, members] of membersByUser) {
    if (!shouldContinue()) return;
    const checkpoint = checkpointByUser.get(userId);
    const proof = proofByUser.get(userId);
    if (!checkpoint || !proof) {
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

function captureSecurityContext(): SecurityContext {
  const state = useStore.getState();
  return {
    generation: state.securityGeneration,
    token: api.token,
    uid: state.uid,
    sid: state.sid,
    keypair: state.keypair,
  };
}

function beginAuthAttempt(): number {
  disconnectSocket();
  api.setToken(null);
  const generation = useStore.getState().securityGeneration + 1;
  useStore.setState({
    securityGeneration: generation,
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
    deviceCache: new Map(),
    error: null,
  });
  return generation;
}

function sameContext(context: SecurityContext): boolean {
  const state = useStore.getState();
  return state.securityGeneration === context.generation
    && api.token === context.token
    && state.uid === context.uid
    && state.sid === context.sid
    && state.keypair === context.keypair;
}

function contextsEqual(left: SecurityContext, right: SecurityContext): boolean {
  return left.generation === right.generation
    && left.token === right.token
    && left.uid === right.uid
    && left.sid === right.sid
    && left.keypair === right.keypair;
}

function canUseCrypto(context: SecurityContext): boolean {
  const state = useStore.getState();
  return sameContext(context)
    && state.authed && !state.approvalPending && !state.securityLocked
    && Boolean(context.token && context.sid && context.keypair);
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

function lockForTrustViolation(error: unknown, context: SecurityContext): void {
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
  if (cid) {
    const messagesForActiveConversation = await listMessages(cid);
    if (shouldContinue() && useStore.getState().activeCid === cid) {
      useStore.setState({ activeMessages: messagesForActiveConversation });
    }
  }
}

/** Compare phone numbers by digits only (handles +82 vs 0082 vs separators). */
/** Desktop notification for a freshly arrived incoming message. */
function maybeNotify(title: string, body: string, _isSms: boolean): void {
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
