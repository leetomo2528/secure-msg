/// <reference types="node" />
import "fake-indexeddb/auto";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import { spawn, type ChildProcess } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { io, type Socket } from "socket.io-client";
import { registerViaEmail } from "./registerViaEmail";
import {
  initCrypto, generateKeypair, hashPassword, saltForUser, encryptMessage,
  decryptMessageWithSender,
} from "../crypto/keys";
import { deviceFingerprint, signDeviceApproval } from "../crypto/deviceTrust";
import { api, getSocket, setSocketBase } from "../net/api";
import { db, getCursor, listMessages, pinTrustedDirectory } from "../store/db";
import { useStore } from "../store/useStore";
import { shareHistoryWithDevice } from "../store/historyShare";

/**
 * Relay interlock test: proves the phone→server→web chain end to end.
 *
 * The Android gateway is simulated with raw HTTP + a real Socket.IO client
 * using the exact same envelope encryption the app uses. The web side runs the
 * real store (Argon2id, IndexedDB persistence, socket fan-out handling).
 * If this passes, any missing-message report is a device-side setup problem
 * (default SMS role, network), not a relay code problem.
 */

const PORT = 5098;
const BASE = `http://127.0.0.1:${PORT}`;
const SERVER_DIR = path.resolve(__dirname, "../../../server");
const PYTHON = path.join(SERVER_DIR, ".venv", "bin", "python");
const USERNAME = "relay_user";
const PASSWORD = "Ab1!가나다라마바사";
const PHONE = "+821099990001";

let serverProc: ChildProcess;
let outbox: string;
const realFetch = globalThis.fetch.bind(globalThis);

function fetchJson(url: string, init?: RequestInit): Promise<any> {
  return realFetch(url, init).then(async (r) => ({ status: r.status, ...(await r.json()) }));
}

