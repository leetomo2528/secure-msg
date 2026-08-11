import sodium from "libsodium-wrappers-sumo";
import { b64u, unb64u } from "./keys";
import type { AccountDevice, DirectoryProof } from "../net/api";

export const DEVICE_APPROVAL_DOMAIN = "securemsg-device-approval-v1";
const DEVICE_FINGERPRINT_DOMAIN = "securemsg-device-fingerprint-v1\n";
const ACCOUNT_SAFETY_NUMBER_DOMAIN = "securemsg-account-safety-v1\n";

export interface DeviceApprovalFields {
  uid: number;
  subjectSid: string;
  pubKey: string;
  sigPub: string;
  kind: string;
  challenge: string;
  parentEpoch: number;
}

export interface DeviceRevokeFields {
  uid: number;
  subjectSid: string;
  subjectPubKey: string;
  subjectSigPub: string;
  actorSid: string;
  parentEpoch: number;
}

export interface LegacyUpgradeFields {
  uid: number;
  identitySid: string;
  identitySigPub: string;
  parentEpoch: number;
}

export interface SafetyNumberDevice {
  sid: string;
  pub_key: string;
  sig_pub: string;
}

const encoder = new TextEncoder();

function assertDecimalInteger(value: number, label: string): void {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error(`${label} must be a non-negative integer`);
}

function assertToken(value: string, label: string): void {
  if (!value || /[\r\n]/.test(value)) throw new Error(`${label} must be a non-empty single line`);
}

function assertKey(value: string, expectedBytes: number, label: string): void {
  let decoded: Uint8Array;
  try { decoded = unb64u(value); } catch { throw new Error(`${label} must be base64url`); }
  if (decoded.length !== expectedBytes || b64u(decoded) !== value) {
    throw new Error(`${label} must be canonical base64url for ${expectedBytes} bytes`);
  }
}

/** Canonical bytes signed by an already-approved device for a pending device. */
export function canonicalDeviceApproval(fields: DeviceApprovalFields): string {
  assertDecimalInteger(fields.uid, "uid");
  assertDecimalInteger(fields.parentEpoch, "parent_epoch");
  assertToken(fields.subjectSid, "subject_sid");
  assertToken(fields.kind, "kind");
  assertKey(fields.pubKey, sodium.crypto_box_PUBLICKEYBYTES, "pub_key");
  assertKey(fields.sigPub, sodium.crypto_sign_PUBLICKEYBYTES, "sig_pub");
  assertKey(fields.challenge, 32, "challenge");
  return `${DEVICE_APPROVAL_DOMAIN}\nuid=${fields.uid}\nsubject_sid=${fields.subjectSid}\npub_key=${fields.pubKey}\nsig_pub=${fields.sigPub}\nkind=${fields.kind}\nchallenge=${fields.challenge}\nparent_epoch=${fields.parentEpoch}\n`;
}

export function signDeviceApproval(fields: DeviceApprovalFields, signingSecretKey: string): string {
  assertKey(signingSecretKey, sodium.crypto_sign_SECRETKEYBYTES, "signing secret key");
  return b64u(sodium.crypto_sign_detached(encoder.encode(canonicalDeviceApproval(fields)), unb64u(signingSecretKey)));
}

export function verifyDeviceApproval(fields: DeviceApprovalFields, signature: string, signerPublicKey: string): boolean {
  try {
    assertKey(signature, sodium.crypto_sign_BYTES, "signature");
    assertKey(signerPublicKey, sodium.crypto_sign_PUBLICKEYBYTES, "signer public key");
    return sodium.crypto_sign_verify_detached(
      unb64u(signature),
      encoder.encode(canonicalDeviceApproval(fields)),
      unb64u(signerPublicKey),
    );
  } catch {
    return false;
  }
}

export function canonicalDeviceRevoke(fields: DeviceRevokeFields): string {
  assertDecimalInteger(fields.uid, "uid");
  assertDecimalInteger(fields.parentEpoch, "parent_epoch");
  assertToken(fields.subjectSid, "subject_sid");
  assertToken(fields.actorSid, "actor_sid");
  assertKey(fields.subjectPubKey, sodium.crypto_box_PUBLICKEYBYTES, "subject_pub_key");
  assertKey(fields.subjectSigPub, sodium.crypto_sign_PUBLICKEYBYTES, "subject_sig_pub");
  return `securemsg-device-revoke-v1\nuid=${fields.uid}\nsubject_sid=${fields.subjectSid}\nsubject_pub_key=${fields.subjectPubKey}\nsubject_sig_pub=${fields.subjectSigPub}\nactor_sid=${fields.actorSid}\nparent_epoch=${fields.parentEpoch}\nreason=user_revoked\n`;
}

