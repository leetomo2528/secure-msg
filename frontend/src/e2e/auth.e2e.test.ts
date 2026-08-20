/// <reference types="node" />
import "fake-indexeddb/auto";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import { spawn, type ChildProcess } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { initCrypto } from "../crypto/keys";
import { clearAllData, getMeta } from "../store/db";
import { useStore } from "../store/useStore";
import { registerViaEmail } from "./registerViaEmail";

/**
 * End-to-end auth test against a REAL local Flask relay server.
 *
 * Why this exists: three production bugs in a row (missing Argon2 in the
 * libsodium npm build, standard-vs-url base64 wire format, IndexedDB
 * out-of-line key) all lived at layer boundaries that mock-based unit tests
 * never crossed. This suite drives the actual store actions over real HTTP,
 * real Argon2id hashing, real bcrypt verification, and real IndexedDB
 * persistence, so any future break in the register/login chain fails here.
 *
 * The Socket.IO realtime layer is stubbed (it needs a browser URL); auth and
 * the REST sync it triggers run for real.
 */

const SERVER_PORT = 5099;
const BASE = `http://127.0.0.1:${SERVER_PORT}`;
const SERVER_DIR = path.resolve(__dirname, "../../../server");
const PYTHON = path.join(SERVER_DIR, ".venv", "bin", "python");
const PASSWORD = "Ab1!가나다라마바사"; // 12 chars: letters+digits+symbols+Korean

const fakeSocket = {
  on: () => {}, off: () => {}, once: () => {},
  emit: () => fakeSocket, connect: () => fakeSocket, disconnect: () => {},
  connected: false, id: "e2e-socket",
};

vi.mock("../net/api", async (importOriginal) => {
  const mod = await importOriginal<typeof import("../net/api")>();
  return {
    ...mod,
    getSocket: () => fakeSocket,
    disconnectSocket: () => {},
    waitForSocketConnected: async () => {},
    sendMessage: async () => ({ ok: true }),
  };
});

let serverProc: ChildProcess;
let outbox: string;
const realFetch = globalThis.fetch.bind(globalThis);

beforeAll(async () => {
  await initCrypto();

  // Route the api module's relative "/api..." URLs at the local server.
  vi.stubGlobal("fetch", (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" && input.startsWith("/") ? BASE + input : input;
    return realFetch(url as RequestInfo | URL, init);
  });

  const tmp = mkdtempSync(path.join(tmpdir(), "securemsg-e2e-"));
  outbox = path.join(tmp, "outbox.jsonl");
  serverProc = spawn(PYTHON, ["app.py"], {
    cwd: SERVER_DIR,
    env: {
      ...process.env,
      SECUREMSG_ENV: "development",
      SECUREMSG_HOST: "127.0.0.1",
      SECUREMSG_PORT: String(SERVER_PORT),
      SECUREMSG_DB: path.join(tmp, "e2e.db"),
      SECUREMSG_JWT_SECRET: "e2e-only-secret-" + "x".repeat(48),
      SECUREMSG_CORS: "http://localhost:5173",
      // Registration is email-verified only; the console provider writes the
      // code to a file this test reads instead of sending mail.
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
  serverProc?.kill();
});

describe("auth end-to-end against a real relay server", () => {
  it("register: mixed-charset 8+ char password, device meta persisted", async () => {
    const ok = await registerViaEmail(outbox, "e2e_alice", PASSWORD);
    expect(useStore.getState().error).toBeNull();
    expect(ok).toBe(true);
    expect(useStore.getState().authed).toBe(true);
    const meta = await getMeta();
    expect(meta?.username).toBe("e2e_alice");
    expect(meta?.keypair.box.sk).toBeTruthy();
  }, 60_000);

  it("logout + login reuses the locally stored device", async () => {
    const sidBefore = (await getMeta())?.sid;
    await useStore.getState().logout();
    expect(useStore.getState().authed).toBe(false);

    const ok = await useStore.getState().login("e2e_alice", PASSWORD);
    expect(useStore.getState().error).toBeNull();
    expect(ok).toBe(true);
    expect((await getMeta())?.sid).toBe(sidBefore);
  }, 60_000);

  it("fresh browser (no meta) logs in by registering a second device", async () => {
    await useStore.getState().logout();
    await clearAllData(); // simulate a brand-new browser profile
    expect(await getMeta()).toBeNull();

    const ok = await useStore.getState().login("e2e_alice", PASSWORD);
    expect(useStore.getState().error).toBeNull();
    expect(ok).toBe(true);
    expect((await getMeta())?.username).toBe("e2e_alice");
  }, 60_000);

  it("wrong password is rejected by the server, not by local validation", async () => {
    await useStore.getState().logout();
    const ok = await useStore.getState().login("e2e_alice", "WrongPw1!가나다라마바");
    expect(ok).toBe(false);
    expect(useStore.getState().authed).toBe(false);
    expect(useStore.getState().error).toBeTruthy();
  }, 60_000);

  it("duplicate registration is rejected with the server error", async () => {
    await clearAllData(); // fresh-browser register path reaches the server
    const ok = await registerViaEmail(outbox, "e2e_alice", PASSWORD);
    expect(ok).toBe(false);
    expect(useStore.getState().error).toContain("already");
  }, 60_000);
});
