import { afterEach, describe, expect, it, vi } from "vitest";
import { Api } from "./api";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("Api 401 handling", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("fires onUnauthorized when an authenticated request gets 401", async () => {
    const fetchMock = vi.fn(async () => jsonResponse(401, { ok: false, error: "invalid token" }));
    vi.stubGlobal("fetch", fetchMock);
    const api = new Api();
    api.setToken("jwt-token");
    const onUnauthorized = vi.fn();
    api.onUnauthorized = onUnauthorized;

    const result = await api.listConversations();

    expect(result.ok).toBe(false);
    expect(result.status).toBe(401);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it("does not fire onUnauthorized for bootstrap calls without a token", async () => {
    // Wrong password returns 401 from /login; that must NOT trigger logout.
    const fetchMock = vi.fn(async () => jsonResponse(401, { ok: false, error: "invalid credentials" }));
    vi.stubGlobal("fetch", fetchMock);
    const api = new Api();
    const onUnauthorized = vi.fn();
    api.onUnauthorized = onUnauthorized;

    const result = await api.login("alice", "hash");

    expect(result.ok).toBe(false);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it("does not fire onUnauthorized for non-401 failures", async () => {
    const fetchMock = vi.fn(async () => jsonResponse(500, { ok: false, error: "boom" }));
    vi.stubGlobal("fetch", fetchMock);
    const api = new Api();
    api.setToken("jwt-token");
    const onUnauthorized = vi.fn();
    api.onUnauthorized = onUnauthorized;

    const result = await api.listConversations();

    expect(result.ok).toBe(false);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it("returns a structured error instead of throwing on network failure", async () => {
    const fetchMock = vi.fn(async () => {
      throw new TypeError("fetch failed");
    });
    vi.stubGlobal("fetch", fetchMock);
    const api = new Api();
    api.setToken("jwt-token");

    const result = await api.listConversations();

    expect(result.ok).toBe(false);
    expect(result.status).toBe(0);
    expect(result.error).toBeTruthy();
  });

  it("posts logout with the current bearer token", async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => jsonResponse(200, { ok: true }));
    vi.stubGlobal("fetch", fetchMock);
    const api = new Api();
    api.setToken("jwt-token");

    const result = await api.logout();

    expect(result.ok).toBe(true);
    expect(fetchMock).toHaveBeenCalledWith("/api/logout", expect.objectContaining({
      method: "POST",
      headers: expect.objectContaining({ Authorization: "Bearer jwt-token" }),
    }));
  });

  it("does not recursively fire onUnauthorized when logout itself gets 401", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(401, { ok: false, error: "invalid token" })));
    const api = new Api();
    api.setToken("expired-token");
    const onUnauthorized = vi.fn();
    api.onUnauthorized = onUnauthorized;

    const result = await api.logout();

    expect(result.ok).toBe(false);
    expect(result.status).toBe(401);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });
});

describe("trusted-device API contract", () => {
  afterEach(() => { vi.unstubAllGlobals(); });

  it("posts the server's exact approval field names", async () => {
    const fetchMock = vi.fn(async () => jsonResponse(200, { ok: true }));
    vi.stubGlobal("fetch", fetchMock);
    const client = new Api();
    client.setToken("approved-device-token");

    await client.deviceApprove("subject-sid", "covered-challenge", 12, "detached-signature");

    expect(fetchMock).toHaveBeenCalledWith("/api/device-approve", expect.objectContaining({
      method: "POST",
      body: JSON.stringify({
        subject_sid: "subject-sid",
        parent_epoch: 12,
        signature: "detached-signature",
      }),
    }));
  });

  it("uses pending-only status and approved key-directory routes", async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) =>
      jsonResponse(200, { ok: true }));
    vi.stubGlobal("fetch", fetchMock);
    const client = new Api();
    client.setToken("token");

    await client.deviceApprovalStatus();
    await client.keyDirectory();

    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      "/api/device-pending-status",
      "/api/key-directory",
    ]);
  });

  it("posts signed revoke fields and supports pending self-cancel", async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL) => jsonResponse(200, { ok: true }));
    vi.stubGlobal("fetch", fetchMock);
    const client = new Api();
    client.setToken("token");

    await client.deviceRevoke("subject", 9, "signed-revoke");
    await client.pendingDeviceRevoke();
    await client.deviceRejectPending("pending", "challenge", 9);
    expect(fetchMock).toHaveBeenNthCalledWith(1, "/api/device-revoke", expect.objectContaining({
      body: JSON.stringify({
        sid: "subject", parent_epoch: 9, signature: "signed-revoke", reason: "user_revoked",
      }),
    }));
    expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/device-pending-revoke", expect.objectContaining({
      body: "{}",
    }));
    expect(fetchMock).toHaveBeenNthCalledWith(3, "/api/device-reject-pending", expect.objectContaining({
      body: JSON.stringify({ sid: "pending", challenge: "challenge", parent_epoch: 9 }),
    }));
  });
});
