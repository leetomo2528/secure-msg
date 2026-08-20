import { beforeAll, describe, expect, it } from "vitest";
import { initCrypto } from "./keys";
import { encodePairingQr, pairingSafetyNumber, parsePairingQr, randomPairingNonce } from "./pairing";

const ONE32 = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE";
const TWO32 = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI";
const THREE32 = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM";
const FOUR32 = "BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ";

const fields = {
  v: 1 as const,
  type: "securemsg-pairing" as const,
  server: "https://msg.example.com",
  username: "yunje",
  sid: "android_A1",
  challenge: ONE32,
  box_pk: TWO32,
  sig_pk: THREE32,
  nonce_new: FOUR32,
  expires_at: 1_750_000_000,
};

describe("QR pairing payloads", () => {
  beforeAll(async () => { await initCrypto(); });

  it("round-trips a well-formed payload", () => {
    expect(parsePairingQr(encodePairingQr(fields))).toEqual(fields);
  });

  it("rejects malformed or foreign payloads", () => {
    expect(parsePairingQr("not json")).toBeNull();
    expect(parsePairingQr(JSON.stringify({ ...fields, v: 2 }))).toBeNull();
    expect(parsePairingQr(JSON.stringify({ ...fields, type: "other" }))).toBeNull();
    // Each of these would otherwise reach the pairing endpoint unchecked.
    expect(parsePairingQr(JSON.stringify({ ...fields, server: "ftp://x" }))).toBeNull();
    expect(parsePairingQr(JSON.stringify({ ...fields, sid: "short" }))).toBeNull();
    expect(parsePairingQr(JSON.stringify({ ...fields, box_pk: "AA" }))).toBeNull();
    expect(parsePairingQr(JSON.stringify({ ...fields, expires_at: "soon" }))).toBeNull();
  });

  it("matches the cross-platform safety-number golden vector", () => {
    // Identical input must produce this exact string on Android
    // (android/app/src/test/java/com/yunjelee/securemsg/PairingTest.kt). If
    // the two disagree, the two screens show different numbers and every
    // pairing looks like an attack.
    expect(pairingSafetyNumber({
      nonceNew: THREE32,
      nonceApprover: FOUR32,
      sid: "android_A1",
      pubKey: ONE32,
      sigPub: TWO32,
    })).toBe("620892-730283-655820-764924-640994");
  });

  it("changes with every bound field", () => {
    const base = pairingSafetyNumber({
      nonceNew: THREE32, nonceApprover: FOUR32, sid: "android_A1", pubKey: ONE32, sigPub: TWO32,
    });
    const variants = [
      { nonceNew: FOUR32 }, { nonceApprover: THREE32 }, { sid: "android_A2" },
      { pubKey: TWO32 }, { sigPub: ONE32 },
    ];
    for (const variant of variants) {
      expect(pairingSafetyNumber({
        nonceNew: THREE32, nonceApprover: FOUR32, sid: "android_A1",
        pubKey: ONE32, sigPub: TWO32, ...variant,
      })).not.toBe(base);
    }
  });

  it("generates 32-byte url-safe nonces", () => {
    const nonce = randomPairingNonce();
    expect(nonce).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(randomPairingNonce()).not.toBe(nonce);
  });
});
