/// <reference types="node" />
import "fake-indexeddb/auto";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import { spawn, type ChildProcess } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { io, type Socket } from "socket.io-client";
import { registerViaEmail } from "./registerViaEmail";
import { initCrypto, generateKeypair, hashPassword, saltForUser, encryptMessage } from "../crypto/keys";
import { signDeviceApproval } from "../crypto/deviceTrust";
import { api, getSocket, setSocketBase } from "../net/api";
import { useStore } from "../store/useStore";

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
    const names = (listed.conversations as Array<{ name: string }>).map((c) => c.name);
    expect(names).toContain("테스트번호");
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

  afterAll(() => {
    gwSocket?.disconnect();
  });
});
