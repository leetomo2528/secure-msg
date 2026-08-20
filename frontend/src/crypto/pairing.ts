/**
 * QR device pairing helpers (v0.11, docs/QR_PAIRING_DESIGN.md).
 *
 * The QR payload carries PUBLIC data only: who the pending device is and the
 * one-time nonce it generated. Authentication remains the approver's Ed25519
 * signature over the v2 approval statement; the human compares the safety
 * number derived from both nonces on both screens.
 */
import sodium from "libsodium-wrappers-sumo";
import { initCrypto } from "./keys";

export interface PairingQrFields {
  v: 1;
  type: "securemsg-pairing";
  /** Relay origin the new device registered against. */
  server: string;
  username: string;
  sid: string;
  /** Pending-registration challenge bound to the subject device. */
  challenge: string;
  box_pk: string;
  sig_pk: string;
  /** Random 32-byte nonce generated once per pending registration. */
  nonce_new: string;
  expires_at: number;
}

const QR_TYPE = "securemsg-pairing";

export function encodePairingQr(fields: PairingQrFields): string {
  return JSON.stringify(fields);
}

export function parsePairingQr(text: string): PairingQrFields | null {
  try {
    const value = JSON.parse(text) as Partial<PairingQrFields>;
    const b64u32 = (v: unknown): v is string =>
      typeof v === "string" && /^[A-Za-z0-9_-]{43}$/.test(v);
    if (
      value.v !== 1 || value.type !== QR_TYPE ||
      typeof value.server !== "string" || !/^https?:\/\//.test(value.server) ||
      typeof value.username !== "string" || value.username.length === 0 ||
      typeof value.sid !== "string" || !/^[A-Za-z0-9_-]{8,64}$/.test(value.sid) ||
      !b64u32(value.challenge) || !b64u32(value.nonce_new) ||
      !b64u32(value.box_pk) || !b64u32(value.sig_pk) ||
      !Number.isSafeInteger(value.expires_at)
    ) {
      return null;
    }
    return value as PairingQrFields;
  } catch {
    return null;
  }
}

export interface SafetyNumberFields {
  nonceNew: string;
  nonceApprover: string;
  sid: string;
  pubKey: string;
  sigPub: string;
}

/**
 * Short human-comparable safety number for one pairing session.
 * SHA-256 over a domain-separated canonical statement, first 150 bits as
 * five 30-bit groups rendered as 6-digit numbers. Both clients (web and
 * Android) must derive byte-identical output; a golden vector is pinned in
 * the unit tests of each platform.
 */
export function pairingSafetyNumber(fields: SafetyNumberFields): string {
  const canonical =
    "securemsg-pairing-safety-v1\n" +
    `nonce_new=${fields.nonceNew}\n` +
    `nonce_approver=${fields.nonceApprover}\n` +
    `sid=${fields.sid}\n` +
    `pub_key=${fields.pubKey}\n` +
    `sig_pub=${fields.sigPub}\n`;
  const digest = sodium.crypto_hash_sha256(
    new TextEncoder().encode(canonical),
  );
  const n = digest.slice(0, 25).reduce((acc, byte) => (acc << 8n) | BigInt(byte), 0n);
  const groups: string[] = [];
  for (let i = 0; i < 5; i += 1) {
    const group = Number((n >> BigInt(120 - 30 * i)) & 0x3fffffffn);
    groups.push(String(group % 1_000_000).padStart(6, "0"));
  }
  return groups.join("-");
}

export function randomPairingNonce(): string {
  // initCrypto() must have resolved before this is called.
  const bytes = sodium.randombytes_buf(32);
  return sodium.to_base64(bytes, sodium.base64_variants.URLSAFE_NO_PADDING);
}

export async function preparePairingCrypto(): Promise<void> {
  await initCrypto();
}
