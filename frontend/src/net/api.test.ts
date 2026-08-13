import { afterEach, describe, expect, it, vi } from "vitest";
import { Api, canonicalDeviceLoginProof, sendMessage } from "./api";

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

  it("preserves a 5xx status when an intermediary returns a non-JSON body", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("Bad Gateway", { status: 502 })));
    const api = new Api();
    api.setToken("jwt-token");

    const result = await api.keyDirectory();

    expect(result).toMatchObject({
      ok: false,
      status: 502,
      error: "서버 응답 형식 오류 (HTTP 502)",
    });
  });

  it("ignores a delayed 401 from a previous login session", async () => {
    let resolveOldRequest!: (response: Response) => void;
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => {
      resolveOldRequest = resolve;
    }));
    vi.stubGlobal("fetch", fetchMock);
    const api = new Api();
    api.setToken("old-token");
    const onUnauthorized = vi.fn();
    api.onUnauthorized = onUnauthorized;

    const oldRequest = api.listConversations();
    api.setToken("new-token");
    resolveOldRequest(jsonResponse(401, { ok: false, error: "old token expired" }));
    const result = await oldRequest;

    expect(result.status).toBe(401);
    expect(onUnauthorized).not.toHaveBeenCalled();
    expect(api.token).toBe("new-token");
    expect(fetchMock).toHaveBeenCalledWith("/api/conversations", expect.objectContaining({
      headers: expect.objectContaining({ Authorization: "Bearer old-token" }),
    }));
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

describe("sendMessage cancellation", () => {
  it("does not emit a retry after the security context is invalidated", async () => {
    vi.useFakeTimers();
    let current = true;
    const socket = { emit: vi.fn() } as any;

    const pending = sendMessage(socket, "cid", {} as any, () => current);
    expect(socket.emit).toHaveBeenCalledTimes(1);
    current = false;
    await vi.advanceTimersByTimeAsync(10_000);

    await expect(pending).resolves.toMatchObject({ ok: false, error: "메시지 전송이 취소되었습니다" });
    expect(socket.emit).toHaveBeenCalledTimes(1);
    vi.useRealTimers();
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

  it("uses the canonical domain-separated login proof and posts every bound field", async () => {
    expect(canonicalDeviceLoginProof({
      uid: 7, sid: "device01", challenge_id: "challenge-id", challenge: "nonce", session_version: 4,
    })).toBe("securemsg-device-login-v1\nuid=7\nsid=device01\nchallenge_id=challenge-id\nchallenge=nonce\nsession_version=4\n");
    const fetchMock = vi.fn(async () => jsonResponse(200, { ok: true, token: "jwt" }));
    vi.stubGlobal("fetch", fetchMock);
    const client = new Api();
    await client.deviceLoginProof("alice", "hash", "device01", "challenge-id", "nonce", "signature");
    expect(fetchMock).toHaveBeenCalledWith("/api/device-login", expect.objectContaining({
      body: JSON.stringify({
        username: "alice", pw_hash: "hash", sid: "device01",
        challenge_id: "challenge-id", challenge: "nonce", proof: "signature",
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