export function signDeviceRevoke(fields: DeviceRevokeFields, signingSecretKey: string): string {
  assertKey(signingSecretKey, sodium.crypto_sign_SECRETKEYBYTES, "signing secret key");
  return b64u(sodium.crypto_sign_detached(encoder.encode(canonicalDeviceRevoke(fields)), unb64u(signingSecretKey)));
}

export function verifyDeviceRevoke(fields: DeviceRevokeFields, signature: string, signerPublicKey: string): boolean {
  try {
    return sodium.crypto_sign_verify_detached(
      unb64u(signature), encoder.encode(canonicalDeviceRevoke(fields)), unb64u(signerPublicKey),
    );
  } catch { return false; }
}

export function canonicalLegacyUpgrade(fields: LegacyUpgradeFields): string {
  assertDecimalInteger(fields.uid, "uid");
  assertDecimalInteger(fields.parentEpoch, "parent_epoch");
  assertToken(fields.identitySid, "identity_sid");
  assertKey(fields.identitySigPub, sodium.crypto_sign_PUBLICKEYBYTES, "identity_sig_pub");
  return `securemsg-legacy-upgrade-v1\nuid=${fields.uid}\nidentity_sid=${fields.identitySid}\nidentity_sig_pub=${fields.identitySigPub}\nparent_epoch=${fields.parentEpoch}\n`;
}

export function signLegacyUpgrade(fields: LegacyUpgradeFields, signingSecretKey: string): string {
  assertKey(signingSecretKey, sodium.crypto_sign_SECRETKEYBYTES, "signing secret key");
  return b64u(sodium.crypto_sign_detached(encoder.encode(canonicalLegacyUpgrade(fields)), unb64u(signingSecretKey)));
}

function hashDomainSeparated(domain: string, body: string): Uint8Array {
  return sodium.crypto_hash_sha256(encoder.encode(domain + body));
}

function decimalDigits(bytes: Uint8Array): string {
  // Fold 30 bytes into exactly 72 decimal digits for stable visual grouping.
  let value = 0n;
  for (const byte of bytes.slice(0, 30)) value = (value << 8n) | BigInt(byte);
  value %= 10n ** 72n;
  return value.toString(10).padStart(72, "0");
}

export function formatSafetyDigits(digits: string): string {
  return digits.match(/.{1,6}/g)?.join(" ") ?? digits;
}

export function deviceFingerprint(pubKey: string, sigPub: string): { hash: string; display: string; qrPayload: string } {
  assertKey(pubKey, sodium.crypto_box_PUBLICKEYBYTES, "pub_key");
  assertKey(sigPub, sodium.crypto_sign_PUBLICKEYBYTES, "sig_pub");
  const body = `pub_key=${pubKey}\nsig_pub=${sigPub}\n`;
  const hash = b64u(hashDomainSeparated(DEVICE_FINGERPRINT_DOMAIN, body));
  const hex = Array.from(unb64u(hash), (byte) => byte.toString(16).padStart(2, "0")).join("");
  return {
    hash,
    display: hex.match(/.{1,4}/g)?.join(" ").toUpperCase() ?? hex.toUpperCase(),
    qrPayload: `securemsg://device-fingerprint/v1?pub_key=${encodeURIComponent(pubKey)}&sig_pub=${encodeURIComponent(sigPub)}&hash=${encodeURIComponent(hash)}`,
  };
}

export function accountSafetyNumber(
  identitySigPub: string,
  uid?: number,
): { hash: string; digits: string; display: string; qrPayload: string } {
  if (uid != null) assertDecimalInteger(uid, "uid");
  assertKey(identitySigPub, sodium.crypto_sign_PUBLICKEYBYTES, "identity_sig_pub");
  const body = `identity_sig_pub=${identitySigPub}\n`;
  const hash = b64u(hashDomainSeparated(ACCOUNT_SAFETY_NUMBER_DOMAIN, body));
  const digits = decimalDigits(unb64u(hash));
  return {
    hash,
    digits,
    display: formatSafetyDigits(digits),
    qrPayload: `securemsg://account-safety/v1?${uid == null ? "" : `uid=${uid}&`}identity_sig_pub=${encodeURIComponent(identitySigPub)}&hash=${encodeURIComponent(hash)}`,
  };
}

