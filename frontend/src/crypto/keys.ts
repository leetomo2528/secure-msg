/**
 * Crypto layer: libsodium-based envelope encryption.
 *
 * Design (see docs/THREAT_MODEL.md):
 *   - Each device has an X25519 keypair (crypto_box) and an Ed25519 signing keypair.
 *   - Private keys live in IndexedDB only. Public keys are sent to the server.
 *   - For each message, sender generates a random 32-byte `message_key`.
 *   - The plaintext is sealed with crypto_secretbox(message_key, nonce).
 *   - For EACH recipient/sender device, the message_key is wrapped with
 *     crypto_box(recipient_pubkey, sender_seckey, box_nonce) -> `ek`.
 *   - The server stores {ct, nonce, keys: { device_sid -> {ek, n} }} and fans out.
 *   - Each device unwraps its own `ek` with its private key, then decrypts `ct`.
 *
 * Tradeoff (honest): this is "envelope encryption", not Signal's Double Ratchet.
 * It is strong against a passive server and against network eavesdroppers. It
 * does NOT provide forward secrecy against device-key compromise. If an
 * attacker gets a device's private key AND captures envelope ciphertexts from
 * the server, they can decrypt past messages addressed to that device.
 */
import sodium from "libsodium-wrappers-sumo";

let ready: Promise<void> | null = null;
export function initCrypto(): Promise<void> {
  if (!ready) ready = sodium.ready;
  return ready;
}

// ----- base64 helpers (url-safe, no padding) ---------------------------

export function b64u(bytes: Uint8Array): string {
  return sodium.to_base64(bytes, sodium.base64_variants.URLSAFE_NO_PADDING);
}
export function unb64u(s: string): Uint8Array {
  return sodium.from_base64(s, sodium.base64_variants.URLSAFE_NO_PADDING);
}

// ----- keypairs --------------------------------------------------------

export interface DeviceKeypair {
  /** X25519 (crypto_box) for encryption. */
  box: { pk: string; sk: string };
  /** Ed25519 (crypto_sign) for message authenticity. */
  sign: { pk: string; sk: string };
}

export function generateKeypair(): DeviceKeypair {
  const box = sodium.crypto_box_keypair();
  const sign = sodium.crypto_sign_keypair();
  return {
    box: { pk: b64u(box.publicKey), sk: b64u(box.privateKey) },
    sign: { pk: b64u(sign.publicKey), sk: b64u(sign.privateKey) },
  };
}

// ----- password hashing (client-side Argon2id over raw password) -------

export async function hashPassword(password: string, saltB64?: string): Promise<string> {
  // Argon2id with a per-user salt. Salt is derived from the username so the
  // same (username, password) always yields the same hash — the server
  // compares bcrypt(this_hash, stored). Salt is NOT secret; it just forces
  // attackers to recompute per-user.
  const salt = saltB64 ? unb64u(saltB64) : sodium.randombytes_buf(16);
  const hash = sodium.crypto_pwhash(
    32,
    password,
    salt,
    sodium.crypto_pwhash_OPSLIMIT_INTERACTIVE,
    sodium.crypto_pwhash_MEMLIMIT_INTERACTIVE,
    sodium.crypto_pwhash_ALG_DEFAULT,
  );
  return b64u(hash);
}

export function saltForUser(username: string): string {
  // Deterministic salt from username — keeps registration/login consistent
  // without needing to transmit the salt separately.
  return b64u(
    sodium.crypto_generichash(16, username.toLowerCase().normalize("NFKC"), null),
  );
}

// ----- envelope encrypt/decrypt ----------------------------------------

export interface EnvelopeKey {
  /** Wrapped message_key (crypto_box), base64. */
  ek: string;
  /** Box nonce, base64. */
  n: string;
}

export interface Envelope {
  /** secretbox ciphertext of plaintext, base64. */
  ct: string;
  /** secretbox nonce, base64. */
  nonce: string;
  /** Per-device wrapped keys: { device_sid -> EnvelopeKey }. */
  keys: Record<string, EnvelopeKey>;
}

export interface RecipientDevice {
  sid: string;
  pub_key: string; // X25519 public key, base64 url-safe
}

export async function encryptMessage(
  plaintext: string,
  recipients: RecipientDevice[],
  sender: DeviceKeypair,
): Promise<Envelope> {
  const messageKey = sodium.randombytes_buf(sodium.crypto_secretbox_KEYBYTES);
  const nonce = sodium.randombytes_buf(sodium.crypto_secretbox_NONCEBYTES);
  const ct = sodium.crypto_secretbox_easy(plaintext, nonce, messageKey);

  const senderSk = unb64u(sender.box.sk);
  const keys: Record<string, EnvelopeKey> = {};
  for (const r of recipients) {
    const rPk = unb64u(r.pub_key);
    const boxNonce = sodium.randombytes_buf(sodium.crypto_box_NONCEBYTES);
    const ek = sodium.crypto_box_easy(messageKey, boxNonce, rPk, senderSk);
    keys[r.sid] = { ek: b64u(ek), n: b64u(boxNonce) };
  }
  return { ct: b64u(ct), nonce: b64u(nonce), keys };
}

/**
 * Decrypt with known sender pubkey. The caller resolves sender device pubkey
 * from the conversation's member list (cached in store).
 */
export function decryptMessageWithSender(
  env: Envelope,
  myDeviceSid: string,
  myKeypair: DeviceKeypair,
  senderDevicePubkeyB64: string,
): string | null {
  try {
    const myKey = env?.keys?.[myDeviceSid];
    if (!myKey) return null;
    const mySk = unb64u(myKeypair.box.sk);
    const senderPk = unb64u(senderDevicePubkeyB64);
    const messageKey = sodium.crypto_box_open_easy(
      unb64u(myKey.ek),
      unb64u(myKey.n),
      senderPk,
      mySk,
    );
    const plain = sodium.crypto_secretbox_open_easy(
      unb64u(env.ct),
      unb64u(env.nonce),
      messageKey,
    );
    return sodium.to_string(plain);
  } catch {
    return null; // malformed, tampered, or encrypted by another sender/device
  }
}
