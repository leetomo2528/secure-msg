import "fake-indexeddb/auto";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";

const effects = vi.hoisted(() => ({
  encrypt: vi.fn(),
  decrypt: vi.fn(),
  sendMessage: vi.fn(),
  putMessage: vi.fn(),
  setCursor: vi.fn(),
  applyBlock: vi.fn(),
  pinTrustedDirectory: vi.fn(),
  clearSessionData: vi.fn(),
  clearDeviceForReregistration: vi.fn(),
  setMeta: vi.fn(),
  cacheDevice: vi.fn(),
  getMeta: vi.fn(),
  pinTrustedDirectories: vi.fn(),
  addBlockKeyword: vi.fn(),
  removeBlockKeyword: vi.fn(),
  putBlockKeywordRow: vi.fn(),
  addBlockedSender: vi.fn(),
  removeBlockedSender: vi.fn(),
  putBlockedSenderRow: vi.fn(),
  socketEmit: vi.fn(),
  socketOn: vi.fn(),
  socketOff: vi.fn(),
  getSocket: vi.fn(),
}));

vi.mock("../crypto/keys", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../crypto/keys")>();
  return {
    ...actual,
    encryptMessage: effects.encrypt,
    decryptMessageWithSender: effects.decrypt,
  };
});

vi.mock("../net/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../net/api")>();
  return {
    ...actual,
    waitForSocketConnected: vi.fn(async () => true),
    sendMessage: effects.sendMessage,
    getSocket: effects.getSocket.mockImplementation(() => ({
      connected: true,
      emit: effects.socketEmit,
      on: effects.socketOn,
      off: effects.socketOff,
    })),
    disconnectSocket: vi.fn(),
  };
});

vi.mock("./db", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./db")>();
  return {
    ...actual,
    putMessage: effects.putMessage,
    setCursor: effects.setCursor,
    pinTrustedDirectory: effects.pinTrustedDirectory,
    clearSessionData: effects.clearSessionData,
    clearDeviceForReregistration: effects.clearDeviceForReregistration,
    setMeta: effects.setMeta,
    cacheDevice: effects.cacheDevice,
    getMeta: effects.getMeta,
    pinTrustedDirectories: effects.pinTrustedDirectories,
    addBlockKeyword: effects.addBlockKeyword,
    removeBlockKeyword: effects.removeBlockKeyword,
    putBlockKeywordRow: effects.putBlockKeywordRow,
    addBlockedSender: effects.addBlockedSender,
    removeBlockedSender: effects.removeBlockedSender,
    putBlockedSenderRow: effects.putBlockedSenderRow,
  };
});

vi.mock("./blocklist", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./blocklist")>();
  return { ...actual, applyBlock: effects.applyBlock };
});

import { b64u, generateKeypair, initCrypto } from "../crypto/keys";
import { deviceFingerprint, recipientKeysetHash, serverDirectoryHash } from "../crypto/deviceTrust";
import { api, type ConvMember, type ConversationMembersResult } from "../net/api";
import { __testing, useStore } from "./useStore";

const originalSyncConversation = useStore.getState().syncConversation;
const originalRefreshBlocklist = useStore.getState().refreshBlocklist;
const originalRefreshConversations = useStore.getState().refreshConversations;
const originalSyncBlockRules = useStore.getState().syncBlockRules;

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

function authenticated(generation = 1) {
  const keypair = generateKeypair();
  api.setToken(`token-${generation}`);
  useStore.setState({
    authed: true,
    approvalPending: false,
    securityLocked: false,
    securityGeneration: generation,
    username: `user${generation}`,
    uid: generation,
    sid: `sid-${generation}`,
    keypair,
    conversations: [],
    activeCid: null,
    activeMessages: [],
    error: null,
  });
  return keypair;
}

function validDirectory(member: ConvMember): ConversationMembersResult {
  const directoryHash = serverDirectoryHash([{
    sid: member.sid,
    pub_key: member.pub_key,
    sig_pub: member.sig_pub,
    kind: member.kind,
  }]);
  return {
    ok: true,
    members: [member],
    recipient_keyset_hash: recipientKeysetHash([member]),
    directory_checkpoints: [{
      user_id: member.user_id,
      identity_sig_pub: member.sig_pub,
      security_epoch: 1,
      directory_hash: directoryHash,
      security_mode: "verified_v2",
    }],
    directory_proofs: [{
      user_id: member.user_id,
      identity_sig_pub: member.sig_pub,
      security_epoch: 1,
      directory_hash: directoryHash,
      security_mode: "verified_v2",
      device_history: [{
        sid: member.sid,
        kind: member.kind,
        pub_key: member.pub_key,
        sig_pub: member.sig_pub,
        fingerprint: deviceFingerprint(member.pub_key, member.sig_pub).hash,
        trust_state: "approved",
        challenge: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        approved_by_sid: member.sid,
        verification_state: "verified",
      }],
      approval_certificates: [],
      revocation_certificates: [],
      security_upgrade_certificates: [],
    }],
  };
}

