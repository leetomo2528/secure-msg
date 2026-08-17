/**
 * Vitest roundtrip test for envelope encryption.
 * Run: `npm test`
 */
import { describe, it, expect, beforeAll } from "vitest";
import {
  initCrypto,
  generateKeypair,
  encryptMessage,
  decryptMessageWithSender,
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

describe("password hashing contract", () => {
  beforeAll(async () => {
    await initCrypto();
  });

  it("is deterministic for the same username salt", async () => {
    const a = await hashPassword("correct horse", saltForUser("alice_92"));
    const b = await hashPassword("correct horse", saltForUser("alice_92"));
    expect(a).toBe(b);
  });

  it("differs per user and rejects an empty salt", async () => {
    const a = await hashPassword("correct horse", saltForUser("alice_92"));
    const b = await hashPassword("correct horse", saltForUser("bob_92"));
    expect(a).not.toBe(b);
    await expect(hashPassword("x", "")).rejects.toThrow();
  });
});
