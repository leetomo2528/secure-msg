import "fake-indexeddb/auto";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";

const effects = vi.hoisted(() => ({
  getMeta: vi.fn(),
  setMeta: vi.fn(),
  cacheDevice: vi.fn(),
  clearDeviceForReregistration: vi.fn(),
  clearSessionData: vi.fn(),
}));

vi.mock("./db", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./db")>();
  return {
    ...actual,
    getMeta: effects.getMeta,
    setMeta: effects.setMeta,
    cacheDevice: effects.cacheDevice,
    clearDeviceForReregistration: effects.clearDeviceForReregistration,
    clearSessionData: effects.clearSessionData,
  };
});

vi.mock("../net/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../net/api")>();
  return { ...actual, disconnectSocket: vi.fn(), getSocket: vi.fn() };
});

import { b64u, generateKeypair, initCrypto, signDetached } from "../crypto/keys";
import { api, canonicalDeviceLoginProof } from "../net/api";
import { useStore } from "./useStore";

const PASSWORD = "correct horse 배터리";

/**
 * A brand-new device cannot read anything sent before it registered, so the
 * registration is gated behind an explicit confirmation. Reusing the device
 * stored in this browser keeps history and must stay silent.
 */
describe("new-device registration warning", () => {
  beforeAll(async () => { await initCrypto(); });

  afterEach(() => {
    vi.restoreAllMocks();
    for (const effect of Object.values(effects)) effect.mockReset();
    effects.setMeta.mockResolvedValue(undefined);
    effects.cacheDevice.mockResolvedValue(undefined);
    effects.clearDeviceForReregistration.mockResolvedValue(undefined);
    effects.clearSessionData.mockResolvedValue(undefined);
    api.setToken(null);
    useStore.setState({
      authed: false, approvalPending: false, securityLocked: false,
      securityGeneration: 0, username: null, uid: null, sid: null,
      keypair: null, pendingNewDevice: null, error: null,
    });
  });

  it("warns instead of registering when this browser has no device for the account", async () => {
    effects.getMeta.mockResolvedValue(null);
    vi.spyOn(api, "login").mockResolvedValue({ ok: true, uid: 7, username: "alice" });
    const register = vi.spyOn(api, "deviceRegister");

    await expect(useStore.getState().login("alice", PASSWORD)).resolves.toBe(false);

    expect(register).not.toHaveBeenCalled();
    expect(useStore.getState().pendingNewDevice).toEqual({ username: "alice", reason: "fresh" });
    // Not an error state: nothing failed, the user simply has not decided yet.
    expect(useStore.getState().error).toBeNull();
    expect(useStore.getState().authed).toBe(false);
  });

  it("registers the new device only after the warning is confirmed", async () => {
    effects.getMeta.mockResolvedValue(null);
    vi.spyOn(api, "login").mockResolvedValue({ ok: true, uid: 7, username: "alice" });
    const register = vi.spyOn(api, "deviceRegister").mockResolvedValue({
      ok: true, uid: 7, sid: "new-sid", token: "new-token", trust_state: "pending",
    });

    await useStore.getState().login("alice", PASSWORD);
    await expect(useStore.getState().confirmNewDevice(PASSWORD)).resolves.toBe(true);

    expect(register).toHaveBeenCalledOnce();
    expect(effects.setMeta).toHaveBeenCalledWith(expect.objectContaining({
      username: "alice", uid: 7, sid: "new-sid",
    }));
    expect(useStore.getState()).toMatchObject({
      authed: true, approvalPending: true, sid: "new-sid", pendingNewDevice: null,
    });
  });

  it("registers nothing when the warning is dismissed", async () => {
    effects.getMeta.mockResolvedValue(null);
    vi.spyOn(api, "login").mockResolvedValue({ ok: true, uid: 7, username: "alice" });
    const register = vi.spyOn(api, "deviceRegister");

    await useStore.getState().login("alice", PASSWORD);
    useStore.getState().cancelNewDevice();

    expect(useStore.getState().pendingNewDevice).toBeNull();
    await expect(useStore.getState().confirmNewDevice(PASSWORD)).resolves.toBe(false);
    expect(register).not.toHaveBeenCalled();
  });

  it("defers the revoked-device re-registration, including its local key wipe", async () => {
    const keypair = generateKeypair();
    effects.getMeta.mockResolvedValue({
      username: "bob", uid: 8, sid: "revoked-sid", deviceName: "old", keypair,
    });
    vi.spyOn(api, "login").mockResolvedValue({ ok: true, uid: 8, username: "bob" });
    vi.spyOn(api, "deviceLogin").mockResolvedValue({ ok: false, status: 404, error: "device not found" });
    const register = vi.spyOn(api, "deviceRegister").mockResolvedValue({
      ok: true, uid: 8, sid: "replacement-sid", token: "replacement-token", trust_state: "pending",
    });

    await expect(useStore.getState().login("bob", PASSWORD)).resolves.toBe(false);

    expect(useStore.getState().pendingNewDevice).toEqual({ username: "bob", reason: "replaced" });
    // The stored keypair survives until the user accepts losing history access.
    expect(effects.clearDeviceForReregistration).not.toHaveBeenCalled();
    expect(register).not.toHaveBeenCalled();

    await expect(useStore.getState().confirmNewDevice(PASSWORD)).resolves.toBe(true);
    expect(effects.clearDeviceForReregistration).toHaveBeenCalledOnce();
    expect(useStore.getState()).toMatchObject({ sid: "replacement-sid", pendingNewDevice: null });
  });

  it("never warns when the stored device is reused", async () => {
    const keypair = generateKeypair();
    const meta = { username: "carol", uid: 9, sid: "stored-sid", deviceName: "this", keypair };
    effects.getMeta.mockResolvedValue(meta);
    vi.spyOn(api, "login").mockResolvedValue({ ok: true, uid: 9, username: "carol" });
    const challenge = b64u(new Uint8Array(32));
    vi.spyOn(api, "deviceLogin").mockResolvedValue({
      ok: true, uid: 9, sid: "stored-sid", challenge_id: "challenge-id",
      challenge, session_version: 1,
    });
    const proof = vi.spyOn(api, "deviceLoginProof").mockResolvedValue({
      ok: true, token: "stored-token", trust_state: "pending",
    });
    const register = vi.spyOn(api, "deviceRegister");

    await expect(useStore.getState().login("carol", PASSWORD)).resolves.toBe(true);

    expect(useStore.getState().pendingNewDevice).toBeNull();
    expect(register).not.toHaveBeenCalled();
    expect(useStore.getState()).toMatchObject({ authed: true, sid: "stored-sid" });
    // The reused device proves possession of the stored signing key; history
    // stays readable precisely because that keypair was never replaced.
    expect(proof).toHaveBeenCalledWith(
      "carol", expect.any(String), "stored-sid", "challenge-id", challenge,
      signDetached(canonicalDeviceLoginProof({
        uid: 9, sid: "stored-sid", challenge_id: "challenge-id",
        challenge, session_version: 1,
      }), keypair.sign.sk),
    );
  });
});