function validOwnDirectory(generation: number, keypair: ReturnType<typeof generateKeypair>) {
  const device = {
    user_id: generation,
    device_id: generation * 10,
    sid: `sid-${generation}`,
    name: `device-${generation}`,
    pub_key: keypair.box.pk,
    sig_pub: keypair.sign.pk,
    kind: "web" as const,
    created_at: 1,
    last_seen: 1,
  };
  return {
    ok: true as const,
    user_id: generation,
    identity_sig_pub: keypair.sign.pk,
    security_epoch: 1,
    directory_hash: serverDirectoryHash([device]),
    security_mode: "verified_v2" as const,
    devices: [device],
    device_history: [{
      sid: device.sid,
      kind: device.kind,
      pub_key: device.pub_key,
      sig_pub: device.sig_pub,
      fingerprint: deviceFingerprint(device.pub_key, device.sig_pub).hash,
      trust_state: "approved" as const,
      challenge: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      approved_by_sid: device.sid,
      verification_state: "verified" as const,
    }],
    approval_certificates: [],
    revocation_certificates: [],
    security_upgrade_certificates: [],
  };
}

function member(userId = 1, sid = "sender"):
ConvMember {
  const keys = generateKeypair();
  return {
    user_id: userId,
    device_id: userId * 10,
    sid,
    pub_key: keys.box.pk,
    sig_pub: keys.sign.pk,
    kind: "web",
  };
}

