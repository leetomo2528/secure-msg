import "fake-indexeddb/auto";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import {
  b64u,
  decryptMessageWithSender,
  encryptMessage,
  generateKeypair,
  initCrypto,
  rewrapMessageKey,
  type DeviceKeypair,
  type Envelope,
} from "../crypto/keys";
import {
  canonicalDeviceApproval,
  deviceFingerprint,
  recipientKeysetHash,
  serverDirectoryHash,
  signDeviceApproval,
} from "../crypto/deviceTrust";
import {
  api,
  type ConvMember,
  type ConversationMembersResult,
  type ServerMessage,
  type ShareKeyEntry,
} from "../net/api";
import {
  getCursor,
  getUndecryptableFloor,
  listMessages,
  pinTrustedDirectory,
} from "./db";
import { __testing, useStore } from "./useStore";
import { shareHistoryWithDevice } from "./historyShare";

vi.mock("../net/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../net/api")>();
  return { ...actual, getSocket: vi.fn(), disconnectSocket: vi.fn() };
});

const CHALLENGE = b64u(new Uint8Array(32));

interface Account {
  uid: number;
  mySid: string;
  targetSid: string;
  mine: DeviceKeypair;
  target: DeviceKeypair;
  members: ConvMember[];
  directoryHash: string;
}

/**
 * One account with two approved web devices: the signed-in sharer and a device
 * approved later. The approval certificate is real, so the fixture passes the
 * same verification the app runs before touching any envelope.
 */
function account(uid: number): Account {
  const mine = generateKeypair();
  const target = generateKeypair();
  const mySid = `web-${uid}-a`;
  const targetSid = `web-${uid}-b`;
  const members: ConvMember[] = [
    { user_id: uid, device_id: uid * 10, sid: mySid, pub_key: mine.box.pk, sig_pub: mine.sign.pk, kind: "web" },
    { user_id: uid, device_id: uid * 10 + 1, sid: targetSid, pub_key: target.box.pk, sig_pub: target.sign.pk, kind: "web" },
  ];
  return {
    uid, mySid, targetSid, mine, target, members,
    directoryHash: serverDirectoryHash(members),
  };
}

function pinnedSnapshot(a: Account, devices = a.members) {
  return {
    uid: a.uid,
    identity_sig_pub: a.mine.sign.pk,
    security_epoch: devices.length > 1 ? 2 : 1,
    directory_hash: serverDirectoryHash(devices),
    security_mode: "verified_v2" as const,
    devices: devices.map((device) => ({
      sid: device.sid,
      pub_key: device.pub_key,
      sig_pub: device.sig_pub,
      kind: device.kind,
      fingerprint: deviceFingerprint(device.pub_key, device.sig_pub).hash,
    })),
  };
}

function conversationDirectory(a: Account): ConversationMembersResult {
  const history = a.members.map((device) => ({
    sid: device.sid,
    kind: device.kind,
    pub_key: device.pub_key,
    sig_pub: device.sig_pub,
    fingerprint: deviceFingerprint(device.pub_key, device.sig_pub).hash,
    trust_state: "approved" as const,
    challenge: CHALLENGE,
    approved_by_sid: a.mySid,
    verification_state: "verified" as const,
  }));
  const approvalFields = {
    uid: a.uid,
    subjectSid: a.targetSid,
    pubKey: a.target.box.pk,
    sigPub: a.target.sign.pk,
    kind: "web",
    challenge: CHALLENGE,
    parentEpoch: 1,
  };
  return {
    ok: true,
    members: a.members,
    recipient_keyset_hash: recipientKeysetHash(a.members),
    directory_checkpoints: [{
      user_id: a.uid,
      identity_sig_pub: a.mine.sign.pk,
      security_epoch: 2,
      directory_hash: a.directoryHash,
      security_mode: "verified_v2",
    }],
    directory_proofs: [{
      user_id: a.uid,
      identity_sig_pub: a.mine.sign.pk,
      security_epoch: 2,
      directory_hash: a.directoryHash,
      security_mode: "verified_v2",
      device_history: history,
      approval_certificates: [{
        subject_sid: a.targetSid,
        approver_sid: a.mySid,
        parent_epoch: 1,
        resulting_epoch: 2,
        statement: canonicalDeviceApproval(approvalFields),
        signature: signDeviceApproval(approvalFields, a.mine.sign.sk),
        created_at: 1,
      }],
      revocation_certificates: [],
      security_upgrade_certificates: [],
    }],
  };
}

/**
 * A relay holding `total` messages of which `unopenableThrough` lowest ones
 * are addressed to neither of this account's devices, answering missing-keys
 * with a `window`-sized page exactly as the real one does (500 there). The
 * window is what makes the unopenable messages matter: they are never posted,
 * so they occupy the same slots in every later round.
 */
