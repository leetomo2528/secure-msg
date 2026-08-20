import "fake-indexeddb/auto";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { b64u, initCrypto } from "../crypto/keys";
import { serverDirectoryHash } from "../crypto/deviceTrust";
import { api } from "../net/api";
import {
  addBlockKeyword,
  addBlockedSender,
  listMessages,
  listBlockKeywords,
  listBlockedSenders,
  pinTrustedDirectory,
  putBlockKeywordRow,
  putBlockedSenderRow,
  putMessage,
  type MessageRow,
} from "./db";
import { decodeRelayContent, useStore } from "./useStore";

function msg(cid: string, seq: number, extra: Partial<MessageRow> = {}): MessageRow {
  return {
    id: "",
    seq,
    cid,
    sender_id: 1,
    sender_sid: "dev_a",
    plaintext: `${cid}-text-${seq}`,
    created_at: 1_700_000_000_000 + seq,
    ...extra,
  };
}

describe("logout session cleanup", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    api.setToken(null);
    useStore.setState({ authed: false, username: null, uid: null, sid: null, error: null });
  });

  it("attempts remote invalidation before clearing the in-memory token", async () => {
    api.setToken("active-token");
    useStore.setState({ authed: true, username: "alice", uid: 1, sid: "device-a" });
    const remoteLogout = vi.spyOn(api, "logout").mockImplementation(async () => {
      expect(api.token).toBe("active-token");
      expect(useStore.getState().authed).toBe(true);
      return { ok: true };
    });

    await useStore.getState().logout();

    expect(remoteLogout).toHaveBeenCalledTimes(1);
    expect(api.token).toBeNull();
    expect(useStore.getState().authed).toBe(false);
    expect(useStore.getState().conversations).toEqual([]);
  });

  it("always clears local auth when remote invalidation throws", async () => {
    api.setToken("active-token");
    useStore.setState({ authed: true, username: "alice", uid: 1, sid: "device-a" });
    vi.spyOn(api, "logout").mockRejectedValue(new TypeError("offline"));

    await useStore.getState().logout();

    expect(api.token).toBeNull();
    expect(useStore.getState().authed).toBe(false);
    expect(useStore.getState().activeCid).toBeNull();
  });

  it("always clears rendered auth state when IndexedDB cleanup throws", async () => {
    api.setToken("active-token");
    useStore.setState({
      authed: true,
      username: "alice",
      uid: 1,
      sid: "device-a",
      keypair: {} as any,
      conversations: [{ cid: "secret" } as any],
      activeMessages: [{ plaintext: "secret text" } as any],
    });
    vi.spyOn(api, "logout").mockResolvedValue({ ok: true });
    const transaction = vi.spyOn(IDBDatabase.prototype, "transaction")
      .mockImplementation(() => { throw new DOMException("broken", "InvalidStateError"); });

    await expect(useStore.getState().logout()).resolves.toBeUndefined();

    expect(transaction).toHaveBeenCalled();
    expect(api.token).toBeNull();
    expect(useStore.getState().authed).toBe(false);
    expect(useStore.getState().keypair).toBeNull();
    expect(useStore.getState().conversations).toEqual([]);
    expect(useStore.getState().activeMessages).toEqual([]);
    expect(useStore.getState().error).toContain("로컬 캐시");
  });
});

describe("block-rule synchronization", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    api.setToken(null);
  });

  it("retains failed local uploads while replacing successful and stale server rows", async () => {
    const failedKeyword = await addBlockKeyword("sync-failed-keyword");
    const uploadedSender = await addBlockedSender("010-9876-5432");
    await putBlockKeywordRow({ id: "srv:9001", keyword: "sync-stale-keyword", created_at: 1 });
    await putBlockedSenderRow({ id: "srv:9002", sender: "+821099990002", created_at: 1 });

    api.setToken("active-token");
    vi.spyOn(api, "listBlockRules").mockResolvedValue({
      ok: true,
      rules: [{ id: 10, type: "keyword", value: "sync-server-keyword", created_at: 10 }],
    });
    vi.spyOn(api, "addBlockRule").mockImplementation(async (type, value) => {
      if (type === "keyword" && value === failedKeyword.keyword) {
        return { ok: false, error: "temporary failure" };
      }
      if (type === "sender" && value === uploadedSender.sender) {
        return { ok: true, rule: { id: 11, type, value, created_at: 11 } };
      }
      return { ok: false, error: "unexpected local rule" };
    });

    await useStore.getState().syncBlockRules();

    const keywords = await listBlockKeywords();
    expect(keywords).toContainEqual(failedKeyword);
    expect(keywords).toContainEqual({
      id: "srv:10", keyword: "sync-server-keyword", created_at: 10_000,
    });
    expect(keywords.some((row) => row.id === "srv:9001")).toBe(false);

    const senders = await listBlockedSenders();
    expect(senders).toContainEqual({
      id: "srv:11", sender: uploadedSender.sender, created_at: 11_000,
    });
    expect(senders.some((row) => row.id === uploadedSender.id)).toBe(false);
    expect(senders.some((row) => row.id === "srv:9002")).toBe(false);
  });
});