describe("security generation invalidation", () => {
  beforeAll(async () => { await initCrypto(); });

  afterEach(() => {
    vi.restoreAllMocks();
    for (const spy of Object.values(effects)) spy.mockReset();
    effects.getSocket.mockImplementation(() => ({
      connected: true,
      emit: effects.socketEmit,
      on: effects.socketOn,
      off: effects.socketOff,
    }));
    effects.applyBlock.mockResolvedValue(true);
    effects.pinTrustedDirectory.mockResolvedValue(undefined);
    effects.clearSessionData.mockResolvedValue(undefined);
    effects.clearDeviceForReregistration.mockResolvedValue(undefined);
    effects.setMeta.mockResolvedValue(undefined);
    effects.cacheDevice.mockResolvedValue(undefined);
    effects.getMeta.mockResolvedValue(null);
    effects.pinTrustedDirectories.mockResolvedValue(undefined);
    effects.addBlockKeyword.mockResolvedValue({ id: "local-keyword", keyword: "old-keyword", created_at: 1 });
    effects.removeBlockKeyword.mockResolvedValue(undefined);
    effects.putBlockKeywordRow.mockResolvedValue(undefined);
    effects.addBlockedSender.mockResolvedValue({ id: "local-sender", sender: "+821011112222", created_at: 1 });
    effects.removeBlockedSender.mockResolvedValue(undefined);
    effects.putBlockedSenderRow.mockResolvedValue(undefined);
    effects.putMessage.mockResolvedValue(undefined);
    effects.setCursor.mockResolvedValue(undefined);
    effects.sendMessage.mockResolvedValue({ ok: true, seq: 1 });
    __testing.resetSyncJobs();
    api.setToken(null);
    useStore.setState({
      authed: false, approvalPending: false, securityLocked: false,
      securityGeneration: 0, username: null, uid: null, sid: null,
      keypair: null, conversations: [], activeCid: null,
      activeMessages: [], error: null,
      syncConversation: originalSyncConversation,
      refreshBlocklist: originalRefreshBlocklist,
      refreshConversations: originalRefreshConversations,
      syncBlockRules: originalSyncBlockRules,
    });
  });

  it("aborts a send whose member lookup completes after a trust lock", async () => {
    authenticated();
    const lookup = deferred<ConversationMembersResult>();
    vi.spyOn(api, "convMembers").mockReturnValue(lookup.promise);

    const sending = useStore.getState().send("cid", "secret");
    await vi.waitFor(() => expect(api.convMembers).toHaveBeenCalledOnce());
    __testing.lockForTrustViolation(new Error("directory changed"));
    lookup.resolve(validDirectory(member()));

    await expect(sending).resolves.toBe(false);
    expect(effects.encrypt).not.toHaveBeenCalled();
    expect(effects.sendMessage).not.toHaveBeenCalled();
    expect(effects.putMessage).not.toHaveBeenCalled();
  });

  it("aborts a sync whose message page completes after a trust lock", async () => {
    authenticated();
    vi.spyOn(api, "convMembers").mockResolvedValue(validDirectory(member()));
    const page = deferred<any>();
    vi.spyOn(api, "fetchMessages").mockReturnValue(page.promise);

    const syncing = useStore.getState().syncConversation("cid");
    await vi.waitFor(() => expect(api.fetchMessages).toHaveBeenCalledOnce());
    __testing.lockForTrustViolation(new Error("revoked"));
    page.resolve({ ok: true, messages: [{ seq: 1 }] });
    await syncing;

    expect(effects.decrypt).not.toHaveBeenCalled();
    expect(effects.applyBlock).not.toHaveBeenCalled();
    expect(effects.putMessage).not.toHaveBeenCalled();
    expect(effects.setCursor).not.toHaveBeenCalled();
    expect(effects.socketEmit).not.toHaveBeenCalledWith("message_delivered", expect.anything());
  });

  it("does not let a rejected old-session request alter the new session", async () => {
    authenticated(10);
    const lookup = deferred<ConversationMembersResult>();
    vi.spyOn(api, "convMembers").mockReturnValue(lookup.promise);
    const sending = useStore.getState().send("cid", "old secret");
    await vi.waitFor(() => expect(api.convMembers).toHaveBeenCalledOnce());

    authenticated(11);
    useStore.setState({ error: "new-session-error", securityLocked: false });
    lookup.resolve({ ok: false, error: "old request rejected" });

    await expect(sending).resolves.toBe(false);
    expect(useStore.getState()).toMatchObject({
      securityGeneration: 11,
      error: "new-session-error",
      securityLocked: false,
    });
  });

  it.each([
    ["newConversation", () => useStore.getState().newConversation(["bob"])],
    ["newSmsConversation", () => useStore.getState().newSmsConversation("010-1234-5678")],
  ])("returns no conversation when a stale %s response completes after an account switch", async (_name, start) => {
    authenticated(12);
    const response = deferred<any>();
    vi.spyOn(api, "createConversation").mockReturnValue(response.promise);

    const creating = start();
    await vi.waitFor(() => expect(api.createConversation).toHaveBeenCalledOnce());
    authenticated(13);
    useStore.setState({ conversations: [{ cid: "new-account" } as any], error: "new-session-error" });
    response.resolve({ ok: true, cid: "old-account" });

    await expect(creating).resolves.toBeNull();
    expect(useStore.getState()).toMatchObject({
      securityGeneration: 13,
      conversations: [{ cid: "new-account" }],
      error: "new-session-error",
    });
  });

  it("does not rename a new account conversation from a stale response", async () => {
    authenticated(14);
    useStore.setState({ conversations: [{ cid: "shared-cid", name: "old name" } as any] });
    const response = deferred<any>();
    vi.spyOn(api, "renameConversation").mockReturnValue(response.promise);

    const renaming = useStore.getState().renameConversation("shared-cid", "stale rename");
    await vi.waitFor(() => expect(api.renameConversation).toHaveBeenCalledOnce());
    authenticated(15);
    useStore.setState({ conversations: [{ cid: "shared-cid", name: "new account name" } as any] });
    response.resolve({ ok: true, cid: "shared-cid", name: "stale rename" });

    await expect(renaming).resolves.toBe(false);
    expect(useStore.getState().conversations[0]?.name).toBe("new account name");
  });

  it.each([
    ["keyword add", () => useStore.getState().addBlock("old-keyword"), "addBlockKeyword"],
    ["sender add", () => useStore.getState().addBlockedSenderRule("010-1111-2222"), "addBlockedSender"],
  ])("does not continue a stale %s after its local write crosses an account switch", async (_name, start, effectName) => {
    authenticated(16);
    const writing = deferred<any>();
    const upload = vi.spyOn(api, "addBlockRule");
    effects[effectName as "addBlockKeyword" | "addBlockedSender"].mockReturnValueOnce(writing.promise);

    const mutation = start();
    await vi.waitFor(() => expect(effects[effectName as "addBlockKeyword" | "addBlockedSender"]).toHaveBeenCalledOnce());
    authenticated(17);
    useStore.setState({ error: "new-session-error" });
    writing.resolve(effectName === "addBlockKeyword"
      ? { id: "old-local", keyword: "old-keyword", created_at: 1 }
      : { id: "old-local", sender: "+821011112222", created_at: 1 });

    await mutation;
    expect(upload).not.toHaveBeenCalled();
    expect(useStore.getState()).toMatchObject({ securityGeneration: 17, error: "new-session-error" });
  });

  it.each([
    ["keyword remove", () => useStore.getState().removeBlock("srv:91")],
    ["sender remove", () => useStore.getState().removeBlockedSenderRule("srv:92")],
  ])("does not perform local deletion for a stale %s server response", async (_name, start) => {
    authenticated(18);
    const response = deferred<any>();
    vi.spyOn(api, "removeBlockRule").mockReturnValue(response.promise);

    const mutation = start();
    await vi.waitFor(() => expect(api.removeBlockRule).toHaveBeenCalledOnce());
    authenticated(19);
    useStore.setState({ error: "new-session-error" });
    response.resolve({ ok: true });

    await mutation;
    expect(effects.removeBlockKeyword).not.toHaveBeenCalled();
    expect(effects.removeBlockedSender).not.toHaveBeenCalled();
    expect(useStore.getState()).toMatchObject({ securityGeneration: 19, error: "new-session-error" });
  });

  it("separates queued sync jobs by generation and drops stale followers", async () => {
    authenticated(20);
    const oldFirst = deferred<void>();
    const calls: string[] = [];
    useStore.setState({
      syncConversation: vi.fn(async () => {
        calls.push("old1");
        await oldFirst.promise;
      }),
    });
    const first = __testing.queueConversationSync("same-cid");
    const second = __testing.queueConversationSync("same-cid");
    await vi.waitFor(() => expect(calls).toEqual(["old1"]));

    authenticated(21);
    useStore.setState({
      syncConversation: vi.fn(async () => { calls.push("new1"); }),
    });
    const fresh = __testing.queueConversationSync("same-cid");
    await expect(fresh).resolves.toBeUndefined();
    expect(calls).toEqual(["old1", "new1"]);

    oldFirst.resolve();
    await Promise.all([first, second]);
    expect(calls).toEqual(["old1", "new1"]);
  });

  it("shares one same-context post-login setup and initial socket sync", async () => {
    const keypair = authenticated(29);
    const directory = deferred<any>();
    vi.spyOn(api, "keyDirectory").mockReturnValue(directory.promise);
    const refreshBlocklist = vi.fn(async () => undefined);
    const syncBlockRules = vi.fn(async () => undefined);
    const refreshConversations = vi.fn(async () => undefined);
    useStore.setState({ refreshBlocklist, syncBlockRules, refreshConversations });

    const first = __testing.postLogin();
    const second = __testing.postLogin();
    expect(first).toBe(second);
    await vi.waitFor(() => expect(api.keyDirectory).toHaveBeenCalledOnce());

    directory.resolve(validOwnDirectory(29, keypair));
    await Promise.all([first, second]);

    expect(api.keyDirectory).toHaveBeenCalledOnce();
    expect(effects.pinTrustedDirectory).toHaveBeenCalledOnce();
    expect(effects.getSocket).toHaveBeenCalledOnce();
    expect(effects.socketOff).toHaveBeenCalledTimes(9);
    expect(effects.socketOn).toHaveBeenCalledTimes(9);
    expect(syncBlockRules).toHaveBeenCalledTimes(2);
    expect(refreshConversations).toHaveBeenCalledTimes(2);
  });

  it.each([
    { status: 0, error: "서버에 연결할 수 없습니다" },
    { status: 503, error: "service unavailable" },
  ])("keeps key-directory status $status retryable without trust locking", async (failure) => {
    const keypair = authenticated(33 + failure.status);
    vi.spyOn(api, "keyDirectory")
      .mockResolvedValueOnce({ ok: false, ...failure })
      .mockResolvedValueOnce(validOwnDirectory(33 + failure.status, keypair));
    const refreshBlocklist = vi.fn(async () => undefined);
    const syncBlockRules = vi.fn(async () => undefined);
    const refreshConversations = vi.fn(async () => undefined);
    useStore.setState({ refreshBlocklist, syncBlockRules, refreshConversations });

    await expect(__testing.postLogin()).resolves.toBeUndefined();

    expect(useStore.getState()).toMatchObject({
      authed: true,
      securityLocked: false,
      error: expect.stringContaining(failure.error),
    });
    expect(effects.pinTrustedDirectory).not.toHaveBeenCalled();
    expect(effects.getSocket).not.toHaveBeenCalled();
    expect(refreshBlocklist).not.toHaveBeenCalled();
    expect(refreshConversations).not.toHaveBeenCalled();

    await expect(__testing.postLogin()).resolves.toBeUndefined();
    expect(api.keyDirectory).toHaveBeenCalledTimes(2);
    expect(effects.pinTrustedDirectory).toHaveBeenCalledOnce();
    expect(effects.getSocket).toHaveBeenCalledOnce();
    expect(useStore.getState().error).toBeNull();
  });

  it("still locks when a successful key-directory response has an invalid proof", async () => {
    const keypair = authenticated(34);
    vi.spyOn(api, "keyDirectory").mockResolvedValue({
      ...validOwnDirectory(34, keypair),
      directory_hash: "invalid-directory-proof",
    });

    await expect(__testing.postLogin()).resolves.toBeUndefined();

    expect(useStore.getState()).toMatchObject({
      authed: true,
      securityLocked: true,
      error: expect.stringContaining("보안 경고:"),
    });
    expect(effects.pinTrustedDirectory).not.toHaveBeenCalled();
    expect(effects.getSocket).not.toHaveBeenCalled();
  });

  it("does not let an invalidated post-login block a new generation", async () => {
    authenticated(30);
    const oldDirectory = deferred<any>();
    vi.spyOn(api, "keyDirectory")
      .mockReturnValueOnce(oldDirectory.promise)
      .mockImplementationOnce(async () => validOwnDirectory(31, useStore.getState().keypair!));
    const refreshBlocklist = vi.fn(async () => undefined);
    const syncBlockRules = vi.fn(async () => undefined);
    const refreshConversations = vi.fn(async () => undefined);
    useStore.setState({ refreshBlocklist, syncBlockRules, refreshConversations });

    const old = __testing.postLogin();
    await vi.waitFor(() => expect(api.keyDirectory).toHaveBeenCalledOnce());
    authenticated(31);
    useStore.setState({ refreshBlocklist, syncBlockRules, refreshConversations });
    const fresh = __testing.postLogin();
    await fresh;
    expect(api.keyDirectory).toHaveBeenCalledTimes(2);
    expect(effects.getSocket).toHaveBeenCalledOnce();

    oldDirectory.reject(new Error("late failure"));
    await old;
    expect(useStore.getState()).toMatchObject({ securityGeneration: 31, securityLocked: false });
    expect(effects.getSocket).toHaveBeenCalledOnce();
  });

  it("allows the same context to retry after setup rejects", async () => {
    const keypair = authenticated(32);
    vi.spyOn(api, "keyDirectory").mockImplementation(async () => validOwnDirectory(32, keypair));
    const refreshBlocklist = vi.fn()
      .mockRejectedValueOnce(new Error("indexeddb unavailable"))
      .mockResolvedValue(undefined);
    const syncBlockRules = vi.fn(async () => undefined);
    const refreshConversations = vi.fn(async () => undefined);
    useStore.setState({ refreshBlocklist, syncBlockRules, refreshConversations });

    await expect(__testing.postLogin()).rejects.toThrow("indexeddb unavailable");
    await expect(__testing.postLogin()).resolves.toBeUndefined();

    expect(api.keyDirectory).toHaveBeenCalledTimes(2);
    expect(effects.getSocket).toHaveBeenCalledOnce();
    expect(effects.socketOn).toHaveBeenCalledTimes(9);
  });

  it("finishes a queued logout clear before installing a new session", async () => {
    authenticated(40);
    const clearing = deferred<void>();
    effects.clearSessionData.mockReturnValueOnce(clearing.promise);
    vi.spyOn(api, "logout").mockResolvedValue({ ok: true });

    const logout = useStore.getState().logout();
    await vi.waitFor(() => expect(effects.clearSessionData).toHaveBeenCalledOnce());

    const keypair = generateKeypair();
    effects.getMeta.mockResolvedValue({
      username: "new-user", uid: 41, sid: "sid-41", deviceName: "new-device", keypair,
    });
    vi.spyOn(api, "deviceLogin").mockResolvedValue({
      ok: true, uid: 41, sid: "sid-41", trust_state: "pending",
      challenge_id: "challenge-id", challenge: b64u(new Uint8Array(32)), session_version: 1,
    });
    vi.spyOn(api, "deviceLoginProof").mockResolvedValue({
      ok: true, token: "token-41", trust_state: "pending",
    });
    const login = useStore.getState().loginExistingDevice("new-user", "password");
    await vi.waitFor(() => expect(api.deviceLogin).toHaveBeenCalledOnce());
    expect(effects.setMeta).not.toHaveBeenCalled();

    clearing.resolve();
    await expect(login).resolves.toBe(true);
    await logout;

    expect(effects.clearSessionData.mock.invocationCallOrder[0])
      .toBeLessThan(effects.setMeta.mock.invocationCallOrder[0]);
    expect(useStore.getState()).toMatchObject({
      authed: true, username: "new-user", uid: 41, sid: "sid-41",
    });
    expect(api.token).toBe("token-41");
  });

  it("uses trust-preserving cleanup before revoked-device re-registration", async () => {
    const oldKeypair = generateKeypair();
    effects.getMeta.mockResolvedValue({
      username: "recovery_user", uid: 42, sid: "revoked-sid", deviceName: "revoked-device",
      keypair: oldKeypair,
    });
    vi.spyOn(api, "login").mockResolvedValue({ ok: true });
    vi.spyOn(api, "deviceLogin").mockResolvedValue({ ok: false, error: "device revoked" });
    vi.spyOn(api, "deviceRegister").mockResolvedValue({
      ok: true, uid: 42, sid: "replacement-sid", token: "replacement-token", trust_state: "pending",
    });

    await expect(useStore.getState().login("recovery_user", "password")).resolves.toBe(true);

    expect(effects.clearDeviceForReregistration).toHaveBeenCalledOnce();
    expect(effects.setMeta).toHaveBeenCalledWith(expect.objectContaining({
      username: "recovery_user", uid: 42, sid: "replacement-sid",
    }));
    expect(useStore.getState()).toMatchObject({
      authed: true, approvalPending: true, sid: "replacement-sid",
    });
  });

  it("drains an already-started message write before logout clears it", async () => {
    authenticated(50);
    const writing = deferred<void>();
    effects.putMessage.mockReturnValueOnce(writing.promise);
    vi.spyOn(api, "convMembers").mockResolvedValue(validDirectory(member(50, "sid-50")));
    effects.encrypt.mockResolvedValue({ v: 1, ciphertext: "ciphertext", recipients: {} });
    effects.sendMessage.mockResolvedValue({ ok: true, seq: 1 });
    vi.spyOn(api, "logout").mockResolvedValue({ ok: true });

    const sending = useStore.getState().send("cid", "secret");
    await vi.waitFor(() => expect(effects.putMessage).toHaveBeenCalledOnce());
    const logout = useStore.getState().logout();
    await Promise.resolve();
    expect(effects.clearSessionData).not.toHaveBeenCalled();

    writing.resolve();
    await expect(sending).resolves.toBe(false);
    await logout;

    expect(effects.putMessage.mock.invocationCallOrder[0])
      .toBeLessThan(effects.clearSessionData.mock.invocationCallOrder[0]);
    expect(useStore.getState().authed).toBe(false);
  });

  it("does not install a stale device-login response after invalidation", async () => {
    const keypair = generateKeypair();
    effects.getMeta.mockResolvedValue({
      username: "stale-user", uid: 60, sid: "sid-60", deviceName: "stale-device", keypair,
    });
    const response = deferred<any>();
    vi.spyOn(api, "deviceLogin").mockReturnValue(response.promise);

    const login = useStore.getState().loginExistingDevice("stale-user", "password");
    await vi.waitFor(() => expect(api.deviceLogin).toHaveBeenCalledOnce());
    const logout = useStore.getState().logout();
    response.resolve({ ok: true, token: "stale-token", trust_state: "pending" });

    await expect(login).resolves.toBe(false);
    await logout;
    expect(effects.setMeta).not.toHaveBeenCalled();
    expect(api.token).toBeNull();
    expect(useStore.getState().authed).toBe(false);
  });
});