beforeAll(async () => {
  await initCrypto();
  vi.stubGlobal("fetch", (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" && input.startsWith("/") ? BASE + input : input;
    return realFetch(url as RequestInfo | URL, init);
  });
  setSocketBase(BASE);

  const tmp = mkdtempSync(path.join(tmpdir(), "securemsg-relay-e2e-"));
  outbox = path.join(tmp, "outbox.jsonl");
  serverProc = spawn(PYTHON, ["app.py"], {
    cwd: SERVER_DIR,
    env: {
      ...process.env,
      SECUREMSG_ENV: "development",
      SECUREMSG_HOST: "127.0.0.1",
      SECUREMSG_PORT: String(PORT),
      SECUREMSG_DB: path.join(tmp, "relay.db"),
      SECUREMSG_JWT_SECRET: "relay-e2e-secret-" + "x".repeat(48),
      SECUREMSG_CORS: "http://localhost:5173",
      SECUREMSG_EMAIL_PROVIDER: "console",
      SECUREMSG_EMAIL_OUTBOX: outbox,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  for (let i = 0; i < 80; i++) {
    try {
      const r = await realFetch(`${BASE}/health`);
      if (r.ok) return;
    } catch { /* not up yet */ }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error("local relay server did not become healthy");
}, 60_000);

afterAll(() => {
  vi.unstubAllGlobals();
  setSocketBase(undefined);
  serverProc?.kill();
});

describe("phone ↔ web relay interlock", () => {
  let gwToken = "";
  let gwSid = "";
  let gwSocket: Socket | null = null;
  let gwKeys: ReturnType<typeof generateKeypair> | null = null;
  let smsCid = "";

  it("web registers and connects its realtime socket", async () => {
    const ok = await registerViaEmail(outbox, USERNAME, PASSWORD);
    expect(useStore.getState().error).toBeNull();
    expect(ok).toBe(true);
    // postLogin wires the socket; wait for the actual connection.
    const socket = getSocket(api.token!);
    const deadline = Date.now() + 10_000;
    while (!socket.connected && Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 100));
    }
    expect(socket.connected).toBe(true);
  }, 60_000);

  it("android gateway device registers and opens the SMS conversation", async () => {
    const pwHash = await hashPassword(PASSWORD, saltForUser(USERNAME));
    gwKeys = generateKeypair();
    const reg = await fetchJson(`${BASE}/api/device-register`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        username: USERNAME, pw_hash: pwHash, device_name: "gw-e2e",
        device_kind: "android_gateway",
        pub_key: gwKeys.box.pk, sig_pub: gwKeys.sign.pk,
      }),
    });
    expect(reg.ok).toBe(true);
    gwToken = reg.token;
    gwSid = reg.sid;

    // New devices are intentionally isolated until an already-approved
    // device cross-signs their registration. Complete that trust ceremony
    // here so the remainder of this relay interlock exercises an approved
    // Android gateway rather than bypassing the security model.
    const devices = await fetchJson(`${BASE}/api/devices`, {
      headers: { Authorization: `Bearer ${api.token}` },
    });
    expect(devices.ok).toBe(true);
    const pending = (devices.devices as Array<any>).find((d) => d.sid === gwSid);
    expect(pending?.trust_state).toBe("pending");
    const approver = useStore.getState().keypair;
    expect(approver?.sign?.sk).toBeTruthy();
    const signature = signDeviceApproval({
      uid: devices.uid ?? useStore.getState().uid!,
      subjectSid: gwSid,
      pubKey: gwKeys.box.pk,
      sigPub: gwKeys.sign.pk,
      kind: "android_gateway",
      challenge: pending.challenge,
      parentEpoch: devices.security_epoch,
    }, approver!.sign.sk);
    const approved = await fetchJson(`${BASE}/api/device-approve`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${api.token}` },
      body: JSON.stringify({ subject_sid: gwSid, parent_epoch: devices.security_epoch, signature }),
    });
    expect(approved.ok).toBe(true);

    gwSocket = io(BASE, { auth: { token: gwToken }, transports: ["websocket", "polling"] });
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("gateway socket connect timeout")), 10_000);
      gwSocket!.once("connect", () => { clearTimeout(timer); resolve(); });
    });

    const conv = await fetchJson(`${BASE}/api/conversation`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${gwToken}` },
      body: JSON.stringify({ members: [USERNAME], name: PHONE }),
    });
    expect(conv.ok).toBe(true);
    smsCid = conv.cid;
  }, 60_000);

  it("incoming SMS relayed by the gateway appears on web (decrypted)", async () => {
    // Web picks the conversation first so the live fan-out lands in view.
    await useStore.getState().refreshConversations();
    const conv = useStore.getState().conversations.find((c) => c.name === PHONE);
    expect(conv).toBeTruthy();
    await useStore.getState().selectConversation(conv!.cid);

    // Gateway encrypts the relay content for every member device (same code
    // path as CryptoUtil.encryptMessage on Android).
    const members = await fetchJson(`${BASE}/api/conversation/${smsCid}/members`, {
      headers: { Authorization: `Bearer ${gwToken}` },
    });
    expect(members.ok).toBe(true);
    const recipients = (members.members as Array<{ sid: string; pub_key: string }>)
      .map((m) => ({ sid: m.sid, pub_key: m.pub_key }));
    const content = JSON.stringify({ v: 1, type: "text", text: "휴대폰에서 보낸 문자입니다 📱" });
    const envelope = await encryptMessage(content, recipients, gwKeys!);

    const ack = await new Promise<any>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("message_send ack timeout")), 10_000);
      gwSocket!.emit(
        "message_send",
        { cid: smsCid, mid: "relay-e2e-mid-00000001", payload: envelope },
        (response: any) => { clearTimeout(timer); resolve(response); },
      );
    });
    expect(ack.ok).toBe(true);

    // Web must receive message_new → REST sync → decrypt → display.
    const deadline = Date.now() + 10_000;
    while (Date.now() < deadline) {
      const msgs = useStore.getState().activeMessages;
      if (msgs.some((m) => m.plaintext.includes("휴대폰에서 보낸 문자입니다"))) break;
      await new Promise((r) => setTimeout(r, 150));
    }
    const msgs = useStore.getState().activeMessages;
    const relayed = msgs.find((m) => m.plaintext.includes("휴대폰에서 보낸 문자입니다"));
    expect(relayed).toBeTruthy();
    expect(relayed!.sender_sid).toBe(gwSid);
    expect(relayed!.blocked).toBeFalsy();
  }, 60_000);

  it("re-reads a message the local store lost while the cursor stayed ahead", async () => {
    // Half of a sync that something interrupted: the row is gone from disk,
    // the delivery cursor still says it arrived. clearSessionData() is
    // origin-wide while a security context is per-tab, so a second tab
    // logging out between putMessage and setCursor leaves exactly this. The
    // relay only ever returns `seq > cursor`, so without reconciliation the
    // message is gone for good — notified once, then absent from the thread.
    const before = await listMessages(smsCid);
    const victim = before[before.length - 1];
    expect(victim).toBeTruthy();
    const cursorBefore = await getCursor(smsCid);
    expect(cursorBefore).toBeGreaterThanOrEqual(victim.seq);

    await (await db()).delete("messages", victim.id);
    expect((await listMessages(smsCid)).some((m) => m.seq === victim.seq)).toBe(false);

    await useStore.getState().syncConversation(smsCid);

    const healed = (await listMessages(smsCid)).find((m) => m.seq === victim.seq);
    expect(healed?.plaintext).toBe(victim.plaintext);
    // Healing re-reads below the cursor; it must never rewind it.
    expect(await getCursor(smsCid)).toBe(cursorBefore);
  }, 60_000);

  it("tells a text the gateway received from one the owner sent on the phone", async () => {
    // Both arrive under the gateway's sid, so the sender cannot separate them.
    // The phone's own idempotency key can: "in_<hash>" is minted on exactly one
    // code path, the carrier-receive, and its sends use a UUID.
    const members = await fetchJson(`${BASE}/api/conversation/${smsCid}/members`, {
      headers: { Authorization: `Bearer ${gwToken}` },
    });
    const recipients = (members.members as Array<{ sid: string; pub_key: string }>)
      .map((m) => ({ sid: m.sid, pub_key: m.pub_key }));
    const relay = async (text: string, mid: string): Promise<number> => {
      const envelope = await encryptMessage(
        JSON.stringify({ v: 1, type: "text", text }), recipients, gwKeys!,
      );
      const ack = await new Promise<any>((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error("ack timeout")), 10_000);
        gwSocket!.emit(
          "message_send", { cid: smsCid, mid, payload: envelope },
          (response: any) => { clearTimeout(timer); resolve(response); },
        );
      });
      expect(ack.ok).toBe(true);
      return ack.seq as number;
    };
    const inboundSeq = await relay("상대가 보낸 문자", `in_${"b4c1".repeat(15)}d`);
    const outboundSeq = await relay("내가 폰에서 보낸 문자", "8c2f1a90-4d77-4b21-9f0e-1a2b3c4d5e6f");

    await useStore.getState().syncConversation(smsCid);
    const rows = await listMessages(smsCid);
    expect(rows.find((m) => m.seq === inboundSeq)?.direction).toBe("in");
    expect(rows.find((m) => m.seq === outboundSeq)?.direction).toBe("out");
  }, 60_000);

  it("block keyword added on web is visible to the gateway device", async () => {
    await useStore.getState().addBlock("e2e차단키워드");
    // Server-side shared state (what every other device pulls).
    const list = await fetchJson(`${BASE}/api/blocklist`, {
      headers: { Authorization: `Bearer ${gwToken}` },
    });
    expect(list.ok).toBe(true);
    const values = (list.rules as Array<{ type: string; value: string }>).map((r) => r.value);
    expect(values).toContain("e2e차단키워드");
    // Local store reflects the synced (server) row.
    const local = useStore.getState().blockKeywords.map((k) => k.keyword);
    expect(local).toContain("e2e차단키워드");
  }, 60_000);

  it("blocked sender added on web is visible to the gateway device", async () => {
    await useStore.getState().addBlockedSenderRule("+821077778888");
    const list = await fetchJson(`${BASE}/api/blocklist`, {
      headers: { Authorization: `Bearer ${gwToken}` },
    });
    const senderRules = (list.rules as Array<{ type: string; value: string }>)
      .filter((r) => r.type === "sender");
    expect(senderRules.map((r) => r.value)).toContain("+821077778888");
  }, 60_000);

  it("conversation renamed on web reaches the server", async () => {
    const ok = await useStore.getState().renameConversation(smsCid, "테스트번호");
    expect(ok).toBe(true);
    const listed = await fetchJson(`${BASE}/api/conversations`, {
      headers: { Authorization: `Bearer ${gwToken}` },
    });
    // Renaming a phone thread is a contact label: `name` stays the SMS
    // identity the carrier gate and ownership policy match on.
    const conv = (listed.conversations as Array<{ cid: string; name: string; synced_contact_name?: string | null }>)
      .find((c) => c.cid === smsCid);
    expect(conv?.name).toBe(PHONE);
    expect(conv?.synced_contact_name).toBe("테스트번호");
  }, 60_000);

  it("brand-new conversation from the gateway appears in the web sidebar", async () => {
    // Gateway opens a SECOND phone thread the web has never seen.
    const conv = await fetchJson(`${BASE}/api/conversation`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${gwToken}` },
      body: JSON.stringify({ members: [USERNAME], name: "+821077770002" }),
    });
    expect(conv.ok).toBe(true);
    const members = await fetchJson(`${BASE}/api/conversation/${conv.cid}/members`, {
      headers: { Authorization: `Bearer ${gwToken}` },
    });
    const recipients = (members.members as Array<{ sid: string; pub_key: string }>)
      .map((m) => ({ sid: m.sid, pub_key: m.pub_key }));
    const content = JSON.stringify({ v: 1, type: "text", text: "새 스레드 자동 표시 확인" });
    const envelope = await encryptMessage(content, recipients, gwKeys!);
    const ack = await new Promise<any>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("ack timeout")), 10_000);
      gwSocket!.emit(
        "message_send",
        { cid: conv.cid, mid: "relay-e2e-newthread-0001", payload: envelope },
        (response: any) => { clearTimeout(timer); resolve(response); },
      );
    });
    expect(ack.ok).toBe(true);

    // No manual refresh: message_new must trigger the conversation reload.
    const deadline = Date.now() + 10_000;
    while (Date.now() < deadline) {
      if (useStore.getState().conversations.some((c) => c.name === "+821077770002")) break;
      await new Promise((r) => setTimeout(r, 150));
    }
    expect(useStore.getState().conversations.some((c) => c.name === "+821077770002")).toBe(true);
  }, 60_000);

  it("shares history with a device registered after the messages were sent", async () => {
    // A third device joins the account now. Everything above was encrypted
    // before it existed, so the relay holds no key it can open.
    const pwHash = await hashPassword(PASSWORD, saltForUser(USERNAME));
    const lateKeys = generateKeypair();
    const registered = await fetchJson(`${BASE}/api/device-register`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        username: USERNAME, pw_hash: pwHash, device_name: "late-web-e2e",
        pub_key: lateKeys.box.pk, sig_pub: lateKeys.sign.pk,
      }),
    });
    expect(registered.ok).toBe(true);
    const lateSid: string = registered.sid;
    const lateToken: string = registered.token;

    const devices = await fetchJson(`${BASE}/api/devices`, {
      headers: { Authorization: `Bearer ${api.token}` },
    });
    const pending = (devices.devices as Array<any>).find((d) => d.sid === lateSid);
    const approver = useStore.getState().keypair!;
    const uid = useStore.getState().uid!;
    const approved = await fetchJson(`${BASE}/api/device-approve`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${api.token}` },
      body: JSON.stringify({
        subject_sid: lateSid,
        parent_epoch: devices.security_epoch,
        signature: signDeviceApproval({
          uid, subjectSid: lateSid, pubKey: lateKeys.box.pk, sigPub: lateKeys.sign.pk,
          kind: "web", challenge: pending.challenge, parentEpoch: devices.security_epoch,
        }, approver.sign.sk),
      }),
    });
    expect(approved.ok).toBe(true);

    // What DeviceManager does after approving: re-pin the verified directory,
    // which is the only place shareHistoryWithDevice will take a key from.
    const directory = await fetchJson(`${BASE}/api/key-directory`, {
      headers: { Authorization: `Bearer ${api.token}` },
    });
    await pinTrustedDirectory({
      uid,
      identity_sig_pub: directory.identity_sig_pub,
      security_epoch: directory.security_epoch,
      directory_hash: directory.directory_hash,
      security_mode: directory.security_mode,
      devices: (directory.devices as Array<any>).map((device) => ({
        sid: device.sid,
        pub_key: device.pub_key,
        sig_pub: device.sig_pub,
        kind: device.kind,
        fingerprint: deviceFingerprint(device.pub_key, device.sig_pub).hash,
      })),
    });

    const before = await fetchJson(
      `${BASE}/api/conversation/${smsCid}/missing-keys?sid=${lateSid}`,
      { headers: { Authorization: `Bearer ${api.token}` } },
    );
    expect(before.ok).toBe(true);
    expect(before.seqs.length).toBeGreaterThan(0);

    const outcome = await shareHistoryWithDevice(lateSid);
    expect(outcome.error).toBeUndefined();
    expect(outcome.ok).toBe(true);
    expect(outcome.shared).toBeGreaterThanOrEqual(before.seqs.length);

    // Idempotent: a second run has nothing left to add.
    const after = await fetchJson(
      `${BASE}/api/conversation/${smsCid}/missing-keys?sid=${lateSid}`,
      { headers: { Authorization: `Bearer ${api.token}` } },
    );
    expect(after.seqs).toEqual([]);
    expect((await shareHistoryWithDevice(lateSid)).shared).toBe(0);

    // The late device now reads the SMS that arrived before it registered,
    // opening the re-wrapped key with the SHARER's pinned public key.
    const history = await fetchJson(
      `${BASE}/api/conversation/${smsCid}/messages?since=0&limit=200`,
      { headers: { Authorization: `Bearer ${lateToken}` } },
    );
    const relayed = (history.messages as Array<any>).find(
      (message) => message.sender_sid === gwSid,
    );
    expect(relayed).toBeTruthy();
    expect(relayed.payload.keys[lateSid].by).toBe(useStore.getState().sid);
    const sharerPubKey = (directory.devices as Array<any>)
      .find((device) => device.sid === useStore.getState().sid).pub_key;
    const plaintext = decryptMessageWithSender(
      relayed.payload, lateSid, lateKeys, gwKeys!.box.pk,
      (sid) => (sid === useStore.getState().sid ? sharerPubKey : null),
    );
    expect(plaintext).toContain("휴대폰에서 보낸 문자입니다");
  }, 60_000);

  afterAll(() => {
    gwSocket?.disconnect();
  });
});
