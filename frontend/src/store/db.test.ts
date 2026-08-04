import "fake-indexeddb/auto";
import { describe, expect, it } from "vitest";
import {
  addBlockKeyword,
  listBlockKeywords,
  getCursor,
  listMessages,
  putMessage,
  setBlocked,
  setCarrierStatus,
  setCursor,
  type MessageRow,
} from "./db";

function msg(cid: string, seq: number, extra: Partial<MessageRow> = {}): MessageRow {
  return {
    id: "",
    seq,
    cid,
    sender_id: 1,
    sender_sid: "dev_a",
    plaintext: `text-${seq}`,
    created_at: 1_700_000_000_000 + seq,
    ...extra,
  };
}

describe("message persistence", () => {
  it("dedupes by (cid, seq) and sorts by seq", async () => {
    await putMessage(msg("c_sort", 2));
    await putMessage(msg("c_sort", 1));
    await putMessage({ ...msg("c_sort", 2), plaintext: "replaced" });

    const rows = await listMessages("c_sort");
    expect(rows.map((r) => r.seq)).toEqual([1, 2]);
    expect(rows[1].plaintext).toBe("replaced");
  });

  it("keeps the newer carrier state when a stale sync page re-puts a row", async () => {
    await putMessage(msg("c_carrier", 1, { carrier_status: "queued", carrier_updated_at: 1000 }));
    // Socket event advances the carrier lifecycle.
    await setCarrierStatus("c_carrier", 1, "sent", null, 2000);
    // A REST re-sync then re-puts the same row with its stale snapshot.
    await putMessage(msg("c_carrier", 1, { carrier_status: "queued", carrier_updated_at: 1000 }));

    const rows = await listMessages("c_carrier");
    expect(rows[0].carrier_status).toBe("sent");
    expect(rows[0].carrier_updated_at).toBe(2000);
  });

  it("applies carrier status updates atomically with the ordering rules", async () => {
    await putMessage(msg("c_advance", 1, { carrier_status: "queued" }));
    await setCarrierStatus("c_advance", 1, "sent", null, 100);
    await setCarrierStatus("c_advance", 1, "dispatched", null, 200); // regression → ignored
    await setCarrierStatus("c_advance", 1, "delivered", null, 300);
    await setCarrierStatus("c_advance", 1, "sent", null, 400); // terminal → ignored

    const rows = await listMessages("c_advance");
    expect(rows[0].carrier_status).toBe("delivered");
  });

  it("ignores carrier updates for messages that are not stored yet", async () => {
    await expect(setCarrierStatus("c_missing", 9, "sent")).resolves.toBeUndefined();
  });

  it("setBlocked only touches existing rows", async () => {
    await putMessage(msg("c_block", 1));
    await setBlocked("c_block", 1, true);
    expect((await listMessages("c_block"))[0].blocked).toBe(true);
    await setBlocked("c_block", 999, true); // must not throw or create a row
    expect((await listMessages("c_block")).length).toBe(1);
  });
});

describe("cursors", () => {
  it("only ever move forward", async () => {
    await setCursor("c_cursor", 5);
    await setCursor("c_cursor", 3);
    expect(await getCursor("c_cursor")).toBe(5);
    await setCursor("c_cursor", 9);
    expect(await getCursor("c_cursor")).toBe(9);
  });
});

describe("blocklist keywords", () => {
  it("normalizes and dedupes keywords", async () => {
    const first = await addBlockKeyword("  SPAM ");
    const second = await addBlockKeyword("spam");
    expect(first.keyword).toBe("spam");
    expect(second.id).toBe(first.id);
    expect((await listBlockKeywords()).filter((r) => r.keyword === "spam").length).toBe(1);
  });

  it("rejects empty keywords", async () => {
    await expect(addBlockKeyword("   ")).rejects.toThrow();
  });
});
