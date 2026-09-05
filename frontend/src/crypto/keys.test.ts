/**
 * Vitest roundtrip test for envelope encryption.
 * Run: `npm test`
 */
import { describe, it, expect, beforeAll, vi } from "vitest";
import {
  initCrypto,
  generateKeypair,
  encryptMessage,
  decryptMessageWithSender,
  rewrapMessageKey,
  hashPassword,
  saltForUser,
  b64u,
  unb64u,
  type Envelope,
} from "./keys";

/** Flip one bit of the first byte of a base64url payload. */
function flipFirstBit(value: string): string {
  const bytes = unb64u(value);
  bytes[0] ^= 0x01;
  return b64u(new Uint8Array(bytes));
}

describe("envelope crypto", () => {
  beforeAll(async () => {
    await initCrypto();
  });

  it("roundtrips for a single recipient", async () => {
    const alice = generateKeypair();
    const bob = generateKeypair();
    const plaintext = "hello from alice";

    const env: Envelope = await encryptMessage(plaintext,
      [{ sid: "bob1", pub_key: bob.box.pk }], alice);

    // Bob decrypts using his private key + Alice's public key (resolved from member list).
    const got = decryptMessageWithSender(env, "bob1", bob, alice.box.pk);
    expect(got).toBe(plaintext);
  });

  it("roundtrips for multiple devices (multi-device sync)", async () => {
    const alice = generateKeypair();     // alice's sending device
    const bob1 = generateKeypair();      // bob device 1
    const bob2 = generateKeypair();      // bob device 2

    const env = await encryptMessage("multi", [
      { sid: "alice1", pub_key: alice.box.pk },  // sender's own device (for sync)
      { sid: "bob1", pub_key: bob1.box.pk },
      { sid: "bob2", pub_key: bob2.box.pk },
    ], alice);

    // Each device unwraps its own key.
    expect(decryptMessageWithSender(env, "alice1", alice, alice.box.pk)).toBe("multi");
    expect(decryptMessageWithSender(env, "bob1", bob1, alice.box.pk)).toBe("multi");
    expect(decryptMessageWithSender(env, "bob2", bob2, alice.box.pk)).toBe("multi");
  });

  it("returns null when the message is not addressed to this device", async () => {
    const alice = generateKeypair();
    const bob = generateKeypair();
    const env = await encryptMessage("secret",
      [{ sid: "bob1", pub_key: bob.box.pk }], alice);
    // carol tries to decrypt — no key for her sid.
    const carol = generateKeypair();
    expect(decryptMessageWithSender(env, "carol1", carol, alice.box.pk)).toBeNull();
  });

  it("returns null on tampered ciphertext (authenticity enforced)", async () => {
    const alice = generateKeypair();
    const bob = generateKeypair();
    const env = await encryptMessage("orig",
      [{ sid: "bob1", pub_key: bob.box.pk }], alice);
    // Flip one byte of the ciphertext.
    const bytes = unb64u(env.ct);
    bytes[0] ^= 0x01;
    const flipped = b64u(bytes);
    const tampered: Envelope = { ...env, ct: flipped };
    expect(decryptMessageWithSender(tampered, "bob1", bob, alice.box.pk)).toBeNull();
  });

  it("returns null instead of throwing on malformed envelope fields", () => {
    const bob = generateKeypair();
    const malformed = {
      ct: "not base64!",
      nonce: "bad",
      keys: { bob1: { ek: "bad", n: "bad" } },
    } as Envelope;
    expect(() => decryptMessageWithSender(malformed, "bob1", bob, "bad-key"))
      .not.toThrow();
    expect(decryptMessageWithSender(malformed, "bob1", bob, "bad-key")).toBeNull();
  });

  it("returns null on a tampered wrapped key", async () => {
    const alice = generateKeypair();
    const bob = generateKeypair();
    const env = await encryptMessage("orig",
      [{ sid: "bob1", pub_key: bob.box.pk }], alice);
    const tampered: Envelope = {
      ...env,
      keys: { bob1: { ...env.keys.bob1, ek: flipFirstBit(env.keys.bob1.ek) } },
    };
    expect(decryptMessageWithSender(tampered, "bob1", bob, alice.box.pk)).toBeNull();
  });

  it("returns null when the ciphertext is replayed under a different nonce", async () => {
    const alice = generateKeypair();
    const bob = generateKeypair();
    const env = await encryptMessage("orig",
      [{ sid: "bob1", pub_key: bob.box.pk }], alice);
    const other = await encryptMessage("other",
      [{ sid: "bob1", pub_key: bob.box.pk }], alice);
    const tampered: Envelope = { ...env, nonce: other.nonce };
    expect(decryptMessageWithSender(tampered, "bob1", bob, alice.box.pk)).toBeNull();
  });

  it("returns null when a different sender key is claimed (impersonation)", async () => {
    const alice = generateKeypair();
    const bob = generateKeypair();
    const mallory = generateKeypair();
    const env = await encryptMessage("orig",
      [{ sid: "bob1", pub_key: bob.box.pk }], alice);
    expect(decryptMessageWithSender(env, "bob1", bob, mallory.box.pk)).toBeNull();
  });
});

