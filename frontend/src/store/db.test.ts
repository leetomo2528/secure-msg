import "fake-indexeddb/auto";
import { beforeAll, describe, expect, it } from "vitest";
import { initCrypto } from "../crypto/keys";
import {
  addBlockKeyword,
  addBlockedSender,
  listBlockKeywords,
  listBlockedSenders,
  getCursor,
  getMeta,
  listMessages,
  putMessage,
  setBlocked,
  setCarrierStatus,
  setCursor,
  setMeta,
  type MessageRow,
  getAccountTrust,
  listTrustedDevices,
  pinTrustedDirectory,
  TrustViolationError,
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

describe("blocked senders", () => {
  it("canonicalizes and dedupes newly added Korean numbers", async () => {
    const first = await addBlockedSender("010-1234-5678");
    const second = await addBlockedSender("+82 10 1234 5678");
    expect(first.sender).toBe("+821012345678");
    expect(second.id).toBe(first.id);
    expect((await listBlockedSenders()).filter(
      (row) => row.sender === "+821012345678",
    )).toHaveLength(1);
  });
});

describe("meta persistence", () => {
  // Regression: the `meta` store has an out-of-line key (no keyPath), so
  // setMeta must pass the key explicitly. Without it, IndexedDB throws
  // DataError "Data provided to an operation does not meet requirements.",
  // which broke every web login/register at the device-key persistence step.
  it("setMeta/getMeta round-trip", async () => {
    await setMeta({
      username: "alice",
      uid: 7,
      sid: "dev_meta",
      deviceName: "test-device",
      keypair: { box: { pk: "bp", sk: "bs" }, sign: { pk: "sp", sk: "ss" } },
    });
    const back = await getMeta();
    expect(back).toMatchObject({ username: "alice", uid: 7, sid: "dev_meta" });
    expect(back?.keypair.box.sk).toBe("bs");
  });

  it("setMeta overwrites the previous device meta", async () => {
    await setMeta({
      username: "alice", uid: 7, sid: "dev_meta", deviceName: "first",
      keypair: { box: { pk: "bp", sk: "bs" }, sign: { pk: "sp", sk: "ss" } },
    });
    await setMeta({
      username: "alice", uid: 7, sid: "dev_meta2", deviceName: "second",
      keypair: { box: { pk: "bp2", sk: "bs2" }, sign: { pk: "sp2", sk: "ss2" } },
    });
    expect((await getMeta())?.sid).toBe("dev_meta2");
  });
});

describe("trusted key directory", () => {
  beforeAll(async () => { await initCrypto(); });
  const initial = {
    uid: 801,
    identity_sig_pub: "identity-A",
    security_epoch: 4,
    directory_hash: "n7p8XOhJkZYBzeWxliNKrn0xqRGrDrsMG6wmYK_p9E0",
    devices: [{
      sid: "sid-a", pub_key: "box-a", sig_pub: "sign-a", kind: "web", fingerprint: "fp-a",
    }],
  };

  it("pins a complete snapshot", async () => {
    await pinTrustedDirectory(initial);
    expect(await getAccountTrust(801)).toMatchObject({ security_epoch: 4, directory_hash: initial.directory_hash });
    expect(await listTrustedDevices(801)).toHaveLength(1);
  });

  it("rejects rollback without overwriting the pin", async () => {
    await expect(pinTrustedDirectory({ ...initial, security_epoch: 3, directory_hash: "old" }))
      .rejects.toMatchObject({ code: "rollback" } satisfies Partial<TrustViolationError>);
    expect(await getAccountTrust(801)).toMatchObject({ security_epoch: 4, directory_hash: initial.directory_hash });
  });

  it("rejects same-epoch equivocation", async () => {
    await expect(pinTrustedDirectory({ ...initial, directory_hash: "split-view" }))
      .rejects.toMatchObject({ code: "equivocation" } satisfies Partial<TrustViolationError>);
  });

  it("rejects account identity replacement", async () => {
    await expect(pinTrustedDirectory({ ...initial, security_epoch: 5, identity_sig_pub: "identity-B" }))
      .rejects.toMatchObject({ code: "identity_changed" } satisfies Partial<TrustViolationError>);
  });

  it("rejects same-SID box or signing key changes, even at a newer epoch", async () => {
    await expect(pinTrustedDirectory({
      ...initial,
      security_epoch: 5,
      directory_hash: "invalid-for-changed-key",
      devices: [{ ...initial.devices[0], sig_pub: "sign-attacker" }],
    })).rejects.toMatchObject({ code: "device_key_changed" } satisfies Partial<TrustViolationError>);
    expect((await listTrustedDevices(801))[0].sig_pub).toBe("sign-a");
    expect((await getAccountTrust(801))?.security_epoch).toBe(4);
  });

  it("accepts an unchanged SID plus a new trusted SID at a newer epoch", async () => {
    await pinTrustedDirectory({
      ...initial,
      security_epoch: 5,
      directory_hash: "j-eBv3P7yrvagsJRF-JauFbmoq1RnlceFCmPex-5how",
      devices: [...initial.devices, {
        sid: "sid-b", pub_key: "box-b", sig_pub: "sign-b", kind: "android_gateway", fingerprint: "fp-b",
      }],
    });
    expect(await listTrustedDevices(801)).toHaveLength(2);
    expect((await getAccountTrust(801))?.security_epoch).toBe(5);
  });
});
