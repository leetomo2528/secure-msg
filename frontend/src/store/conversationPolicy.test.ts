import { describe, expect, it } from "vitest";
import { ownedSmsPhone } from "./conversationPolicy";

describe("ownedSmsPhone", () => {
  it("accepts a self-only phone conversation", () => {
    expect(ownedSmsPhone(
      { name: "+82 10-1234-5678", members: ["alice"] },
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