/** Stable local calculation used to detect same-epoch split views. */
/** Exact relay contract: SHA-256(JSON [[sid,box,sign,kind], ...sorted by SID]). */
export function serverDirectoryHash(devices: Array<SafetyNumberDevice & { kind: string }>): string {
  const records = [...devices]
    .sort((a, b) => a.sid < b.sid ? -1 : a.sid > b.sid ? 1 : 0)
    .map((device) => [device.sid, device.pub_key, device.sig_pub, device.kind]);
  return b64u(sodium.crypto_hash_sha256(encoder.encode(JSON.stringify(records))));
}

export function recipientKeysetHash(devices: Array<SafetyNumberDevice & { user_id: number }>): string {
  const records = [...devices]
    .sort((a, b) => a.user_id - b.user_id || (a.sid < b.sid ? -1 : a.sid > b.sid ? 1 : 0))
    .map((device) => [device.user_id, device.sid, device.pub_key, device.sig_pub]);
  return b64u(sodium.crypto_hash_sha256(encoder.encode(JSON.stringify(records))));
}

/** Verify the device cross-signature history before accepting a directory. */
export function verifyDirectoryProof(
  proof: DirectoryProof,
  activeDevices: Array<Pick<AccountDevice, "sid" | "pub_key" | "sig_pub" | "kind">>,
): void {
  assertDecimalInteger(proof.user_id, "user_id");
  assertDecimalInteger(proof.security_epoch, "security_epoch");
  assertKey(proof.identity_sig_pub, sodium.crypto_sign_PUBLICKEYBYTES, "identity_sig_pub");
  if (proof.security_mode !== "legacy_v1" && proof.security_mode !== "verified_v2") {
    throw new Error("directory proof security mode missing");
  }
  if (!Array.isArray(proof.device_history) || proof.device_history.length === 0) {
    throw new Error("directory proof has no root device");
  }
  if (!Array.isArray(proof.approval_certificates)) throw new Error("directory proof certificates missing");
  if (!Array.isArray(proof.revocation_certificates)) throw new Error("directory proof revocations missing");
  if (!Array.isArray(proof.security_upgrade_certificates)) throw new Error("directory proof upgrades missing");

  const history = new Map<string, DirectoryProof["device_history"][number]>();
  for (const device of proof.device_history) {
    if (history.has(device.sid)) throw new Error("duplicate SID in device history");
    assertToken(device.sid, "history sid");
    assertToken(device.kind, "history kind");
    assertKey(device.pub_key, sodium.crypto_box_PUBLICKEYBYTES, "history pub_key");
    assertKey(device.sig_pub, sodium.crypto_sign_PUBLICKEYBYTES, "history sig_pub");
    assertKey(device.challenge, 32, "history challenge");
    if (device.fingerprint !== deviceFingerprint(device.pub_key, device.sig_pub).hash) {
      throw new Error("device history fingerprint mismatch");
    }
    if (proof.security_mode === "verified_v2" && device.verification_state !== "verified") {
      throw new Error("verified directory contains an unverified device");
    }
    history.set(device.sid, device);
  }

  const root = proof.device_history[0];
  if (root.sig_pub !== proof.identity_sig_pub) throw new Error("root signing key does not match account identity");
  if (root.approved_by_sid !== root.sid && root.approved_by_sid !== "legacy_tofu") {
    throw new Error("invalid root device authorization");
  }
  const authorized = new Map<string, DirectoryProof["device_history"][number]>([[root.sid, root]]);
  const activeAtEpoch = new Set<string>([root.sid]);
  const certifiedSubjects = new Set<string>();
  const events = [
    ...proof.approval_certificates.map((certificate) => ({ type: "approval" as const, certificate })),
    ...proof.revocation_certificates.map((certificate) => ({ type: "revocation" as const, certificate })),
    ...proof.security_upgrade_certificates.map((certificate) => ({ type: "upgrade" as const, certificate })),
  ].sort((a, b) => a.certificate.resulting_epoch - b.certificate.resulting_epoch);
  let previousResultingEpoch: number | null = null;
  for (const event of events) {
    const certificate = event.certificate;
    assertDecimalInteger(certificate.parent_epoch, "certificate parent_epoch");
    assertDecimalInteger(certificate.resulting_epoch, "certificate resulting_epoch");
    if (certificate.resulting_epoch !== certificate.parent_epoch + 1
      || (previousResultingEpoch != null && certificate.parent_epoch !== previousResultingEpoch)) {
      throw new Error("non-monotonic approval certificate epoch");
    }
    if (event.type === "approval") {
      const approval = event.certificate;
      const approver = authorized.get(approval.approver_sid);
      const subject = history.get(approval.subject_sid);
      if (!approver || !activeAtEpoch.has(approver.sid) || !subject
        || certifiedSubjects.has(subject.sid) || authorized.has(subject.sid)) {
        throw new Error("approval certificate chain is not anchored");
      }
      const fields: DeviceApprovalFields = {
        uid: proof.user_id,
        subjectSid: subject.sid,
        pubKey: subject.pub_key,
        sigPub: subject.sig_pub,
        kind: subject.kind,
        challenge: subject.challenge,
        parentEpoch: approval.parent_epoch,
      };
      if (approval.statement !== canonicalDeviceApproval(fields)
        || !verifyDeviceApproval(fields, approval.signature, approver.sig_pub)
        || approval.approver_sid !== subject.approved_by_sid) {
        throw new Error("invalid device approval certificate signature or statement");
      }
      authorized.set(subject.sid, subject);
      activeAtEpoch.add(subject.sid);
      certifiedSubjects.add(subject.sid);
    } else if (event.type === "revocation") {
      const revocation = event.certificate;
      const actor = authorized.get(revocation.actor_sid);
      const subject = authorized.get(revocation.subject_sid);
      if (!actor || !subject || !activeAtEpoch.has(actor.sid) || !activeAtEpoch.has(subject.sid)
        || revocation.reason !== "user_revoked") {
        throw new Error("revocation certificate chain is not anchored");
      }
      const fields: DeviceRevokeFields = {
        uid: proof.user_id,
        subjectSid: subject.sid,
        subjectPubKey: subject.pub_key,
        subjectSigPub: subject.sig_pub,
        actorSid: actor.sid,
        parentEpoch: revocation.parent_epoch,
      };
      if (revocation.statement !== canonicalDeviceRevoke(fields)
        || !verifyDeviceRevoke(fields, revocation.signature, actor.sig_pub)) {
        throw new Error("invalid device revocation certificate signature or statement");
      }
      activeAtEpoch.delete(subject.sid);
    } else {
      const upgrade = event.certificate;
      const fields: LegacyUpgradeFields = {
        uid: proof.user_id,
        identitySid: root.sid,
        identitySigPub: root.sig_pub,
        parentEpoch: upgrade.parent_epoch,
      };
      if (upgrade.identity_sid !== root.sid
        || upgrade.statement !== canonicalLegacyUpgrade(fields)
        || !sodium.crypto_sign_verify_detached(
          unb64u(upgrade.signature), encoder.encode(upgrade.statement), unb64u(root.sig_pub),
        )) {
        throw new Error("invalid legacy security upgrade certificate");
      }
    }
    previousResultingEpoch = certificate.resulting_epoch;
  }
  if (previousResultingEpoch != null && previousResultingEpoch > proof.security_epoch) {
    throw new Error("certificate epoch exceeds directory epoch");
  }

  // Grandfathered v1 devices are explicitly TOFU/unverified. Every v2 device
  // must be reachable from the root through a valid approval certificate.
  for (const device of proof.device_history) {
    if (proof.security_mode === "verified_v2" && device.sid !== root.sid && !authorized.has(device.sid)) {
      throw new Error(`device ${device.sid} has no valid approval certificate`);
    }
  }

  const activeHistory = [...history.values()].filter((device) => device.trust_state === "approved");
  if (proof.security_mode === "verified_v2") {
    const finalActive = new Set(activeHistory.map((device) => device.sid));
    if (finalActive.size !== activeAtEpoch.size || [...finalActive].some((sid) => !activeAtEpoch.has(sid))) {
      throw new Error("certificate chain final state does not match active directory");
    }
  }
  const expectedHash = serverDirectoryHash(activeHistory);
  if (expectedHash !== proof.directory_hash) throw new Error("directory proof hash mismatch");
  const activeBySid = new Map(activeDevices.map((device) => [device.sid, device]));
  if (activeBySid.size !== activeHistory.length) throw new Error("active directory does not match device history");
  for (const device of activeHistory) {
    const active = activeBySid.get(device.sid);
    if (!active || active.pub_key !== device.pub_key || active.sig_pub !== device.sig_pub || active.kind !== device.kind) {
      throw new Error("active device keys do not match verified history");
    }
  }
}