describe("sender block-rule reapplication", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    useStore.setState({ username: null, conversations: [], activeCid: null, activeMessages: [] });
  });

  it("blocks only the cached Android gateway sender in an SMS conversation", async () => {
    const uid = 81_001;
    const cid = "sender-reapply-mixed-direction";
    const phone = "+821055501234";
    const devices = [
      { sid: "reapply-web", pub_key: "web-box", sig_pub: "web-sign", kind: "web", fingerprint: "web-fp" },
      { sid: "reapply-gateway", pub_key: "gateway-box", sig_pub: "gateway-sign", kind: "android_gateway", fingerprint: "gateway-fp" },
    ];
    await pinTrustedDirectory({
      uid,
      identity_sig_pub: "reapply-identity",
      security_epoch: 1,
      directory_hash: serverDirectoryHash(devices),
      security_mode: "verified_v2",
      devices,
    });
    await putMessage(msg(cid, 1, { sender_id: uid, sender_sid: "reapply-web", plaintext: "outgoing" }));
    await putMessage(msg(cid, 2, { sender_id: uid, sender_sid: "reapply-gateway", plaintext: "incoming" }));
    useStore.setState({
      username: "alice",
      conversations: [{ cid, conv_id: 81_001, name: phone, members: ["alice"], created_at: 1 }],
      activeCid: cid,
    });
    vi.spyOn(api, "addBlockRule").mockResolvedValue({ ok: false, error: "offline" });

    await useStore.getState().addBlockedSenderRule(phone);

    const [outgoing, incoming] = await listMessages(cid);
    expect(Boolean(outgoing.blocked)).toBe(false);
    expect(incoming.blocked).toBe(true);
  });
});

describe("Korean SMS conversation identity", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    useStore.setState({ username: null, conversations: [], error: null });
  });

  it("reuses a legacy local-number conversation for a +82 recipient", async () => {
    const create = vi.spyOn(api, "createConversation");
    useStore.setState({
      username: "alice",
      conversations: [{
        cid: "legacy_sms",
        conv_id: 1,
        name: "01012345678",
        members: ["alice"],
        created_at: 1,
      }],
    });

    await expect(useStore.getState().newSmsConversation("+821012345678"))
      .resolves.toBe("legacy_sms");
    expect(create).not.toHaveBeenCalled();
  });

  it("creates a new Korean conversation with its canonical +82 name", async () => {
    const create = vi.spyOn(api, "createConversation")
      .mockResolvedValue({ ok: true, cid: "new_sms" });
    vi.spyOn(api, "listConversations")
      .mockResolvedValue({ ok: true, conversations: [] });
    useStore.setState({ username: "alice", conversations: [] });

    await expect(useStore.getState().newSmsConversation("010-1234-5678"))
      .resolves.toBe("new_sms");
    expect(create).toHaveBeenCalledWith(["alice"], "+821012345678");
  });
});

describe("selectConversation race", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("never shows one conversation's messages under another header", async () => {
    // The REST sync triggered by selectConversation must fail fast in tests.
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify({ ok: false, error: "offline" }), { status: 500 })),
    );

    await putMessage(msg("race_a", 1));
    await putMessage(msg("race_a", 2));
    await putMessage(msg("race_b", 1));

    // Rapid A→B switching: the two async loads overlap.
    const first = useStore.getState().selectConversation("race_a");
    const second = useStore.getState().selectConversation("race_b");
    await Promise.all([first, second]);

    const state = useStore.getState();
    expect(state.activeCid).toBe("race_b");
    expect(state.activeMessages.length).toBe(1);
    for (const row of state.activeMessages) {
      expect(row.cid).toBe("race_b");
    }
  });
});

