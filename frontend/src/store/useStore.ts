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
  type DeviceKeypair,
  type Envelope,
  type RecipientDevice,
} from "../crypto/keys";
import { deviceFingerprint, recipientKeysetHash, verifyDirectoryProof } from "../crypto/deviceTrust";
import {
  setMeta,
  getMeta,
  clearSessionData,
  clearAllData,
  cacheDevice,
  getDevice,
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
  clearBlockKeywords,
  addBlockedSender,
  removeBlockedSender,
  listBlockedSenders,
  putBlockedSenderRow,
  clearBlockedSenders,
  type MessageRow,
  type BlockRow,
  type SenderRow,
  type MessageAttachment,
  pinTrustedDirectory,
  TrustViolationError,
} from "./db";
import { applyBlock, matchBlockKeywords } from "./blocklist";
import { normalizePhone, ownedSmsPhone } from "./conversationPolicy";
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

interface State {
  ready: boolean;
  authed: boolean;
  approvalPending: boolean;
  securityLocked: boolean;
  username: string | null;
  uid: number | null;
  sid: string | null;
  deviceName: string | null;
  keypair: DeviceKeypair | null;
  conversations: Conversation[];
  activeCid: string | null;
  activeMessages: MessageRow[];
  blockKeywords: BlockRow[];
  blockedSenders: SenderRow[];
  notifyEnabled: boolean;
  deviceCache: Map<string, ConvMember>; // sid -> member info
  error: string | null;

  init: () => Promise<void>;
  register: (username: string, password: string) => Promise<boolean>;
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
  syncConversation: (cid: string) => Promise<void>;
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
  username: null,
  uid: null,
  sid: null,
  deviceName: null,
  keypair: null,
  conversations: [],
  activeCid: null,
  activeMessages: [],
  blockKeywords: [],
  blockedSenders: [],
  notifyEnabled: readNotifyPref(),
  deviceCache: new Map(),
  error: null,

