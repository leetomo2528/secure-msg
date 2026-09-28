import "fake-indexeddb/auto";
import { afterEach, describe, expect, it } from "vitest";
import { openDB } from "idb";
import { api } from "../net/api";
import {
  conversationSummaries,
  conversationSummary,
  markConversationRead,
  putMessage,
  setCursor,
  UNREAD_CAP,
  type MessageRow,
} from "./db";
import { useStore } from "./useStore";

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

async function expectSame(cid: string, sid = "dev_me", uid?: number | null) {
  const full = (await conversationSummaries(sid, uid))[cid];
  expect(await conversationSummary(cid, sid, uid)).toEqual(full ?? null);
  return full;
}

describe("indexed conversation summary", () => {
  it("takes the newest non-blocked preview, or returns null when none exists", async () => {
    await putMessage(msg("single_blocked_top", 1, { plaintext: "visible" }));
    await putMessage(msg("single_blocked_top", 2, {
      plaintext: "hidden", blocked: true,
    }));
    expect(await expectSame("single_blocked_top")).toEqual({
      lastSeq: 1, lastAt: 1_700_000_000_001, preview: "visible", unread: 1,
    });

    await putMessage(msg("single_all_blocked", 1, { blocked: true }));
    expect(await expectSame("single_all_blocked")).toBeUndefined();
    expect(await expectSame("single_no_rows")).toBeUndefined();
  });

  it("excludes this device's rows and the gateway's outgoing rows from unread", async () => {
    await putMessage(msg("single_own_device", 1, { sender_sid: "dev_peer" }));
    await putMessage(msg("single_own_device", 2, {
      sender_sid: "dev_me", plaintext: "my reply",
    }));
    expect(await expectSame("single_own_device")).toEqual({
      lastSeq: 2, lastAt: 1_700_000_000_002, preview: "나: my reply", unread: 1,
    });

    await putMessage(msg("single_gateway", 1, {
      sender_sid: "gateway", direction: "in", plaintext: "received",
    }));
    await putMessage(msg("single_gateway", 2, {
      sender_sid: "gateway", direction: "out", plaintext: "sent from phone",
    }));
    expect(await expectSame("single_gateway")).toEqual({
      lastSeq: 2, lastAt: 1_700_000_000_002,
      preview: "나: sent from phone", unread: 1,
    });
  });

  it("uses client_mid only for our own account when direction is absent", async () => {
    const uuid = "123e4567-e89b-12d3-a456-426614174000";
    await putMessage(msg("single_legacy_mid", 1, {
      sender_id: 7, sender_sid: "gateway", client_mid: "in_abc",
    }));
    await putMessage(msg("single_legacy_mid", 2, {
      sender_id: 7, sender_sid: "gateway", client_mid: uuid,
    }));
    await putMessage(msg("single_legacy_mid", 3, {
      sender_id: 8, sender_sid: "dev_peer", client_mid: uuid,
    }));
    expect(await expectSame("single_legacy_mid", "dev_me", 7)).toEqual({
      lastSeq: 3, lastAt: 1_700_000_000_003, preview: "text-3", unread: 2,
    });
    // Without uid, the second row is unknown and therefore still unread.
    expect(await expectSame("single_legacy_mid", "dev_me", null)).toEqual({
      lastSeq: 3, lastAt: 1_700_000_000_003, preview: "text-3", unread: 3,
    });
    expect(await expectSame("single_legacy_mid", "dev_me", undefined)).toEqual({
      lastSeq: 3, lastAt: 1_700_000_000_003, preview: "text-3", unread: 3,
    });
  });

  it("matches subject and attachment-only previews", async () => {
    await putMessage(msg("single_subject", 1, {
      plaintext: " body ", subject: "subject", direction: "in",
    }));
    expect((await expectSame("single_subject"))!.preview).toBe("subject — body");
    await putMessage(msg("single_subject", 2, {
      plaintext: "   ", subject: "subject only", direction: "in",
    }));
    expect((await expectSame("single_subject"))!.preview).toBe("subject only");

    await putMessage(msg("single_photo", 1, {
      plaintext: "", attachments: [{
        name: "picture.png", content_type: "image/png", data: "", size: 0,
      }],
    }));
    expect((await expectSame("single_photo"))!.preview).toBe("사진");
  });

  it("uses read_seq, falls back to a legacy last_seq, and defaults to zero", async () => {
    for (let seq = 1; seq <= 3; seq++) {
      await putMessage(msg("single_read_seq", seq));
      await putMessage(msg("single_legacy_cursor", seq));
      await putMessage(msg("single_no_cursor", seq));
    }
    await setCursor("single_read_seq", 3);
    await markConversationRead("single_read_seq", 2);
    expect((await expectSame("single_read_seq"))!.unread).toBe(1);

    const d = await openDB("secure-msg", 5);
    await d.put("cursors", { cid: "single_legacy_cursor", last_seq: 2 });
    d.close();
    expect((await expectSame("single_legacy_cursor"))!.unread).toBe(1);
    expect((await expectSame("single_no_cursor"))!.unread).toBe(3);
  });

  it("caps both paths at 100 unread rows", async () => {
    for (let seq = 1; seq <= 130; seq++) {
      await putMessage(msg("single_unread_cap", seq, { sender_sid: "dev_peer" }));
    }
    expect(UNREAD_CAP).toBe(100);
    expect((await expectSame("single_unread_cap"))!.unread).toBe(100);
  });
});

describe("store sidebar refresh", () => {
  const initial = useStore.getState();

  afterEach(() => {
    api.setToken(null);
    useStore.setState(initial, true);
  });

  it("merges one cid without changing another, while a full refresh replaces the map", async () => {
    await putMessage(msg("store_cid_A", 1, { sender_sid: "dev_peer" }));
    await putMessage(msg("store_cid_B", 1, { sender_sid: "dev_peer" }));
    const sentinel = { lastSeq: 999, lastAt: 999, preview: "sentinel", unread: 999 };
    api.setToken("test-token");
    useStore.setState({
      authed: true,
      approvalPending: false,
      securityLocked: false,
      securityGeneration: 1,
      uid: 7,
      sid: "dev_me",
      keypair: {
        box: { pk: "test-box-pk", sk: "test-box-sk" },
        sign: { pk: "test-sign-pk", sk: "test-sign-sk" },
      },
      convMeta: { store_cid_B: sentinel },
    });

    await useStore.getState().refreshConvMeta("store_cid_A");
    expect(useStore.getState().convMeta.store_cid_A).toEqual(
      (await conversationSummaries("dev_me", 7)).store_cid_A,
    );
    expect(useStore.getState().convMeta.store_cid_B).toBe(sentinel);

    await useStore.getState().refreshConvMeta();
    expect(useStore.getState().convMeta).toEqual(await conversationSummaries("dev_me", 7));
    expect(useStore.getState().convMeta.store_cid_B).not.toEqual(sentinel);

    useStore.setState({
      convMeta: { ...useStore.getState().convMeta, store_no_rows: sentinel },
    });
    await useStore.getState().refreshConvMeta("store_no_rows");
    expect(useStore.getState().convMeta).not.toHaveProperty("store_no_rows");
    expect(useStore.getState().convMeta).toHaveProperty("store_cid_B");
  });
});
