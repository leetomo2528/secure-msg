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
  type MessageRow,
  type BlockRow,
  type MessageAttachment,
} from "./db";
import { applyBlock, matchBlockKeywords } from "./blocklist";
import { normalizePhone, ownedSmsPhone } from "./conversationPolicy";

export interface Conversation {
  cid: string;
  conv_id: number;
  name: string;
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
  username: string | null;
  uid: number | null;
  sid: string | null;
  deviceName: string | null;
  keypair: DeviceKeypair | null;
  conversations: Conversation[];
  activeCid: string | null;
  activeMessages: MessageRow[];
  blockKeywords: BlockRow[];
  deviceCache: Map<string, ConvMember>; // sid -> member info
  error: string | null;

  init: () => Promise<void>;
  register: (username: string, password: string) => Promise<boolean>;
  login: (username: string, password: string) => Promise<boolean>;
  addDevice: (username: string, password: string, deviceName: string) => Promise<boolean>;
  loginExistingDevice: (username: string, password: string) => Promise<boolean>;
  logout: () => Promise<void>;
  forgetLocalDevice: () => Promise<void>;
  refreshConversations: () => Promise<void>;
  newConversation: (members: string[]) => Promise<string | null>;
  newSmsConversation: (phone: string) => Promise<string | null>;
  selectConversation: (cid: string) => Promise<void>;
  syncConversation: (cid: string) => Promise<void>;
  send: (cid: string, text: string) => Promise<boolean>;
  sendContent: (cid: string, content: RelayContent) => Promise<boolean>;
  addBlock: (kw: string) => Promise<void>;
  removeBlock: (id: string) => Promise<void>;
  refreshBlocklist: () => Promise<void>;
}

export const useStore = create<State>((set, get) => ({
  ready: false,
  authed: false,
  username: null,
  uid: null,
  sid: null,
  deviceName: null,
  keypair: null,
  conversations: [],
  activeCid: null,
  activeMessages: [],
  blockKeywords: [],
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
        if (get().error !== "device not found") return false;
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
    set({ authed: true, error: null, ...meta });
    await postLogin();
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
    set({ authed: true, username: meta.username, uid: meta.uid, sid: meta.sid,
           deviceName: meta.deviceName, keypair: meta.keypair, error: null });
    await postLogin();
    return true;
  },

  logout: async () => {
    disconnectSocket();
    api.setToken(null);
    // Keep the local device key so the next login renews this device instead
    // of silently creating an orphaned server device. Plaintext caches go away.
    const meta = await getMeta();
    await clearSessionData();
    set({
      authed: false, username: meta?.username ?? null, uid: null, sid: null,
      deviceName: null, keypair: null, conversations: [],
      activeCid: null, activeMessages: [], deviceCache: new Map(), error: null,
    });
  },

  forgetLocalDevice: async () => {
    disconnectSocket();
    api.setToken(null);
    await clearAllData();
    set({
      authed: false, username: null, uid: null, sid: null,
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
    const memberMap = new Map<string, ConvMember>();
    for (const m of mr.members) memberMap.set(m.sid, m);
    set({ deviceCache: memberMap });
    await cacheDevices(mr.members.map((m) => ({
      sid: m.sid, user_id: m.user_id, name: m.name, pub_key: m.pub_key,
    })));

    // 2. Pull every page since our last cursor. Advance even past an envelope
    // this device cannot decrypt, so one malformed row cannot starve all newer
    // history forever.
    const me = useStore.getState();
    if (!me.sid || !me.keypair) return;
    const mySid = me.sid;
    const myKeypair = me.keypair;
    const pageSize = 200;
    let cursor = await getCursor(cid);
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
        const shouldShow = await applyBlock(
          cid,
          sm.seq,
          [content.subject, content.text].filter(Boolean).join("\n"),
        );
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
    await addBlockKeyword(kw);
    await get().refreshBlocklist();
    await reapplyBlocklist();
  },

  removeBlock: async (id) => {
    await removeBlockKeyword(id);
    await get().refreshBlocklist();
    await reapplyBlocklist();
  },

  refreshBlocklist: async () => {
    set({ blockKeywords: await listBlockKeywords() });
  },
}));

async function postLogin(): Promise<void> {
  const me = useStore.getState();
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
  const syncAll = async () => {
    const state = useStore.getState();
    useStore.setState({ error: null });
    for (const conv of state.conversations) {
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

async function reapplyBlocklist(): Promise<void> {
  const keywords = await listBlockKeywords();
  const messages = await listAllMessages();
  for (const message of messages) {
    const result = matchBlockKeywords(
      [message.subject, message.plaintext].filter(Boolean).join("\n"),
      keywords,
    );
    if (Boolean(message.blocked) !== result.blocked) {
      await setBlocked(message.cid, message.seq, result.blocked);
    }
  }
  const cid = useStore.getState().activeCid;
  if (cid) useStore.setState({ activeMessages: await listMessages(cid) });
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/** Exported for unit tests. Parses the decrypted relay JSON with hard limits. */
export function decodeRelayContent(value: string): RelayContent {
  try {
    const parsed = JSON.parse(value) as Partial<RelayContent>;
    if (parsed.v === 1 && (parsed.type === "text" || parsed.type === "mms")
      && typeof parsed.text === "string" && parsed.text.length <= 20_000) {
      let totalBytes = 0;
      const candidates = Array.isArray(parsed.attachments) ? parsed.attachments.slice(0, 64) : [];
      const attachments = candidates
        .filter((item): item is MessageAttachment => {
          if (!item || typeof item !== "object"
            || typeof item.name !== "string"
            || typeof item.content_type !== "string"
            || !isSafeMimeType(item.content_type)
            || typeof item.data !== "string"
            || typeof item.size !== "number"
            || !Number.isInteger(item.size)
            || item.size < 0
            || item.size > 512 * 1024
            || totalBytes + item.size > 512 * 1024) return false;
          try {
            if (unb64u(item.data).byteLength !== item.size) return false;
          } catch {
            return false;
          }
          totalBytes += item.size;
          return true;
        }).slice(0, 8);
      return {
        v: 1,
        type: parsed.type,
        text: parsed.text,
        subject: typeof parsed.subject === "string" ? parsed.subject.slice(0, 120) : undefined,
        attachments,
      };
    }
  } catch {
    // Legacy SMS rows were encrypted as plain text.
  }
  return { v: 1, type: "text", text: value.slice(0, 20_000), attachments: [] };
}

function isSafeMimeType(value: string): boolean {
  return /^[A-Za-z0-9!#$&^_.+-]+\/[A-Za-z0-9!#$&^_.+-]+$/.test(value)
    && value.length <= 120;
}