  init: async () => {
    // Expired JWTs / revoked devices surface as REST 401s. Drop back to the
    // password screen instead of stranding the user in a broken authed state.
    api.onUnauthorized = () => {
      if (!api.token) return;
      void useStore.getState().logout().then(() => {
        useStore.setState({
          error: "로그인이 만료되었거나 이 기기가 폐기되었습니다. 다시 로그인하세요.",
        });
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

  register: async (username, password) => {
    try {
      if (!/^[a-z0-9_]{3,20}$/.test(username)) {
        set({ error: "아이디는 영소문자·숫자·_ 3~20자로 입력하세요" });
        return false;
      }
      if (password.length < 8 || password.length > 1024) {
        set({
          error:
            "비밀번호는 영문·숫자·특수문자 조합 제한 없이 8~1,024자로 입력하세요",
        });
        return false;
      }
      const existingMeta = await getMeta();
      if (existingMeta) {
        set({
          error: `이 브라우저에는 ${existingMeta.username} 기기 키가 남아 있습니다. 새 계정을 만들기 전에 로컬 기기를 초기화하세요.`,
        });
        return false;
      }
      const salt = saltForUser(username);
      const pwHash = await hashPassword(password, salt);
      const r = await api.register(username, pwHash);
      if (!r.ok) { set({ error: r.error || "register failed" }); return false; }
      // After register, immediately register first device.
      return await get().addDevice(username, password, "first-device");
    } catch (error) {
      set({ error: errorText(error) });
      return false;
    }
  },

  login: async (username, password) => {
    try {
      if (!/^[a-z0-9_]{3,20}$/.test(username) || password.length < 1 || password.length > 1024) {
        set({ error: "아이디 또는 비밀번호 형식을 확인하세요" });
        return false;
      }
      const salt = saltForUser(username);
      const pwHash = await hashPassword(password, salt);
      const r = await api.login(username, pwHash);
      if (!r.ok) { set({ error: r.error || "login failed" }); return false; }
      // Decide: existing device or new device?
      const meta = await getMeta();
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
        // discard keys on transient network errors.
        if (!/^(device not found|device revoked)$/.test(get().error ?? "")) return false;
        await clearAllData();
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
    const salt = saltForUser(username);
    const pwHash = await hashPassword(password, salt);
    const kp = generateKeypair();
    const r = await api.deviceRegister(username, pwHash, deviceName, kp.box.pk, kp.sign.pk);
    if (!r.ok || !r.token || !r.sid) {
      set({ error: r.error || "device register failed" });
      return false;
    }
    api.setToken(r.token);
    const meta = { username, uid: r.uid!, sid: r.sid, deviceName, keypair: kp };
    await setMeta(meta);
    await cacheDevice({ sid: r.sid, user_id: r.uid!, name: deviceName, pub_key: kp.box.pk });
    const approvalPending = r.trust_state === "pending";
    set({ authed: true, approvalPending, securityLocked: false, error: null, ...meta });
    if (!approvalPending) await postLogin();
    return true;
  },

  loginExistingDevice: async (username, password) => {
    const meta = await getMeta();
    if (!meta || meta.username !== username) { set({ error: "no local device" }); return false; }
    const salt = saltForUser(username);
    const pwHash = await hashPassword(password, salt);
    const r = await api.deviceLogin(username, pwHash, meta.sid);
    if (!r.ok || !r.token) { set({ error: r.error || "device login failed" }); return false; }
    api.setToken(r.token);
    const approvalPending = r.trust_state === "pending";
    set({ authed: true, approvalPending, securityLocked: false, username: meta.username, uid: meta.uid, sid: meta.sid,
           deviceName: meta.deviceName, keypair: meta.keypair, error: null });
    if (!approvalPending) await postLogin();
    return true;
  },

  refreshPendingApproval: async () => {
    if (!get().approvalPending || !api.token) return get().authed ? "approved" : "error";
    const result = await api.deviceApprovalStatus();
    if (!result.ok) {
      if (result.status === 401 || result.status === 403) return "revoked";
      set({ error: result.error ?? "기기 승인 상태를 확인하지 못했습니다." });
      return "error";
    }
    if (result.trust_state === "approved") {
      set({ approvalPending: false, error: null });
      await postLogin();
      return "approved";
    }
    if (result.trust_state === "revoked" || result.trust_state === "rejected") return "revoked";
    return "pending";
  },

  logout: async () => {
    const rememberedUsername = get().username;
    try {
      // Best effort: revoke the bearer token while it is still available.
      // A rejected/expired token or offline server must never trap the user in
      // a local authenticated state.
      if (api.token) await api.logout();
    } catch {
      // The normal API path returns a structured failure, but also tolerate an
      // unexpected transport/runtime exception and complete local logout.
    }

    disconnectSocket();
    api.setToken(null);

    // Authentication state must disappear even if IndexedDB is unavailable or
    // corrupt. Read/clear it best-effort, then unconditionally remove every
    // in-memory key and plaintext conversation from the rendered UI.
    let localUsername = rememberedUsername;
    let cleanupFailed = false;
    try {
      localUsername = (await getMeta())?.username ?? localUsername;
    } catch {
      cleanupFailed = true;
    }
    try {
      await clearSessionData();
    } catch {
      cleanupFailed = true;
    }
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
    disconnectSocket();
    api.setToken(null);
    await clearAllData();
    set({
      authed: false, approvalPending: false, securityLocked: false, username: null, uid: null, sid: null,
      deviceName: null, keypair: null, conversations: [],
      activeCid: null, activeMessages: [], blockKeywords: [],
      deviceCache: new Map(), error: null,
    });
  },

  refreshConversations: async () => {
    const r = await api.listConversations();
    if (r.ok && r.conversations) {
      set({ conversations: r.conversations, error: null });
    } else {
      set({ error: r.error || "대화 목록을 불러오지 못했습니다" });
    }
  },

  newConversation: async (members) => {
    const r = await api.createConversation(members);
    if (!r.ok || !r.cid) { set({ error: r.error || "create failed" }); return null; }
    await get().refreshConversations();
    return r.cid as string;
  },

  newSmsConversation: async (phone) => {
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
    if (!r.ok || !r.cid) {
      set({ error: r.error || "SMS 대화 생성 실패" });
      return null;
    }
    await get().refreshConversations();
    return r.cid as string;
  },

  selectConversation: async (cid) => {
    // Split the update: awaiting listMessages() inside set() let a fast
    // A→B→A click sequence land one conversation's messages under another's
    // header. Guard the async result against the still-active conversation.
    set({ activeCid: cid, activeMessages: [] });
    const rows = await listMessages(cid);
    if (get().activeCid === cid) set({ activeMessages: rows });
    await queueConversationSync(cid);
  },

  syncConversation: async (cid) => {
    // 1. Fetch + cache member devices so we can decrypt (need sender pubkeys).
    const mr = await api.convMembers(cid);
    if (!mr.ok || !mr.members) {
      set({ error: mr.error || "대화 기기 목록을 불러오지 못했습니다" });
      return;
    }
    try {
      await verifyConversationKeyDirectory(mr);
    } catch (error) {
      lockForTrustViolation(error);
      return;
    }
    const memberMap = new Map<string, ConvMember>();
    for (const m of mr.members) memberMap.set(m.sid, m);
    set({ deviceCache: memberMap });
    await cacheDevices(mr.members.map((m) => ({
      sid: m.sid, user_id: m.user_id, name: m.name, pub_key: m.pub_key, sig_pub: m.sig_pub,
    })));

    // 2. Pull every page since our last cursor. Advance even past an envelope
    // this device cannot decrypt, so one malformed row cannot starve all newer
    // history forever.
    const me = useStore.getState();
    if (!me.sid || !me.keypair) return;
    const mySid = me.sid;
    const myKeypair = me.keypair;
    // Sender blocking: an SMS thread's carrier messages arrive via the Android
    // gateway device. If the thread's phone number is blocked, hide them.
    const conv = useStore.getState().conversations.find((c) => c.cid === cid);
    const smsPhone = conv ? ownedSmsPhone(conv, useStore.getState().username) : null;
    const senderBlocked = smsPhone != null
      && matchesBlockedSender(smsPhone, await listBlockedSenders());
    const gatewaySids = new Set(
      mr.members.filter((m) => m.kind === "android_gateway").map((m) => m.sid),
    );
    const pageSize = 200;
    let cursor = await getCursor(cid);
    const startCursor = cursor;
    let notifyBody: string | null = null;
    let notifyIsIncoming = false;
    while (true) {
      // Abort if the user logged out or switched accounts mid-sync; otherwise
      // decrypted rows would be written back after clearSessionData().
      const current = useStore.getState();
      if (!current.authed || current.sid !== mySid || current.keypair !== myKeypair) return;
      const fr = await api.fetchMessages(cid, cursor, pageSize);
      if (!fr.ok || !fr.messages) {
        set({ error: fr.error || "메시지 동기화 실패" });
        return;
      }
      if (fr.messages.length === 0) break;

      let maxSeq = cursor;
      for (const sm of fr.messages) {
        maxSeq = Math.max(maxSeq, sm.seq);
        if (!useStore.getState().authed) return;
        const senderDev = sm.sender_pub_key ? null : await getDevice(sm.sender_sid);
        const senderPubKey = sm.sender_pub_key || senderDev?.pub_key;
        if (!senderPubKey) continue;
        const plaintext = decryptMessageWithSender(
          sm.payload, mySid, myKeypair, senderPubKey,
        );
        if (plaintext == null) continue;
        const content = decodeRelayContent(plaintext);
        let shouldShow = await applyBlock(
          cid,
          sm.seq,
          [content.subject, content.text].filter(Boolean).join("\n"),
        );
        if (shouldShow && senderBlocked && gatewaySids.has(sm.sender_sid)) {
          await setBlocked(cid, sm.seq, true);
          shouldShow = false;
        }
        if (shouldShow && sm.seq > startCursor && sm.sender_sid !== mySid) {
          notifyBody = content.text || content.subject || "(첨부파일)";
          notifyIsIncoming = true;
        }
        await putMessage({
          id: "", seq: sm.seq, cid, sender_id: sm.sender_id,
          sender_sid: sm.sender_sid, plaintext: content.text, created_at: sm.created_at * 1000,
          blocked: !shouldShow, content_type: content.type, subject: content.subject ?? null,
          attachments: content.attachments, carrier_status: sm.carrier_status ?? "none",
          carrier_error: sm.carrier_error,
          carrier_updated_at: sm.carrier_updated_at ? sm.carrier_updated_at * 1000 : null,
        });
      }
      if (!useStore.getState().authed) return;
      await setCursor(cid, maxSeq);
      const socket = api.token ? getSocket(api.token) : null;
      if (socket?.connected) {
        socket.emit("message_delivered", { cid, seq: maxSeq });
      }
      if (fr.messages.length < pageSize || maxSeq <= cursor) break;
      cursor = maxSeq;
    }
    // Re-read state: the `me` snapshot predates the pagination loop, and the
    // user may have switched conversations while pages were being pulled.
    if (useStore.getState().activeCid === cid) {
      set({ activeMessages: await listMessages(cid) });
    }
    if (notifyIsIncoming && notifyBody != null) {
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
    try {
      const me = useStore.getState();
      if (!me.keypair || !me.sid || !api.token) return false;
      if (content.type !== "text" && content.type !== "mms") {
        set({ error: "지원하지 않는 메시지 형식입니다" });
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
      const socket = getSocket(api.token);
      if (!await waitForSocketConnected(socket)) {
        set({ error: "실시간 서버에 연결할 수 없습니다" });
        return false;
      }
      // Recipient list: every device of every conversation member.
      const mr = await api.convMembers(cid);
      if (!mr.ok || !mr.members) {
        set({ error: mr.error || "members fetch failed" });
        return false;
      }
      try {
        await verifyConversationKeyDirectory(mr);
      } catch (error) {
        lockForTrustViolation(error);
        return false;
      }
      const recipients: RecipientDevice[] = mr.members.map((m) => ({ sid: m.sid, pub_key: m.pub_key }));
      if (recipients.length === 0) {
        set({ error: "암호화할 수신 기기가 없습니다" });
        return false;
      }
      const envelope: Envelope = await encryptMessage(contentJson, recipients, me.keypair);
      const ack = await sendMessage(socket, cid, envelope);
      if (!ack.ok || !ack.seq) { set({ error: ack.error || "send failed" }); return false; }
      if (!useStore.getState().authed) return false;
      // Optimistic local insert. The ordered REST sync advances the cursor.
      const conversation = useStore.getState().conversations.find((item) => item.cid === cid);
      const isSms = conversation ? ownedSmsPhone(conversation, me.username) !== null : false;
      await putMessage({
        id: "", seq: ack.seq, cid, sender_id: me.uid!,
        sender_sid: me.sid, plaintext: content.text, created_at: Date.now(),
        blocked: false, content_type: content.type, subject: content.subject ?? null,
        attachments: content.attachments ?? [],
        carrier_status: isSms ? "queued" : "none",
        carrier_error: null,
        carrier_updated_at: null,
      });
      set({ error: null });
      if (useStore.getState().activeCid === cid) {
        set({ activeMessages: await listMessages(cid) });
      }
      return true;
    } catch (error) {
      set({ error: errorText(error) });
      return false;
    }
  },

  addBlock: async (kw) => {
    // Apply locally first (instant UI), then share with the other devices.
    const row = await addBlockKeyword(kw);
    await get().refreshBlocklist();
    await reapplyBlocklist();
    const r = await api.addBlockRule("keyword", row.keyword);
    if (r.ok && r.rule) {
      await removeBlockKeyword(row.id);
      await putBlockKeywordRow(ruleToKeywordRow(r.rule));
      await get().refreshBlocklist();
    } // offline/failed: keep the local row; syncBlockRules pushes it later
  },

  removeBlock: async (id) => {
    if (id.startsWith("srv:")) {
      const r = await api.removeBlockRule(Number(id.slice(4)));
      if (!r.ok) {
        set({ error: r.error || "차단 키워드를 삭제하지 못했습니다" });
        return;
      }
    }
    await removeBlockKeyword(id);
    await get().refreshBlocklist();
    await reapplyBlocklist();
  },

  addBlockedSenderRule: async (sender) => {
    const row = await addBlockedSender(sender);
    await get().refreshBlocklist();
    await reapplyBlocklist();
    const r = await api.addBlockRule("sender", row.sender);
    if (r.ok && r.rule) {
      await removeBlockedSender(row.id);
      await putBlockedSenderRow(ruleToSenderRow(r.rule));
      await get().refreshBlocklist();
    }
  },

  removeBlockedSenderRule: async (id) => {
    if (id.startsWith("srv:")) {
      const r = await api.removeBlockRule(Number(id.slice(4)));
      if (!r.ok) {
        set({ error: r.error || "차단 번호를 삭제하지 못했습니다" });
        return;
      }
    }
    await removeBlockedSender(id);
    await get().refreshBlocklist();
    await reapplyBlocklist();
  },

  refreshBlocklist: async () => {
    set({
      blockKeywords: await listBlockKeywords(),
      blockedSenders: await listBlockedSenders(),
    });
  },

  /** Reconcile local block rules with the server (server wins). Pushes any
   * local-only rules first, then replaces local copies with the server set. */
  syncBlockRules: async () => {
    if (!api.token) return;
    const list = await api.listBlockRules();
    if (!list.ok || !list.rules) return; // offline: keep local rules as-is
    const serverKeywords = new Map<string, BlockRule>(
      list.rules.filter((r) => r.type === "keyword").map((r) => [r.value, r]),
    );
    const serverSenders = new Map<string, BlockRule>(
      list.rules.filter((r) => r.type === "sender").map((r) => [r.value, r]),
    );
    for (const row of await listBlockKeywords()) {
      if (row.id.startsWith("srv:")) continue;
      const r = await api.addBlockRule("keyword", row.keyword);
      if (r.ok && r.rule) serverKeywords.set(r.rule.value, r.rule);
    }
    for (const row of await listBlockedSenders()) {
      if (row.id.startsWith("srv:")) continue;
      const r = await api.addBlockRule("sender", row.sender);
      if (r.ok && r.rule) serverSenders.set(r.rule.value, r.rule);
    }
    await clearBlockKeywords();
    for (const rule of serverKeywords.values()) {
      await putBlockKeywordRow(ruleToKeywordRow(rule));
    }
    await clearBlockedSenders();
    for (const rule of serverSenders.values()) {
      await putBlockedSenderRow(ruleToSenderRow(rule));
    }
    await get().refreshBlocklist();
  },

  renameConversation: async (cid, name) => {
    const r = await api.renameConversation(cid, name);
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

async function postLogin(): Promise<void> {
  const me = useStore.getState();
  // Verify the account key directory before fetching any encrypted history.
  // A known identity/key rollback is fail-closed for the entire message UI,
  // not merely a warning shown when the user happens to open DeviceManager.
  if (me.uid != null) {
    try {
      const directory = await api.keyDirectory();
      if (!directory.ok) {
        // Only an explicitly old server may use the compatibility path.
        if (directory.status === 404) return;
        throw new Error(directory.error ?? "키 디렉터리를 불러오지 못했습니다.");
      }
      if (!directory.identity_sig_pub || !directory.directory_hash
        || !Number.isSafeInteger(directory.security_epoch) || !directory.devices
        || !directory.device_history || !directory.approval_certificates
        || !directory.revocation_certificates || !directory.security_upgrade_certificates) {
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
      const own = directory.devices.find((device) => device.sid === me.sid);
      if (!own || !me.keypair || own.pub_key !== me.keypair.box.pk || own.sig_pub !== me.keypair.sign.pk) {
        throw new Error("현재 SID의 로컬 키와 검증된 공개키 디렉터리가 일치하지 않습니다.");
      }
      await pinTrustedDirectory({
        uid: me.uid,
        identity_sig_pub: directory.identity_sig_pub,
        security_epoch: directory.security_epoch!,
        directory_hash: directory.directory_hash,
        devices: directory.devices.map((device) => ({
          sid: device.sid,
          pub_key: device.pub_key,
          sig_pub: device.sig_pub,
          kind: device.kind,
          fingerprint: deviceFingerprint(device.pub_key, device.sig_pub).hash,
        })),
      });
      useStore.setState({ securityLocked: false });
    } catch (error) {
      lockForTrustViolation(error);
      return;
    }
  }
  await me.refreshBlocklist();
  // Pull shared block rules from the server (and push any local-only ones).
  await me.syncBlockRules().catch(() => undefined);
  await me.refreshBlocklist();
  await me.refreshConversations();
  if (!api.token) return;
  // Wire socket.
  const socket = getSocket(api.token);
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
    const state = useStore.getState();
    useStore.setState({ error: null });
    await state.syncBlockRules().catch(() => undefined);
    await state.refreshBlocklist();
    await state.refreshConversations();
    // Re-read after refresh: `state` predates the conversation reload.
    for (const conv of useStore.getState().conversations) {
      await queueConversationSync(conv.cid);
    }
  };
  socket.on("connect", syncAll);
  socket.on("connect_error", (error: Error) => {
    const detail = error?.message ?? "";
    if (/auth required|invalid token|device unknown|unauthenticated/i.test(detail)) {
      void useStore.getState().logout().then(() => {
        useStore.setState({ error: "로그인이 만료되었거나 이 기기가 폐기되었습니다. 다시 로그인하세요." });
      });
      return;
    }
    useStore.setState({ error: "실시간 서버 연결 실패 — 자동 재시도 중" });
  });
  socket.on("message_new", async (env: ServerMessage) => {
    // A conversation created on another device (e.g. the Android gateway
    // opening a new SMS thread) is not in our list yet — refresh so the
    // sidebar shows the thread the incoming message belongs to.
    if (!useStore.getState().conversations.some((c) => c.cid === env.cid)) {
      await useStore.getState().refreshConversations();
    }
    // Pull from the last contiguous local cursor. This avoids jumping over
    // older offline messages when the new event is for a later sequence.
    await queueConversationSync(env.cid);
  });
  socket.on("message_status", async (event: {
    cid: string;
    seq: number;
    carrier_status: string;
    carrier_error?: string | null;
    carrier_updated_at?: number | null;
  }) => {
    await setCarrierStatus(
      event.cid,
      event.seq,
      event.carrier_status,
      event.carrier_error ?? null,
      event.carrier_updated_at ? event.carrier_updated_at * 1000 : Date.now(),
    );
    if (useStore.getState().activeCid === event.cid) {
      useStore.setState({ activeMessages: await listMessages(event.cid) });
    }
  });
  socket.on("typing", (_data: { cid: string; user_id: number; is_typing: boolean }) => {
    // Surface typing state via a separate lightweight subscription.
    // no-op; UI can subscribe via socket directly if needed.
  });
  socket.on("blocklist_updated", async () => {
    // Another device of the same account changed the shared block rules.
    const state = useStore.getState();
    await state.syncBlockRules().catch(() => undefined);
    await state.refreshBlocklist();
    await reapplyBlocklist();
  });
  socket.on("conv_updated", async () => {
    await useStore.getState().refreshConversations();
  });
  socket.on("contacts_updated", async () => {
    // Bulk contact syncs fan out one account-scoped invalidation event. Reload
    // the authoritative labels immediately on every connected browser.
    await useStore.getState().refreshConversations();
  });
  socket.on("device_pending", () => {
    // DeviceManager owns the approval UI; a DOM event avoids coupling that
    // account-security surface to the message Zustand state.
    if (typeof window !== "undefined") {
      window.dispatchEvent(new CustomEvent("securemsg:device-pending"));
    }
  });
  if (socket.connected) await syncAll();
}

const syncJobs = new Map<string, Promise<void>>();

function queueConversationSync(cid: string): Promise<void> {
  const previous = syncJobs.get(cid) ?? Promise.resolve();
  const next = previous
    .catch(() => undefined)
    .then(() => useStore.getState().syncConversation(cid))
    .finally(() => {
      if (syncJobs.get(cid) === next) syncJobs.delete(cid);
    });
  syncJobs.set(cid, next);
  return next;
}

export async function verifyConversationKeyDirectory(result: ConversationMembersResult): Promise<void> {
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
  if (checkpointByUser.size !== membersByUser.size || proofByUser.size !== membersByUser.size) {
    throw new TrustViolationError("equivocation", "대화 참여자 디렉터리 checkpoint 수가 일치하지 않습니다.");
  }
  for (const [userId, members] of membersByUser) {
    const checkpoint = checkpointByUser.get(userId);
    const proof = proofByUser.get(userId);
    if (!checkpoint || !proof) {
      throw new TrustViolationError("equivocation", `사용자 ${userId}의 키 디렉터리가 누락되었습니다.`);
    }
    if (proof.identity_sig_pub !== checkpoint.identity_sig_pub
      || proof.security_epoch !== checkpoint.security_epoch
      || proof.directory_hash !== checkpoint.directory_hash) {
      throw new TrustViolationError("equivocation", `사용자 ${userId}의 checkpoint와 proof가 일치하지 않습니다.`);
    }
    verifyDirectoryProof(proof, members);
    await pinTrustedDirectory({
      uid: userId,
      identity_sig_pub: checkpoint.identity_sig_pub,
      security_epoch: checkpoint.security_epoch,
      directory_hash: checkpoint.directory_hash,
      devices: members.map((member) => ({
        sid: member.sid,
        pub_key: member.pub_key,
        sig_pub: member.sig_pub,
        kind: member.kind,
        fingerprint: deviceFingerprint(member.pub_key, member.sig_pub).hash,
      })),
    });
  }
}

function lockForTrustViolation(error: unknown): void {
  const message = error instanceof Error ? error.message : "알 수 없는 키 디렉터리 오류";
  useStore.setState({
    securityLocked: true,
    error: `보안 경고: ${message} 메시지 암복호화를 중단했습니다.`,
  });
}

async function reapplyBlocklist(): Promise<void> {
  const keywords = await listBlockKeywords();
  const senders = await listBlockedSenders();
  const username = useStore.getState().username;
  const senderBlockByCid = new Map<string, boolean>();
  for (const conv of useStore.getState().conversations) {
    const phone = ownedSmsPhone(conv, username);
    if (phone && matchesBlockedSender(phone, senders)) senderBlockByCid.set(conv.cid, true);
  }
  const messages = await listAllMessages();
  for (const message of messages) {
    const result = matchBlockKeywords(
      [message.subject, message.plaintext].filter(Boolean).join("\n"),
      keywords,
    );
    const blocked = result.blocked || (senderBlockByCid.get(message.cid) ?? false);
    if (Boolean(message.blocked) !== blocked) {
      await setBlocked(message.cid, message.seq, blocked);
    }
  }
  const cid = useStore.getState().activeCid;
  if (cid) useStore.setState({ activeMessages: await listMessages(cid) });
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
