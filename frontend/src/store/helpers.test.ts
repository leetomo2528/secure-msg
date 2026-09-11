import { describe, expect, it } from "vitest";
import {
  conversationDisplayName,
  directionFromMid,
  matchesBlockedSender,
  messageDirection,
  recordedDirection,
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

describe("SMS direction", () => {
  // The gateway relays both halves of a phone thread under its own sid, so the
  // only thing separating them on the wire is the shape of the id it minted.
  const RECEIVED = "in_" + "a3f0".repeat(15) + "b";
  const SENT = "7f3a1c22-9b0e-4d5a-8c11-2e6f90ab34cd";

  it("reads the gateway's own id shapes", () => {
    expect(directionFromMid(RECEIVED)).toBe("in");
    expect(directionFromMid(SENT)).toBe("out");
  });

  it("refuses to guess on anything else", () => {
    for (const value of [null, undefined, "", "relay-e2e-mid-00000001", "in", "INBOX"]) {
      expect(directionFromMid(value)).toBeNull();
    }
  });

  it("prefers a stored direction over the sender", () => {
    expect(messageDirection({ direction: "in", sender_sid: "sid-me" }, "sid-me")).toBe("in");
    expect(messageDirection({ direction: "out", sender_sid: "sid-gw" }, "sid-me")).toBe("out");
  });

  it("falls back to this device, then admits it does not know", () => {
    expect(messageDirection({ sender_sid: "sid-me" }, "sid-me")).toBe("out");
    expect(messageDirection({ sender_sid: "sid-gw" }, "sid-me")).toBe("unknown");
    expect(messageDirection({ sender_sid: "sid-gw" }, null)).toBe("unknown");
  });
});

describe("what direction gets recorded", () => {
  const MID_IN = "in_" + "a3f0".repeat(15) + "b";
  const MID_OUT = "7f3a1c22-9b0e-4d5a-8c11-2e6f90ab34cd";
  const ME = 7;

  it("records our own account's sealed direction and id shape", () => {
    expect(recordedDirection("out", MID_IN, ME, ME)).toBe("out");
    expect(recordedDirection(undefined, MID_IN, ME, ME)).toBe("in");
    expect(recordedDirection(undefined, MID_OUT, ME, ME)).toBe("out");
  });

  it("records nothing for another account, however it sealed the message", () => {
    // A peer's browser seals dir:"out" on their own sends and mints UUID ids
    // just like ours. Trusting either would put their message on our side.
    expect(recordedDirection("out", MID_OUT, 9, ME)).toBeUndefined();
    expect(recordedDirection("in", MID_IN, 9, ME)).toBeUndefined();
    expect(recordedDirection(undefined, MID_OUT, 9, ME)).toBeUndefined();
  });

  it("records nothing when we do not know our own account", () => {
    expect(recordedDirection("out", MID_OUT, ME, null)).toBeUndefined();
    expect(recordedDirection("out", MID_OUT, ME, undefined)).toBeUndefined();
  });

  it("records nothing when the id has no recognisable shape", () => {
    expect(recordedDirection(undefined, "relay-e2e-mid-00000001", ME, ME)).toBeUndefined();
    expect(recordedDirection(undefined, null, ME, ME)).toBeUndefined();
  });

  it("reads an unrecorded peer message as received, our own device as unknown", () => {
    // The account id is the only thing left once nothing was recorded.
    expect(messageDirection({ sender_sid: "sid-peer", sender_id: 9 }, "sid-me", ME)).toBe("in");
    expect(messageDirection({ sender_sid: "sid-mine-2", sender_id: ME }, "sid-me", ME)).toBe("unknown");
    expect(messageDirection({ sender_sid: "sid-me", sender_id: ME }, "sid-me", ME)).toBe("out");
  });
});
