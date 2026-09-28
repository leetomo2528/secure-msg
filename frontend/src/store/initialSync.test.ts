import "fake-indexeddb/auto";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * SM-11: a re-login (or a new browser's first login) imports the account's
 * whole history, and that history must not come back as unread. Everything
 * below runs the real login → installSession → postLogin → syncAll →
 * syncConversation path against fake-indexeddb, with real encryption and a
 * real key directory; only the relay's HTTP answers and the socket are faked.
 */

type Handler = (...args: unknown[]) => unknown;
const socket = vi.hoisted(() => ({ handlers: new Map<string, Handler>() }));

vi.mock("../net/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../net/api")>();
  return {
    ...actual,
    getSocket: vi.fn(() => ({
      connected: true,
      emit: () => undefined,
      on: (event: string, handler: Handler) => { socket.handlers.set(event, handler); },
      off: (event: string) => { socket.handlers.delete(event); },
    })),
    disconnectSocket: vi.fn(),
  };
});

vi.mock("../crypto/keys", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../crypto/keys")>();
  // Argon2 adds nothing here; the relay mock accepts any hash.
  return { ...actual, hashPassword: vi.fn(async () => "pw-hash") };
});

import {
  b64u,
  encryptMessage,
  generateKeypair,
  initCrypto,
  type DeviceKeypair,
  type Envelope,
} from "../crypto/keys";
import { deviceFingerprint, recipientKeysetHash, serverDirectoryHash } from "../crypto/deviceTrust";
import {
  api,
  type ConvMember,
  type ConversationMembersResult,
  type ServerMessage,
} from "../net/api";
import {
  clearAllData,
  clearSessionData,
  conversationSummaries,
  getInitialSync,
  getMeta,
  setCursor,
  setMeta,
} from "./db";
import { __testing, useStore } from "./useStore";

const CHALLENGE = b64u(new Uint8Array(32));
const ME_UID = 1;
const ME_SID = "web-me";
const PEER_UID = 2;
const PEER_SID = "web-peer";

let me: DeviceKeypair;
let peer: DeviceKeypair;
let myMember: ConvMember;
let peerMember: ConvMember;
let incoming: Envelope;

function rootProof(member: ConvMember) {
  const directoryHash = serverDirectoryHash([member]);
  return {
    checkpoint: {
      user_id: member.user_id,
      identity_sig_pub: member.sig_pub,
      security_epoch: 1,
      directory_hash: directoryHash,
      security_mode: "verified_v2" as const,
    },
    proof: {
      user_id: member.user_id,
      identity_sig_pub: member.sig_pub,
      security_epoch: 1,
      directory_hash: directoryHash,
      security_mode: "verified_v2" as const,
      device_history: [{
        sid: member.sid,
        kind: member.kind,
        pub_key: member.pub_key,
        sig_pub: member.sig_pub,
        fingerprint: deviceFingerprint(member.pub_key, member.sig_pub).hash,
        trust_state: "approved" as const,
        challenge: CHALLENGE,
        approved_by_sid: member.sid,
        verification_state: "verified" as const,
      }],
      approval_certificates: [],
      revocation_certificates: [],
      security_upgrade_certificates: [],
    },
  };
}

/** A two-account conversation: this browser and one peer, both root devices. */
function conversationDirectory(): ConversationMembersResult {
  const mine = rootProof(myMember);
  const theirs = rootProof(peerMember);
  return {
    ok: true,
    members: [myMember, peerMember],
    recipient_keyset_hash: recipientKeysetHash([myMember, peerMember]),
    directory_checkpoints: [mine.checkpoint, theirs.checkpoint],
    directory_proofs: [mine.proof, theirs.proof],
  };
}

function ownDirectory() {
  const device = {
    ...myMember,
    name: "browser",
    created_at: 1,
    last_seen: 1,
  };
  const { proof } = rootProof(myMember);
  return {
    ok: true as const,
    user_id: ME_UID,
    identity_sig_pub: me.sign.pk,
    security_epoch: 1,
    directory_hash: serverDirectoryHash([device]),
    security_mode: "verified_v2" as const,
    devices: [device],
    device_history: proof.device_history,
    approval_certificates: [],
    revocation_certificates: [],
    security_upgrade_certificates: [],
  };
}