describe("re-wrapped history keys", () => {
  beforeAll(async () => {
    await initCrypto();
  });

  /** alice sends to her own first device only; her second device joins later. */
  async function history() {
    const alice1 = generateKeypair(); // sender + sharer
    const alice2 = generateKeypair(); // registered after the message was sent
    const env = await encryptMessage("과거 메시지",
      [{ sid: "alice1", pub_key: alice1.box.pk }], alice1);
    return { alice1, alice2, env };
  }

  it("lets a device that was never a recipient read the message after a re-wrap", async () => {
    const { alice1, alice2, env } = await history();
    expect(decryptMessageWithSender(env, "alice2", alice2, alice1.box.pk)).toBeNull();

    const shared = rewrapMessageKey(env, "alice1", alice1, alice1.box.pk, alice2.box.pk);
    expect(shared).not.toBeNull();
    expect(shared!.by).toBe("alice1");
    const backfilled: Envelope = { ...env, keys: { ...env.keys, alice2: shared! } };

    // The wrapper key, not the sender key, is what opens a `by` entry — and it
    // must come from the caller's pinned store.
    const got = decryptMessageWithSender(
      backfilled, "alice2", alice2, alice1.box.pk, (sid) => (sid === "alice1" ? alice1.box.pk : null),
    );
    expect(got).toBe("과거 메시지");
  });

  it("refuses a `by` entry when the wrapper key is not pinned locally", async () => {
    const { alice1, alice2, env } = await history();
    const shared = rewrapMessageKey(env, "alice1", alice1, alice1.box.pk, alice2.box.pk)!;
    const backfilled: Envelope = { ...env, keys: { ...env.keys, alice2: shared } };

    // No resolver at all, an empty pinned store, and a pinned store that simply
    // does not know this SID must all refuse rather than fall back to the
    // sender key the relay named.
    expect(decryptMessageWithSender(backfilled, "alice2", alice2, alice1.box.pk)).toBeNull();
    expect(decryptMessageWithSender(backfilled, "alice2", alice2, alice1.box.pk, () => null)).toBeNull();
    expect(decryptMessageWithSender(backfilled, "alice2", alice2, alice1.box.pk, () => undefined)).toBeNull();
  });

  it("refuses a `by` entry when the pinned wrapper key is a different device", async () => {
    const { alice1, alice2, env } = await history();
    const mallory = generateKeypair();
    const shared = rewrapMessageKey(env, "alice1", alice1, alice1.box.pk, alice2.box.pk)!;
    const backfilled: Envelope = { ...env, keys: { ...env.keys, alice2: shared } };
    expect(decryptMessageWithSender(
      backfilled, "alice2", alice2, alice1.box.pk, () => mallory.box.pk,
    )).toBeNull();
  });

  it("keeps entries without `by` on the sender key, resolver or not", async () => {
    const alice = generateKeypair();
    const bob = generateKeypair();
    const env = await encryptMessage("plain", [{ sid: "bob1", pub_key: bob.box.pk }], alice);
    const resolver = vi.fn(() => "should-never-be-used");
    expect(decryptMessageWithSender(env, "bob1", bob, alice.box.pk, resolver)).toBe("plain");
    expect(resolver).not.toHaveBeenCalled();
  });

  it("re-wraps a key this device only holds through an earlier share (chained `by`)", async () => {
    const { alice1, alice2, env } = await history();
    const alice3 = generateKeypair();
    const forAlice2 = rewrapMessageKey(env, "alice1", alice1, alice1.box.pk, alice2.box.pk)!;
    const backfilled: Envelope = { ...env, keys: { ...env.keys, alice2: forAlice2 } };

    // alice2 now shares onward; its own entry opens with alice1's key.
    const forAlice3 = rewrapMessageKey(backfilled, "alice2", alice2, alice1.box.pk, alice3.box.pk)!;
    expect(forAlice3.by).toBe("alice2");
    const chained: Envelope = { ...backfilled, keys: { ...backfilled.keys, alice3: forAlice3 } };
    expect(decryptMessageWithSender(
      chained, "alice3", alice3, alice1.box.pk, (sid) => (sid === "alice2" ? alice2.box.pk : null),
    )).toBe("과거 메시지");
  });

  it("returns null when this device holds no key, or the opener key is wrong", async () => {
    const { alice1, alice2, env } = await history();
    const target = generateKeypair();
    expect(rewrapMessageKey(env, "unknown-sid", alice1, alice1.box.pk, target.box.pk)).toBeNull();
    expect(rewrapMessageKey(env, "alice1", alice1, alice2.box.pk, target.box.pk)).toBeNull();
    expect(rewrapMessageKey(env, "alice1", alice1, alice1.box.pk, "not-a-key")).toBeNull();
  });
});

describe("password hashing contract", () => {
  beforeAll(async () => {
    await initCrypto();
  });

  it("is deterministic for the same username salt", async () => {
    const a = await hashPassword("correct horse", saltForUser("alice_92"));
    const b = await hashPassword("correct horse", saltForUser("alice_92"));
    expect(a).toBe(b);
  });

  it("matches the cross-platform golden vector", async () => {
    // The relay stores bcrypt over THIS hash, so a user who registers on the
    // web must be able to log in from the Android client and back. The salt is
    // pinned separately because that is where the two implementations differ in
    // shape (web lowercases then NFKC-normalizes, Android normalizes first).
    // The same literals are asserted on-device and against PyNaCl on the relay.
    expect(saltForUser("alice_92")).toBe("Z8TMpFrk1L3TGTqifSaL2A");
    expect(await hashPassword("correct horse", saltForUser("alice_92")))
      .toBe("dzuVYr5AiVb52u3imbOmNAxzOtD1gwLxYUS1kVQLNfE");
  });

  it("differs per user and rejects an empty salt", async () => {
    const a = await hashPassword("correct horse", saltForUser("alice_92"));
    const b = await hashPassword("correct horse", saltForUser("bob_92"));
    expect(a).not.toBe(b);
    await expect(hashPassword("x", "")).rejects.toThrow();
  });
});
