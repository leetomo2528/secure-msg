import "fake-indexeddb/auto";
import { beforeAll, describe, expect, it } from "vitest";
import { openDB } from "idb";
import { initCrypto } from "../crypto/keys";
import { serverDirectoryHash } from "../crypto/deviceTrust";
import {
  addBlockKeyword,
  addBlockedSender,
  blockKeywordRejection,
  blockedSenderRejection,
  listBlockKeywords,
  listBlockedSenders,
  getCursor,
  getMeta,
  highestStoredSeq,
  markConversationRead,
  conversationSummaries,
  listMessages,
  putMessage,
  setBlocked,
  setCarrierStatus,
  setCursor,
  setMeta,
  clearDeviceForReregistration,
  clearAllData,
  type MessageRow,
  getAccountTrust,
  listTrustedDevices,
  pinTrustedDirectory,
  pinTrustedDirectories,
  type TrustedDirectorySnapshot,
  TrustViolationError,
  db,
  type AccountTrustRow,
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

describe("schema upgrade", () => {
  /**
   * Deliberately the first test in this file: db() memoises its connection for
   * the module's lifetime, so this is the only point at which a profile can
   * still be staged at the previous version.
   */
  it("drops the v4 devices cache and opens the existing profile at v5", async () => {
    const legacy = await openDB("secure-msg", 4, {
      upgrade(d) {
        d.createObjectStore("meta");
        d.createObjectStore("devices", { keyPath: "sid" }).createIndex("by-user", "user_id");
        const messages = d.createObjectStore("messages", { keyPath: "id" });
        messages.createIndex("by-cid", "cid");
        messages.createIndex("by-cid-seq", ["cid", "seq"]);
        d.createObjectStore("cursors", { keyPath: "cid" });
        d.createObjectStore("blocklist", { keyPath: "id" }).createIndex("by-keyword", "keyword");
        d.createObjectStore("blockedSenders", { keyPath: "id" }).createIndex("by-sender", "sender");
        d.createObjectStore("accountTrust", { keyPath: "uid" });
        d.createObjectStore("trustedDevices", { keyPath: "id" }).createIndex("by-account", "uid");
      },
    });
    await legacy.put("devices", { sid: "legacy-device", user_id: 1, pub_key: "legacy-box" });
    await legacy.put("cursors", { cid: "legacy-thread", last_seq: 7 });
    legacy.close();

    const upgraded = await db();

    expect(upgraded.version).toBe(5);
    expect(Array.from(upgraded.objectStoreNames).sort()).toEqual([
      "accountTrust", "blockedSenders", "blocklist", "cursors",
      "messages", "meta", "trustedDevices",
    ]);
    // Dropping a store must not cost the profile the rest of its content.
    expect(await getCursor("legacy-thread")).toBe(7);
  });
});

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

  it("does not erase an optimistic queued state with a status-less sync row", async () => {
    await putMessage(msg("c_optimistic", 1, {
      carrier_status: "queued",
      carrier_updated_at: null,
    }));
    await putMessage(msg("c_optimistic", 1, {
      carrier_status: "none",
      carrier_updated_at: null,
    }));

    const rows = await listMessages("c_optimistic");
    expect(rows[0].carrier_status).toBe("queued");
  });

  it("accepts lifecycle progress even when the server timestamp is slightly older", async () => {
    await putMessage(msg("c_clock_skew", 1, {
      carrier_status: "queued",
      carrier_updated_at: 2000,
    }));
    await setCarrierStatus("c_clock_skew", 1, "sent", null, 1000);

    const rows = await listMessages("c_clock_skew");
    expect(rows[0].carrier_status).toBe("sent");
    expect(rows[0].carrier_updated_at).toBe(1000);
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

  it("reports the highest seq actually stored, not the cursor", async () => {
    await putMessage(msg("c_top", 1));
    await putMessage(msg("c_top", 2));
    await setCursor("c_top", 7);
    expect(await getCursor("c_top")).toBe(7);
    expect(await highestStoredSeq("c_top")).toBe(2);
    expect(await highestStoredSeq("c_top_empty")).toBe(0);
  });

  it("seeds read_seq from the pre-advance cursor so old threads are not unread", async () => {
    // The upgrade case: a row written before unread counts existed.
    await setCursor("c_read", 4);
    await putMessage(msg("c_read", 5, { sender_sid: "dev_peer" }));
    await setCursor("c_read", 5);
    const summaries = await conversationSummaries("dev_me");
    expect(summaries["c_read"].unread).toBe(1);
    expect(summaries["c_read"].lastSeq).toBe(5);
  });

  it("clears the unread count once the conversation is marked read", async () => {
    await putMessage(msg("c_seen", 1, { sender_sid: "dev_peer" }));
    await setCursor("c_seen", 1);
    expect((await conversationSummaries("dev_me"))["c_seen"].unread).toBe(1);
    await markConversationRead("c_seen", 1);
    expect((await conversationSummaries("dev_me"))["c_seen"].unread).toBe(0);
    // Marking read must not rewind, and must leave the delivery cursor alone.
    await markConversationRead("c_seen", 0);
    expect((await conversationSummaries("dev_me"))["c_seen"].unread).toBe(0);
    expect(await getCursor("c_seen")).toBe(1);
  });
});

describe("conversation summaries", () => {
  it("previews the newest readable line and ignores our own messages", async () => {
    await putMessage(msg("c_sum", 1, { sender_sid: "dev_peer", plaintext: "먼저 온 문자" }));
    await putMessage(msg("c_sum", 2, { sender_sid: "dev_me", plaintext: "내가 보낸 답장" }));
    const summaries = await conversationSummaries("dev_me");
    expect(summaries["c_sum"].preview).toBe("내가 보낸 답장");
    expect(summaries["c_sum"].lastSeq).toBe(2);
    // Only the peer's message counts toward the badge.
    expect(summaries["c_sum"].unread).toBe(1);
  });

  it("never leaks a blocked message into the sidebar", async () => {
    await putMessage(msg("c_blk", 1, { sender_sid: "dev_peer", plaintext: "보이는 문자" }));
    await putMessage(
      msg("c_blk", 2, { sender_sid: "dev_peer", plaintext: "차단된 문자", blocked: true }),
    );
    const summaries = await conversationSummaries("dev_me");
    expect(summaries["c_blk"].preview).toBe("보이는 문자");
    expect(summaries["c_blk"].lastSeq).toBe(1);
    expect(summaries["c_blk"].unread).toBe(1);
  });

  it("falls back to the subject, then to an attachment marker", async () => {
    await putMessage(
      msg("c_mms", 1, { sender_sid: "dev_peer", plaintext: "", subject: "사진첨부" }),
    );
    expect((await conversationSummaries("dev_me"))["c_mms"].preview).toBe("사진첨부");
    await putMessage(
      msg("c_mms", 2, {
        sender_sid: "dev_peer",
        plaintext: "   ",
        subject: null,
        attachments: [{ name: "a.png", content_type: "image/png", data: "", size: 0 }],
      }),
    );
    expect((await conversationSummaries("dev_me"))["c_mms"].preview).toBe("(첨부파일)");
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

  it("refuses the keyword shapes the relay would reject", () => {
    expect(blockKeywordRejection("광고")).toBeNull();
    expect(blockKeywordRejection("   ")).not.toBeNull();
    // 61 ligatures pass the editor's 120-character input cap and expand to 122
    // under NFKC, which is the length the relay measures.
    expect(blockKeywordRejection("ﬁ".repeat(61))).not.toBeNull();
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

  it("refuses the sender shapes the relay would reject", () => {
    expect(blockedSenderRejection("010-1234-5678")).toBeNull();
    expect(blockedSenderRejection("*1234#5")).toBeNull();
    // SENDER_RE ends on a digit, so a service code keeps its trailing '#' and
    // the relay answers 400 for it on every upload.
    expect(blockedSenderRejection("1588-1234#")).not.toBeNull();
    expect(blockedSenderRejection("MYBANK")).not.toBeNull();
    expect(blockedSenderRejection("15")).not.toBeNull();
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

describe("local account cleanup", () => {
  const uid = 70_001;
  const trustedDevices = [{
    sid: "cleanup-old-device",
    pub_key: "cleanup-box",
    sig_pub: "cleanup-sign",
    kind: "web",
    fingerprint: "cleanup-fingerprint",
  }];
  const trust = {
    uid,
    identity_sig_pub: "cleanup-identity",
    security_epoch: 12,
    directory_hash: serverDirectoryHash(trustedDevices.map(({ fingerprint: _fingerprint, ...device }) => device)),
    security_mode: "verified_v2" as const,
    devices: trustedDevices,
  };

  it("automatic revoked-device recovery removes local secrets but preserves trust anchors", async () => {
    await setMeta({
      username: "cleanup-user", uid, sid: "cleanup-old-device", deviceName: "old-device",
      keypair: { box: { pk: "cleanup-box", sk: "box-secret" }, sign: { pk: "cleanup-sign", sk: "sign-secret" } },
    });
    await putMessage(msg("cleanup-content", 1));
    await setCursor("cleanup-content", 1);
    await addBlockKeyword("cleanup-keyword");
    await addBlockedSender("010-7000-0001");
    await pinTrustedDirectory(trust);

    await clearDeviceForReregistration();

    expect(await getMeta()).toBeNull();
    expect(await listMessages("cleanup-content")).toEqual([]);
    expect(await getCursor("cleanup-content")).toBe(0);
    expect(await listBlockKeywords()).toEqual([]);
    expect(await listBlockedSenders()).toEqual([]);
    expect(await getAccountTrust(uid)).toMatchObject({
      identity_sig_pub: trust.identity_sig_pub,
      security_epoch: trust.security_epoch,
      directory_hash: trust.directory_hash,
    });
    expect(await listTrustedDevices(uid)).toMatchObject([{
      sid: "cleanup-old-device",
      pub_key: "cleanup-box",
      sig_pub: "cleanup-sign",
    }]);
  });

  it("explicit full forget erases account trust anchors", async () => {
    await pinTrustedDirectory(trust);

    await clearAllData();

    expect(await getAccountTrust(uid)).toBeNull();
    expect(await listTrustedDevices(uid)).toEqual([]);
  });
});

describe("trusted key directory", () => {
  beforeAll(async () => { await initCrypto(); });
  const initial = {
    uid: 801,
    identity_sig_pub: "identity-A",
    security_epoch: 4,
    directory_hash: "n7p8XOhJkZYBzeWxliNKrn0xqRGrDrsMG6wmYK_p9E0",
    security_mode: "verified_v2" as const,
    devices: [{
      sid: "sid-a", pub_key: "box-a", sig_pub: "sign-a", kind: "web", fingerprint: "fp-a",
    }],
  };
  const snapshot = (
    uid: number,
    sid: string,
    securityEpoch = 1,
  ): TrustedDirectorySnapshot => {
    const devices = [{
      sid, pub_key: `box-${sid}`, sig_pub: `sign-${sid}`, kind: "web", fingerprint: `fp-${sid}`,
    }];
    return {
      uid,
      identity_sig_pub: `identity-${uid}`,
      security_epoch: securityEpoch,
      directory_hash: serverDirectoryHash(devices),
      security_mode: "verified_v2",
      devices,
    };
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

  it("marks a device the next verified directory drops as revoked, keeping its key", async () => {
    const kept = snapshot(807, "kept", 1);
    const devices = [...kept.devices, {
      sid: "lost", pub_key: "box-lost", sig_pub: "sign-lost", kind: "web", fingerprint: "fp-lost",
    }];
    await pinTrustedDirectory({ ...kept, devices, directory_hash: serverDirectoryHash(devices) });

    await pinTrustedDirectory({ ...kept, security_epoch: 2 });

    const rows = await listTrustedDevices(807);
    // The row itself stays: history "lost" wrapped while it was trusted has to
    // remain openable. Only the mark separates "was verified" from "may still
    // be given access".
    expect(rows.map((row) => row.sid).sort()).toEqual(["kept", "lost"]);
    expect(rows.find((row) => row.sid === "kept")?.revoked_at).toBeNull();
    expect(rows.find((row) => row.sid === "lost")?.revoked_at).toEqual(expect.any(Number));
  });

  it("rejects an unsigned legacy directory after verified_v2 was pinned", async () => {
    const verified = snapshot(805, "verified-root", 3);
    await pinTrustedDirectory(verified);
    const injected = snapshot(805, "attacker-unsigned", 4);
    injected.security_mode = "legacy_v1";

    await expect(pinTrustedDirectory(injected))
      .rejects.toMatchObject({ code: "rollback" } satisfies Partial<TrustViolationError>);
    expect(await getAccountTrust(805)).toMatchObject({
      security_epoch: 3,
      security_mode: "verified_v2",
      directory_hash: verified.directory_hash,
    });
    expect((await listTrustedDevices(805)).map((device) => device.sid)).toEqual(["verified-root"]);
  });

  it("allows a legacy_v1 trust pin to upgrade to verified_v2", async () => {
    const legacy = snapshot(806, "legacy-root", 1);
    legacy.security_mode = "legacy_v1";
    await pinTrustedDirectory(legacy);
    const upgraded = { ...legacy, security_epoch: 2, security_mode: "verified_v2" as const };

    await expect(pinTrustedDirectory(upgraded)).resolves.toBeUndefined();
    expect(await getAccountTrust(806)).toMatchObject({ security_epoch: 2, security_mode: "verified_v2" });
  });

  it("treats a pre-v4 trust row with no mode as legacy and allows a verified upgrade", async () => {
    const old = snapshot(807, "old-schema-root", 1);
    await (await db()).put("accountTrust", {
      uid: old.uid,
      identity_sig_pub: old.identity_sig_pub,
      security_epoch: old.security_epoch,
      directory_hash: old.directory_hash,
      updated_at: Date.now(),
    } as AccountTrustRow);

    await expect(pinTrustedDirectory({ ...old, security_epoch: 2 }))
      .resolves.toBeUndefined();
    expect(await getAccountTrust(807)).toMatchObject({ security_epoch: 2, security_mode: "verified_v2" });
  });

  it("commits every participant in one successful batch", async () => {
    const first = snapshot(811, "batch-first");
    const second = snapshot(812, "batch-second");

    await pinTrustedDirectories([first, second]);

    expect(await getAccountTrust(811)).toMatchObject({ directory_hash: first.directory_hash });
    expect(await getAccountTrust(812)).toMatchObject({ directory_hash: second.directory_hash });
    expect(await listTrustedDevices(811)).toHaveLength(1);
    expect(await listTrustedDevices(812)).toHaveLength(1);
  });

  it("writes no participant when a later snapshot conflicts", async () => {
    const existing = snapshot(822, "stable-device");
    await pinTrustedDirectory(existing);
    const first = snapshot(821, "must-not-persist");
    const conflicting = snapshot(822, "stable-device", 2);
    conflicting.devices[0] = { ...conflicting.devices[0], sig_pub: "attacker-signing-key" };
    conflicting.directory_hash = serverDirectoryHash(conflicting.devices);

    await expect(pinTrustedDirectories([first, conflicting]))
      .rejects.toMatchObject({ code: "device_key_changed" } satisfies Partial<TrustViolationError>);

    expect(await getAccountTrust(821)).toBeNull();
    expect(await listTrustedDevices(821)).toEqual([]);
    expect(await getAccountTrust(822)).toMatchObject({
      security_epoch: existing.security_epoch,
      directory_hash: existing.directory_hash,
    });
    expect((await listTrustedDevices(822))[0].sig_pub).toBe(existing.devices[0].sig_pub);
  });

  it("aborts all queued writes when the security guard expires", async () => {
    const first = snapshot(831, "guard-first");
    const second = snapshot(832, "guard-second");
    let guardChecks = 0;

    await pinTrustedDirectories([first, second], () => {
      guardChecks += 1;
      // Allow preflight and the first participant's queued device/account
      // writes, then invalidate before the second participant is written.
      return guardChecks < 9;
    });

    expect(await getAccountTrust(831)).toBeNull();
    expect(await listTrustedDevices(831)).toEqual([]);
    expect(await getAccountTrust(832)).toBeNull();
    expect(await listTrustedDevices(832)).toEqual([]);
  });
});