function fromPeer(cid: string, seq: number): ServerMessage {
  return {
    id: seq,
    seq,
    cid,
    conv_id: 1,
    sender_id: PEER_UID,
    sender_sid: PEER_SID,
    sender_pub_key: peer.box.pk,
    payload: incoming,
    created_at: 1_700_000_000 + seq,
  };
}

/** What the relay holds: conversation id → its messages, in list order. */
let relay: Map<string, ServerMessage[]>;
/** Return true to answer a page request with a failure (network error / 429). */
let failPage: (cid: string, since: number) => boolean;
let pageRequests: Array<{ cid: string; since: number }>;

function holdThread(cid: string, count: number): void {
  const rows = relay.get(cid) ?? [];
  const start = rows.length;
  for (let seq = start + 1; seq <= start + count; seq += 1) rows.push(fromPeer(cid, seq));
  relay.set(cid, rows);
}

function mockRelay(): void {
  vi.spyOn(api, "deviceLogin").mockResolvedValue({
    ok: true, uid: ME_UID, challenge_id: "challenge-1", challenge: CHALLENGE, session_version: 1,
  } as never);
  vi.spyOn(api, "deviceLoginProof").mockResolvedValue(
    { ok: true, token: "token", trust_state: "approved" } as never,
  );
  vi.spyOn(api, "logout").mockResolvedValue({ ok: true } as never);
  vi.spyOn(api, "keyDirectory").mockImplementation(async () => ownDirectory() as never);
  vi.spyOn(api, "tokenRefresh").mockResolvedValue({ ok: false });
  vi.spyOn(api, "listBlockRules").mockResolvedValue({ ok: false } as never);
  vi.spyOn(api, "listConversations").mockImplementation(async () => ({
    ok: true,
    conversations: [...relay.keys()].map((cid, index) => ({
      cid, conv_id: index + 1, name: cid, members: ["alice", "bob"], created_at: 1,
    })),
  }) as never);
  vi.spyOn(api, "convMembers").mockImplementation(async () => conversationDirectory());
  vi.spyOn(api, "fetchMessages").mockImplementation(async (cid, since, limit = 200) => {
    pageRequests.push({ cid, since });
    if (failPage(cid, since)) return { ok: false, error: "rate limited", status: 429 } as never;
    return {
      ok: true,
      messages: (relay.get(cid) ?? []).filter((message) => message.seq > since).slice(0, limit),
    };
  });
}

/** One page load: the web app keeps no token, so every load is a login. */
async function login(): Promise<void> {
  __testing.resetSyncJobs();
  await expect(useStore.getState().loginExistingDevice("alice", "password")).resolves.toBe(true);
  expect(useStore.getState().securityLocked).toBe(false);
}

/** The socket (re)connects, which runs the whole-account sync pass again. */
async function reconnect(): Promise<void> {
  const handler = socket.handlers.get("connect");
  expect(handler).toBeDefined();
  await handler!();
}

/** The relay announces a new message the way the socket does. */
async function messageNew(message: ServerMessage): Promise<void> {
  const handler = socket.handlers.get("message_new");
  expect(handler).toBeDefined();
  await handler!(message);
}

async function summary(cid: string) {
  return (await conversationSummaries(ME_SID, ME_UID))[cid];
}