async function relayFixture(
  a: Account,
  cid: string,
  { total, unopenableThrough, window }: { total: number; unopenableThrough: number; window: number },
) {
  const mine = await encryptMessage("과거 대화", [{ sid: a.mySid, pub_key: a.mine.box.pk }], a.mine);
  const foreign = await encryptMessage(
    "다른 기기 것", [{ sid: "ghost-device", pub_key: generateKeypair().box.pk }], a.mine,
  );
  const messages = Array.from({ length: total }, (_, index) => serverMessage(
    cid, index + 1, a, index < unopenableThrough ? foreign : mine,
  ));
  const held = new Set<number>();

  vi.spyOn(api, "listConversations").mockResolvedValue({
    ok: true,
    conversations: [{ cid, conv_id: 1, name: "chat", members: [`user${a.uid}`], created_at: 1 }],
  } as never);
  vi.spyOn(api, "convMembers").mockResolvedValue(conversationDirectory(a));
  vi.spyOn(api, "missingKeys").mockImplementation(async () => ({
    ok: true,
    cid,
    sid: a.targetSid,
    seqs: messages.map((message) => message.seq).filter((seq) => !held.has(seq)).slice(0, window),
  }));
  vi.spyOn(api, "fetchMessages").mockImplementation(async (_cid, since, limit = 200) => ({
    ok: true,
    messages: messages.filter((message) => message.seq > since).slice(0, limit),
  }));
  const shareKeys = vi.spyOn(api, "shareKeys").mockImplementation(async (_cid, _sid, entries) => {
    for (const entry of entries) held.add(entry.seq);
    return { ok: true, added: entries.length, skipped: 0 };
  });
  return { shareKeys };
}

function serverMessage(cid: string, seq: number, a: Account, payload: Envelope): ServerMessage {
  return {
    id: seq,
    seq,
    cid,
    conv_id: 1,
    sender_id: a.uid,
    sender_sid: a.mySid,
    sender_pub_key: a.mine.box.pk,
    payload,
    created_at: 1_700_000_000,
  };
}

function signedIn(a: Account, as: "sharer" | "late" = "sharer", generation = a.uid): void {
  api.setToken(`token-${a.uid}`);
  useStore.setState({
    authed: true,
    approvalPending: false,
    securityLocked: false,
    securityGeneration: generation,
    username: `user${a.uid}`,
    uid: a.uid,
    sid: as === "late" ? a.targetSid : a.mySid,
    keypair: as === "late" ? a.target : a.mine,
    conversations: [],
    activeCid: null,
    activeMessages: [],
    error: null,
  });
}

