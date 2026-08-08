import { describe, expect, it } from "vitest";
import { normalizePhone, ownedSmsPhone } from "./conversationPolicy";

describe("normalizePhone", () => {
  it("maps Korean local and +82 mobile numbers to one identity", () => {
    expect(normalizePhone("01012345678")).toBe("+821012345678");
    expect(normalizePhone("01012345678")).toBe(normalizePhone("+821012345678"));
    expect(normalizePhone("00821012345678")).toBe("+821012345678");
    expect(normalizePhone("821012345678")).toBe("+821012345678");
    expect(normalizePhone("+8201012345678")).toBe("+821012345678");
  });

  it("canonicalizes Korean landlines", () => {
    expect(normalizePhone("02-1234-5678")).toBe("+82212345678");
    expect(normalizePhone("031-1234-5678")).toBe("+823112345678");
  });

  it("canonicalizes Korean 050x virtual numbers", () => {
    expect(normalizePhone("0507-1234-5678")).toBe("+8250712345678");
    expect(normalizePhone("+82 507 1234 5678")).toBe("+8250712345678");
  });

  it("preserves service codes and non-Korean international numbers", () => {
    expect(normalizePhone("*1234#")).toBe("*1234#");
    expect(normalizePhone("1588-1234")).toBe("15881234");
    expect(normalizePhone("+1 202-555-0123")).toBe("+12025550123");
    expect(normalizePhone("0012025550123")).toBe("+12025550123");
  });
});

describe("ownedSmsPhone", () => {
  it("accepts a self-only phone conversation", () => {
    expect(ownedSmsPhone(
      { name: "+82 10-1234-5678", members: ["alice"] },
      "alice",
    )).toBe("+821012345678");
  });

  it("returns the same identity for a legacy Korean local label", () => {
    expect(ownedSmsPhone(
      { name: "01012345678", members: ["alice"] },
      "alice",
    )).toBe("+821012345678");
  });

  it("rejects a group that could abuse the Android gateway", () => {
    expect(ownedSmsPhone(
      { name: "+821012345678", members: ["alice", "mallory"] },
      "alice",
    )).toBeNull();
  });
});
