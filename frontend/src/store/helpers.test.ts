import { describe, expect, it } from "vitest";
import {
  conversationDisplayName,
  matchesBlockedSender,
} from "./helpers";
import type { SenderRow } from "./db";

function sender(value: string): SenderRow {
  return { id: value, sender: value, created_at: 0 };
}

describe("matchesBlockedSender", () => {
  it("matches a legacy 010 row against canonical +8210 input", () => {
    expect(matchesBlockedSender("+821012345678", [sender("01012345678")])).toBe(true);
  });

  it("matches a canonical +8210 row against legacy 010 input", () => {
    expect(matchesBlockedSender("010-1234-5678", [sender("+82 10 1234 5678")])).toBe(true);
  });

  it("does not match a different or empty sender", () => {
    expect(matchesBlockedSender("+821012345678", [sender("01012345679")])).toBe(false);
    expect(matchesBlockedSender("", [sender("01012345678")])).toBe(false);
  });

  it("does not suffix-match unrelated international numbers", () => {
    expect(matchesBlockedSender("+442025550123", [sender("+12025550123")])).toBe(false);
  });

  it("matches alphanumeric IDs exactly with NFKC and case folding", () => {
    expect(matchesBlockedSender("ＢＡＮＫ０１０", [sender("bank010")])).toBe(true);
    expect(matchesBlockedSender("BANK01012345678", [sender("01012345678")])).toBe(false);
    expect(matchesBlockedSender("MYBANK", [sender("BANK")])).toBe(false);
  });
});

describe("cross-device contact display names", () => {
  const conversation = {
    cid: "sms-a",
    name: "+821012345678",
    members: ["alice"],
  };

  it("prefers a nonblank synchronized contact name without changing SMS identity", () => {
    const withContact = { ...conversation, synced_contact_name: "  홍길동  " };
    expect(conversationDisplayName(withContact)).toBe("홍길동");
    expect(withContact.name).toBe("+821012345678");
  });

  it("falls back to conversation identity, members, then the supplied fallback", () => {
    expect(conversationDisplayName({ ...conversation, synced_contact_name: "   " })).toBe("+821012345678");
    expect(conversationDisplayName({ ...conversation, name: "" })).toBe("alice");
    expect(conversationDisplayName(undefined, "?")).toBe("?");
  });

});