describe("history key sharing", () => {
  beforeAll(async () => { await initCrypto(); });

  afterEach(() => {
    vi.restoreAllMocks();
    api.setToken(null);
    useStore.setState({
      authed: false, approvalPending: false, securityLocked: false,
      uid: null, sid: null, keypair: null, conversations: [], error: null,
    });
  });

  it("re-wraps history so a device that was not a recipient can read it", async () => {
    const a = account(9001);
    const cid = "conv-share-ok";
    await pinTrustedDirectory(pinnedSnapshot(a));
    signedIn(a);

    // Sent before the second device existed: addressed to this device only.
    const envelope = await encryptMessage("과거 대화", [{ sid: a.mySid, pub_key: a.mine.box.pk }], a.mine);
    expect(decryptMessageWithSender(envelope, a.targetSid, a.target, a.mine.box.pk)).toBeNull();

    vi.spyOn(api, "listConversations").mockResolvedValue({
      ok: true,
      conversations: [{ cid, conv_id: 1, name: "chat", members: [`user${a.uid}`], created_at: 1 }],
    } as never);
    vi.spyOn(api, "convMembers").mockResolvedValue(conversationDirectory(a));
    const missing = vi.spyOn(api, "missingKeys")
      .mockResolvedValueOnce({ ok: true, cid, sid: a.targetSid, seqs: [1] })
      .mockResolvedValue({ ok: true, cid, sid: a.targetSid, seqs: [] });
    vi.spyOn(api, "fetchMessages").mockResolvedValue({
      ok: true, messages: [serverMessage(cid, 1, a, envelope)],
    });
    let posted: ShareKeyEntry[] = [];
    vi.spyOn(api, "shareKeys").mockImplementation(async (_cid, _sid, entries) => {
      posted = entries;
      return { ok: true, added: entries.length, skipped: 0 };
    });
    const progress: number[] = [];

    const outcome = await shareHistoryWithDevice(a.targetSid, (p) => progress.push(p.shared));

    expect(outcome).toMatchObject({ ok: true, shared: 1, skipped: 0, conversationsDone: 1 });
    expect(progress).toContain(1);
    expect(missing).toHaveBeenCalledWith(cid, a.targetSid);
    expect(posted).toHaveLength(1);
    expect(posted[0].seq).toBe(1);

    // Exactly what the relay stores: the sharer's SID stamped as `by`.
    const backfilled: Envelope = {
      ...envelope,
      keys: { ...envelope.keys, [a.targetSid]: { ek: posted[0].ek, n: posted[0].n, by: a.mySid } },
    };
    expect(decryptMessageWithSender(
      backfilled, a.targetSid, a.target, a.mine.box.pk,
      (sid) => (sid === a.mySid ? a.mine.box.pk : null),
    )).toBe("과거 대화");
  });

  it("refuses a target whose key is not pinned locally, without asking the relay", async () => {
    const a = account(9002);
    // Only this browser's own device is pinned; the target never was.
    await pinTrustedDirectory(pinnedSnapshot(a, [a.members[0]]));
    signedIn(a);
    const missing = vi.spyOn(api, "missingKeys");
    const shareKeys = vi.spyOn(api, "shareKeys");
    const conversations = vi.spyOn(api, "listConversations");

    const outcome = await shareHistoryWithDevice(a.targetSid);

    expect(outcome.ok).toBe(false);
    expect(outcome.error).toContain("기기 목록");
    expect(outcome.shared).toBe(0);
    expect(conversations).not.toHaveBeenCalled();
    expect(missing).not.toHaveBeenCalled();
    expect(shareKeys).not.toHaveBeenCalled();
  });

  it("refuses a target the newest verified directory no longer lists", async () => {
    const a = account(9007);
    await pinTrustedDirectory(pinnedSnapshot(a));
    // The user revoked the second device from this browser: the directory that
    // follows lists only this one. The pinned row survives — history that
    // device wrapped must stay readable — so nothing but its revoked mark can
    // stop the share.
    await pinTrustedDirectory({ ...pinnedSnapshot(a, [a.members[0]]), security_epoch: 3 });
    signedIn(a);
    const conversations = vi.spyOn(api, "listConversations");
    const shareKeys = vi.spyOn(api, "shareKeys");

    const outcome = await shareHistoryWithDevice(a.targetSid);

    expect(outcome).toMatchObject({ ok: false, shared: 0 });
    expect(outcome.error).toContain("폐기된 기기");
    expect(conversations).not.toHaveBeenCalled();
    expect(shareKeys).not.toHaveBeenCalled();
  });

  it("counts a message this device cannot open once, not once per round", async () => {
    const a = account(9008);
    const cid = "conv-share-skip-count";
    await pinTrustedDirectory(pinnedSnapshot(a));
    signedIn(a);
    // Two unopenable messages sit in the missing-keys window of all five
    // rounds; the run must report two skipped, not ten.
    await relayFixture(a, cid, { total: 6, unopenableThrough: 2, window: 3 });

    const outcome = await shareHistoryWithDevice(a.targetSid);

    expect(outcome).toMatchObject({ ok: true, shared: 4, skipped: 2, conversationsDone: 1 });
  });

  it("reports an incomplete run when the round cap is reached with work left", async () => {
    const a = account(9009);
    const cid = "conv-share-rounds";
    await pinTrustedDirectory(pinnedSnapshot(a));
    signedIn(a);
    // Two unopenable messages leave one shareable slot per round, so 68
    // shareable messages cannot fit in the 64-round cap.
    const { shareKeys } = await relayFixture(a, cid, { total: 70, unopenableThrough: 2, window: 3 });

    const outcome = await shareHistoryWithDevice(a.targetSid);

    expect(outcome).toMatchObject({ ok: false, shared: 64, skipped: 2 });
    expect(outcome.error).toContain("다시 실행");
    expect(shareKeys).toHaveBeenCalledTimes(64);
  });

  it("skips a message re-wrapped by a device this browser has not pinned", async () => {
    const a = account(9003);
    const cid = "conv-share-unpinned-wrapper";
    await pinTrustedDirectory(pinnedSnapshot(a));
    signedIn(a);

    // Our own copy claims to come from a wrapper that is not in the pinned
    // directory — a relay-named key would be the only way to open it.
    const envelope = await encryptMessage("과거 대화", [{ sid: a.mySid, pub_key: a.mine.box.pk }], a.mine);
    const forged: Envelope = {
      ...envelope,
      keys: { [a.mySid]: { ...envelope.keys[a.mySid], by: "ghost-device" } },
    };
    vi.spyOn(api, "listConversations").mockResolvedValue({
      ok: true,
      conversations: [{ cid, conv_id: 1, name: "chat", members: [`user${a.uid}`], created_at: 1 }],
    } as never);
    vi.spyOn(api, "convMembers").mockResolvedValue(conversationDirectory(a));
    vi.spyOn(api, "missingKeys").mockResolvedValue({ ok: true, cid, sid: a.targetSid, seqs: [1] });
    vi.spyOn(api, "fetchMessages").mockResolvedValue({
      ok: true, messages: [serverMessage(cid, 1, a, forged)],
    });
    const shareKeys = vi.spyOn(api, "shareKeys");

    const outcome = await shareHistoryWithDevice(a.targetSid);

    expect(outcome).toMatchObject({ ok: true, shared: 0, skipped: 1 });
    expect(shareKeys).not.toHaveBeenCalled();
  });

  it("stops sharing when the session is invalidated mid-run", async () => {
    const a = account(9004);
    const cid = "conv-share-logout";
    await pinTrustedDirectory(pinnedSnapshot(a));
    signedIn(a);
    vi.spyOn(api, "listConversations").mockResolvedValue({
      ok: true,
      conversations: [{ cid, conv_id: 1, name: "chat", members: [`user${a.uid}`], created_at: 1 }],
    } as never);
    vi.spyOn(api, "convMembers").mockImplementation(async () => {
      // A logout landing between two awaits must end the run.
      api.setToken(null);
      useStore.setState({ authed: false, securityGeneration: a.uid + 1 });
      return conversationDirectory(a);
    });
    const missing = vi.spyOn(api, "missingKeys");

    const outcome = await shareHistoryWithDevice(a.targetSid);

    expect(outcome.ok).toBe(false);
    expect(missing).not.toHaveBeenCalled();
  });

  it("re-reads a message it once failed to decrypt after the key is shared", async () => {
    // The receiving half: this device pulled the message while it held no key,
    // so its cursor already moved past it. Nothing notifies it that a key
    // arrived later, so the next session has to look again.
    const a = account(9006);
    const cid = "conv-share-receiver";
    await pinTrustedDirectory(pinnedSnapshot(a));
    signedIn(a, "late");
    __testing.resetSyncJobs();

    const envelope = await encryptMessage("놓친 메시지", [{ sid: a.mySid, pub_key: a.mine.box.pk }], a.mine);
    vi.spyOn(api, "convMembers").mockResolvedValue(conversationDirectory(a));
    const fetchMessages = vi.spyOn(api, "fetchMessages").mockResolvedValue({
      ok: true, messages: [serverMessage(cid, 1, a, envelope)],
    });

    await useStore.getState().syncConversation(cid);

    expect(await listMessages(cid)).toEqual([]);
    expect(await getCursor(cid)).toBe(1);
    expect(await getUndecryptableFloor(cid)).toBe(1);

    // The sharer re-wraps it; the relay now serves the same envelope with our
    // key added, stamped with the sharer's SID.
    const shared = rewrapMessageKey(envelope, a.mySid, a.mine, a.mine.box.pk, a.target.box.pk)!;
    fetchMessages.mockImplementation(async (_cid, since) => ({
      ok: true,
      messages: since < 1
        ? [serverMessage(cid, 1, a, { ...envelope, keys: { ...envelope.keys, [a.targetSid]: shared } })]
        : [],
    }));
    // Same session, no reload: share_keys emits no socket event, so the next
    // incoming message is the only trigger there is. The pass above only
    // DISCOVERED the gap, so this one must still get the session's one
    // re-read — otherwise the thread stays empty until the browser restarts.
    await useStore.getState().syncConversation(cid);

    const rows = await listMessages(cid);
    expect(rows.map((row) => row.plaintext)).toEqual(["놓친 메시지"]);
    expect(await getUndecryptableFloor(cid)).toBeNull();
  });

  it("reports the relay error and shares nothing when missing-keys fails", async () => {
    const a = account(9005);
    const cid = "conv-share-error";
    await pinTrustedDirectory(pinnedSnapshot(a));
    signedIn(a);
    vi.spyOn(api, "listConversations").mockResolvedValue({
      ok: true,
      conversations: [{ cid, conv_id: 1, name: "chat", members: [`user${a.uid}`], created_at: 1 }],
    } as never);
    vi.spyOn(api, "convMembers").mockResolvedValue(conversationDirectory(a));
    vi.spyOn(api, "missingKeys").mockResolvedValue({ ok: false, error: "too many requests" });
    const shareKeys = vi.spyOn(api, "shareKeys");

    const outcome = await shareHistoryWithDevice(a.targetSid);

    expect(outcome).toMatchObject({ ok: false, shared: 0, error: "too many requests" });
    expect(shareKeys).not.toHaveBeenCalled();
  });
});