describe("initial sync after logout / on a new browser (SM-11)", () => {
  beforeAll(async () => {
    await initCrypto();
    me = generateKeypair();
    peer = generateKeypair();
    myMember = {
      user_id: ME_UID, device_id: 10, sid: ME_SID, pub_key: me.box.pk, sig_pub: me.sign.pk, kind: "web",
    };
    peerMember = {
      user_id: PEER_UID, device_id: 20, sid: PEER_SID, pub_key: peer.box.pk, sig_pub: peer.sign.pk, kind: "web",
    };
    incoming = await encryptMessage(
      JSON.stringify({ v: 1, type: "text", text: "예전 메시지", attachments: [] }),
      [{ sid: ME_SID, pub_key: me.box.pk }],
      peer,
    );
  });

  beforeEach(async () => {
    relay = new Map();
    failPage = () => false;
    pageRequests = [];
    socket.handlers.clear();
    await clearAllData();
    await setMeta({ username: "alice", uid: ME_UID, sid: ME_SID, deviceName: "browser", keypair: me });
    mockRelay();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    __testing.resetSyncJobs();
    api.setToken(null);
    useStore.setState((state) => ({
      securityGeneration: state.securityGeneration + 1,
      authed: false, approvalPending: false, securityLocked: false,
      uid: null, sid: null, keypair: null, conversations: [], activeCid: null, error: null,
    }));
  });

  it("imports history as read after logout, then badges the next incoming message", async () => {
    holdThread("c-hist", 150);
    await login();
    await useStore.getState().logout();

    // Logout keeps the device key and arms the marker in the same transaction.
    expect(await getMeta()).toMatchObject({ sid: ME_SID });
    expect(await getInitialSync()).toEqual({ armed: true });
    expect(await summary("c-hist")).toBeUndefined();

    await login();
    expect(await summary("c-hist")).toMatchObject({ lastSeq: 150, unread: 0 });
    expect(useStore.getState().convMeta["c-hist"]).toMatchObject({ lastSeq: 150, unread: 0 });
    // The pass covered the thread, so the marker is gone for good.
    expect(await getInitialSync()).toBeNull();

    holdThread("c-hist", 1);
    await messageNew(fromPeer("c-hist", 151));
    expect(await summary("c-hist")).toMatchObject({ lastSeq: 151, unread: 1 });
  });

  it("imports history as read on a new browser's first login", async () => {
    holdThread("c-hist", 150);
    // A conversation with no messages yet is covered by its empty pull and
    // must not keep the marker armed forever.
    relay.set("c-empty", []);
    expect(await getInitialSync()).toBeNull();

    await login();

    expect(await summary("c-hist")).toMatchObject({ lastSeq: 150, unread: 0 });
    expect(await getInitialSync()).toBeNull();

    holdThread("c-hist", 1);
    await messageNew(fromPeer("c-hist", 151));
    expect(await summary("c-hist")).toMatchObject({ lastSeq: 151, unread: 1 });
  });

  it("marks every page of a long thread read, not only the first", async () => {
    holdThread("c-long", 450);

    await login();

    expect(pageRequests.filter((request) => request.cid === "c-long").map((request) => request.since))
      .toEqual([0, 200, 400]);
    expect(await summary("c-long")).toMatchObject({ lastSeq: 450, unread: 0 });
  });

  it("still badges a thread that first arrives via message_new, even mid-pass", async () => {
    holdThread("c-hist", 10);
    let racing: Promise<void> | null = null;
    const fetchMessages = vi.mocked(api.fetchMessages).getMockImplementation()!;
    vi.mocked(api.fetchMessages).mockImplementation(async (cid, since, limit) => {
      // A peer opens a new thread while the initial pass is still pulling.
      if (cid === "c-hist" && racing == null) {
        holdThread("c-new", 5);
        racing = messageNew(fromPeer("c-new", 5));
      }
      return await fetchMessages(cid, since, limit);
    });

    await login();
    await racing;

    expect(await summary("c-hist")).toMatchObject({ lastSeq: 10, unread: 0 });
    expect(await summary("c-new")).toMatchObject({ lastSeq: 5, unread: 5 });

    // And one that arrives after the pass has finished.
    holdThread("c-later", 3);
    await messageNew(fromPeer("c-later", 3));
    expect(await summary("c-later")).toMatchObject({ lastSeq: 3, unread: 3 });
  });

  it("treats a cursor-less thread on an ordinary re-login as new", async () => {
    holdThread("c-hist", 10);
    await login();
    expect(await getInitialSync()).toBeNull();

    // Created while this browser was closed; the store still has read state.
    holdThread("c-offline", 7);
    await login();

    expect(await summary("c-hist")).toMatchObject({ lastSeq: 10, unread: 0 });
    expect(await summary("c-offline")).toMatchObject({ lastSeq: 7, unread: 7 });
  });

  it("does not arm on a store that already has cursor rows", async () => {
    // Legacy-shaped read state from before this session; not an initial sync.
    await setCursor("c-known", 3);
    holdThread("c-other", 4);

    await login();

    expect(await getInitialSync()).toBeNull();
    expect(await summary("c-other")).toMatchObject({ lastSeq: 4, unread: 4 });
  });

  it("resumes an interrupted pass on the next login", async () => {
    holdThread("c-long", 450);
    holdThread("c-short", 30);
    // The relay's sync budget runs out after the long thread's first page.
    failPage = (cid, since) => cid === "c-long" && since === 200;

    await login();

    // The failed thread stays pending; the one that finished is off the list.
    expect(await getInitialSync()).toEqual({ pending: ["c-long"] });
    expect(await summary("c-short")).toMatchObject({ lastSeq: 30, unread: 0 });
    expect(await summary("c-long")).toMatchObject({ lastSeq: 200, unread: 0 });

    // Page reload: cursor rows exist now, but the pending marker survives.
    failPage = () => false;
    await login();

    expect(await summary("c-long")).toMatchObject({ lastSeq: 450, unread: 0 });
    expect(await getInitialSync()).toBeNull();
  });

  it("ends the pass on an account with no conversations yet", async () => {
    // New browser, empty account: the fetched list is empty, so the initial
    // pass is over the moment it has been listed.
    await login();
    expect(await getInitialSync()).toBeNull();

    // A thread opened while this session's socket was down arrives with the
    // reconnect pass, not via message_new; it is new, so it badges.
    holdThread("c-new", 3);
    await reconnect();

    expect(await summary("c-new")).toMatchObject({ lastSeq: 3, unread: 3 });
  });

  it("badges a new thread that races the connect pass's list refresh on an empty account", async () => {
    // New browser, empty account. While the connect handler re-fetches the
    // list, a peer opens a thread: its message_new lands mid-refresh and its
    // sync is still waiting on the key directory when the pass settles.
    let listCalls = 0;
    let racing: Promise<void> | null = null;
    const listConversations = vi.mocked(api.listConversations).getMockImplementation()!;
    vi.mocked(api.listConversations).mockImplementation(async () => {
      listCalls += 1;
      if (listCalls === 2) {
        holdThread("c-new", 3);
        racing = messageNew(fromPeer("c-new", 3));
      }
      return await listConversations();
    });
    const convMembers = vi.mocked(api.convMembers).getMockImplementation()!;
    vi.mocked(api.convMembers).mockImplementation(async (cid) => {
      if (cid === "c-new") await new Promise((resolve) => setTimeout(resolve, 30));
      return await convMembers(cid);
    });

    await login();
    await racing;

    expect(listCalls).toBeGreaterThanOrEqual(2);
    expect(await summary("c-new")).toMatchObject({ lastSeq: 3, unread: 3 });
    expect(await getInitialSync()).toBeNull();
  });

  it("keeps the marker armed while the conversation list cannot be fetched", async () => {
    holdThread("c-hist", 20);
    const listConversations = vi.mocked(api.listConversations).getMockImplementation()!;
    vi.mocked(api.listConversations).mockResolvedValue({ ok: false, error: "offline" } as never);

    await login();

    // An empty list the pass never fetched says nothing about the account.
    expect(await getInitialSync()).toEqual({ armed: true });

    vi.mocked(api.listConversations).mockImplementation(listConversations);
    await reconnect();

    expect(await summary("c-hist")).toMatchObject({ lastSeq: 20, unread: 0 });
    expect(await getInitialSync()).toBeNull();
  });

  it("re-arms on logout even in the middle of a pending pass", async () => {
    holdThread("c-long", 450);
    failPage = (cid, since) => cid === "c-long" && since === 200;
    await login();
    expect(await getInitialSync()).toEqual({ pending: ["c-long"] });

    await clearSessionData();
    expect(await getInitialSync()).toEqual({ armed: true });
    expect(await getMeta()).toMatchObject({ sid: ME_SID });
  });
});