describe("decodeRelayContent", () => {
  beforeAll(async () => {
    await initCrypto();
  });

  it("passes legacy plaintext through unchanged", () => {
    const decoded = decodeRelayContent("그냥 문자");
    expect(decoded).toMatchObject({ v: 1, type: "text", text: "그냥 문자" });
  });

  it("parses a valid v1 text payload", () => {
    const decoded = decodeRelayContent(JSON.stringify({ v: 1, type: "text", text: "hi" }));
    expect(decoded.text).toBe("hi");
    expect(decoded.attachments).toEqual([]);
  });

  it("keeps well-formed attachments and truncates subject", () => {
    const data = b64u(new Uint8Array([1, 2, 3]));
    const decoded = decodeRelayContent(JSON.stringify({
      v: 1,
      type: "mms",
      text: "",
      subject: "s".repeat(300),
      attachments: [{ name: "a.png", content_type: "image/png", data, size: 3 }],
    }));
    expect(decoded.type).toBe("mms");
    expect(decoded.subject?.length).toBe(120);
    expect(decoded.attachments?.length).toBe(1);
  });

  it("drops attachments with bad base64, wrong size, or unsafe mime", () => {
    const good = b64u(new Uint8Array([1, 2, 3]));
    const decoded = decodeRelayContent(JSON.stringify({
      v: 1,
      type: "mms",
      text: "x",
      attachments: [
        { name: "bad64", content_type: "image/png", data: "!!!not-base64!!!", size: 3 },
        { name: "wrongsize", content_type: "image/png", data: good, size: 99 },
        { name: "badmime", content_type: "text/html bad", data: good, size: 3 },
      ],
    }));
    expect(decoded.attachments).toEqual([]);
  });

  it("caps attachment count at 8 and total size at 512KB", () => {
    const small = b64u(new Uint8Array([7]));
    const many = Array.from({ length: 12 }, (_, i) => ({
      name: `f${i}`, content_type: "image/png", data: small, size: 1,
    }));
    expect(decodeRelayContent(JSON.stringify({
      v: 1, type: "mms", text: "", attachments: many,
    })).attachments?.length).toBe(8);

    const oversized = b64u(new Uint8Array(513 * 1024));
    expect(decodeRelayContent(JSON.stringify({
      v: 1,
      type: "mms",
      text: "",
      attachments: [{ name: "big", content_type: "image/png", data: oversized, size: 513 * 1024 }],
    })).attachments).toEqual([]);
  });

  it("caps message text at 20,000 characters", () => {
    const decoded = decodeRelayContent(JSON.stringify({
      v: 1, type: "text", text: "a".repeat(30_000),
    }));
    expect(decoded.text.length).toBeLessThanOrEqual(20_000);
  });
});

describe("register password policy", () => {
  // The only rule is total length: 8-1,024 chars. No composition requirements
  // (letters, digits, symbols, Korean — any mix is fine).
  beforeAll(async () => {
    await initCrypto();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    useStore.setState({ error: null });
  });

  it("rejects under 8 chars without any network call", async () => {
    const fetchMock = vi.fn(async (_input: unknown) =>
      new Response(JSON.stringify({ ok: true }), { status: 200 }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const challenge = await useStore.getState().requestEmailRegistration(
      "policy_a", "policy_a@example.test", "Ab1!xyz",
    );
    expect(challenge).toBeNull();
    expect(useStore.getState().error).toContain("8");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("accepts any 8+ char mix of letters, digits, symbols, and Korean", async () => {
    const fetchMock = vi.fn(async (_input: unknown) =>
      new Response(JSON.stringify({ ok: false, error: "username already taken" }), { status: 409 }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const challenge = await useStore.getState().requestEmailRegistration(
      "policy_b", "policy_b@example.test", "Ab1!가나다라마바사",
    );
    expect(challenge).toBeNull();
    // The failure must come from the (mocked) server, not from local
    // password validation — otherwise a composition rule has crept back in.
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0][0])).toContain("/register/email/request");
    expect(useStore.getState().error).toBe("username already taken");
  });
});
