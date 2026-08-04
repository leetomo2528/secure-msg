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
});
