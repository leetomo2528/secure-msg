import "fake-indexeddb/auto";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { b64u, initCrypto } from "../crypto/keys";
import { putMessage, type MessageRow } from "./db";
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

    const ok = await useStore.getState().register("policy_a", "Ab1!xyz");
    expect(ok).toBe(false);
    expect(useStore.getState().error).toContain("8");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("accepts any 8+ char mix of letters, digits, symbols, and Korean", async () => {
    const fetchMock = vi.fn(async (_input: unknown) =>
      new Response(JSON.stringify({ ok: false, error: "username already taken" }), { status: 409 }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const ok = await useStore.getState().register("policy_b", "Ab1!가나다라마바사");
    expect(ok).toBe(false);
    // The failure must come from the (mocked) server, not from local
    // password validation — otherwise a composition rule has crept back in.
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0][0])).toContain("/register");
    expect(useStore.getState().error).toBe("username already taken");
  });
});
