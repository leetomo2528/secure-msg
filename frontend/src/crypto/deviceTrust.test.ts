import { beforeAll, describe, expect, it } from "vitest";
import { b64u, generateKeypair, initCrypto } from "./keys";
import {
  accountSafetyNumber,
  canonicalDeviceApproval,
  deviceFingerprint,
  signDeviceApproval,
  verifyDeviceApproval,
  serverDirectoryHash,
  verifyDirectoryProof,
  canonicalDeviceRevoke,
  signDeviceRevoke,
  verifyDeviceRevoke,
  canonicalLegacyUpgrade,
  signLegacyUpgrade,
} from "./deviceTrust";

const ZERO32 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
const ONE32 = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE";
const TWO32 = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI";

describe("trusted-device crypto", () => {
  beforeAll(async () => { await initCrypto(); });

  it("matches the exact golden approval statement byte-for-byte", () => {
    expect(canonicalDeviceApproval({
      uid: 42,
      subjectSid: "dev_new-01",
      pubKey: ZERO32,
      sigPub: ONE32,
      kind: "android_gateway",
      challenge: TWO32,
      parentEpoch: 7,
    })).toBe(
      "securemsg-device-approval-v1\n"
      + "uid=42\n"
      + "subject_sid=dev_new-01\n"
      + `pub_key=${ZERO32}\n`
      + `sig_pub=${ONE32}\n`
      + "kind=android_gateway\n"
      + `challenge=${TWO32}\n`
      + "parent_epoch=7\n",
    );
  });

  it("signs and verifies the canonical statement with Ed25519", () => {
    const signer = generateKeypair();
    const subject = generateKeypair();
    const fields = {
      uid: 7, subjectSid: "subject", pubKey: subject.box.pk,
      sigPub: subject.sign.pk, kind: "web", challenge: b64u(new Uint8Array(32).fill(9)), parentEpoch: 3,
    };
    const signature = signDeviceApproval(fields, signer.sign.sk);
    expect(verifyDeviceApproval(fields, signature, signer.sign.pk)).toBe(true);
    expect(verifyDeviceApproval({ ...fields, subjectSid: "attacker" }, signature, signer.sign.pk)).toBe(false);
    expect(verifyDeviceApproval({ ...fields, parentEpoch: 4 }, signature, signer.sign.pk)).toBe(false);
    const changed = new Uint8Array(64);
    changed.set(new Uint8Array(64));
    expect(verifyDeviceApproval(fields, b64u(changed), signer.sign.pk)).toBe(false);
  });

  it("matches and authenticates the exact signed-revoke statement", () => {
    const actor = generateKeypair();
    const fields = {
      uid: 42,
      subjectSid: "subject",
      subjectPubKey: ZERO32,
      subjectSigPub: ONE32,
      actorSid: "actor",
      parentEpoch: 8,
    };
    expect(canonicalDeviceRevoke(fields)).toBe(
      "securemsg-device-revoke-v1\nuid=42\nsubject_sid=subject\n"
      + `subject_pub_key=${ZERO32}\nsubject_sig_pub=${ONE32}\n`
      + "actor_sid=actor\nparent_epoch=8\nreason=user_revoked\n",
    );
    const signature = signDeviceRevoke(fields, actor.sign.sk);
    expect(verifyDeviceRevoke(fields, signature, actor.sign.pk)).toBe(true);
    expect(verifyDeviceRevoke({ ...fields, parentEpoch: 9 }, signature, actor.sign.pk)).toBe(false);
  });

  it("matches the exact legacy security-upgrade statement", () => {
    const root = generateKeypair();
    const fields = { uid: 5, identitySid: "root", identitySigPub: root.sign.pk, parentEpoch: 11 };
    expect(canonicalLegacyUpgrade(fields)).toBe(
      `securemsg-legacy-upgrade-v1\nuid=5\nidentity_sid=root\nidentity_sig_pub=${root.sign.pk}\nparent_epoch=11\n`,
    );
    expect(signLegacyUpgrade(fields, root.sign.sk)).toMatch(/^[A-Za-z0-9_-]{86}$/);
  });

  it("rejects newline injection and non-canonical/wrong-length keys", () => {
    expect(() => canonicalDeviceApproval({
      uid: 1, subjectSid: "safe\nuid=999", pubKey: ZERO32, sigPub: ONE32,
      kind: "web", challenge: TWO32, parentEpoch: 0,
    })).toThrow(/single line/);
    expect(() => canonicalDeviceApproval({
      uid: 1, subjectSid: "safe", pubKey: "AA", sigPub: ONE32,
      kind: "web", challenge: TWO32, parentEpoch: 0,
    })).toThrow(/32 bytes/);
  });

  it("derives stable, domain-separated fingerprints", () => {
    const first = deviceFingerprint(ZERO32, ONE32);
    const same = deviceFingerprint(ZERO32, ONE32);
    const changed = deviceFingerprint(ZERO32, TWO32);
    expect(first).toEqual(same);
    expect(first.hash).not.toBe(changed.hash);
    expect(first.display.split(" ")).toHaveLength(16);
    expect(first.qrPayload).toContain("securemsg://device-fingerprint/v1");
  });

  it("keeps the account Safety Number stable because it uses only the immutable identity key", () => {
    const first = accountSafetyNumber(TWO32);
    const repeated = accountSafetyNumber(TWO32);
    expect(first).toEqual(repeated);
    expect(first.digits).toMatch(/^\d{72}$/);
    expect(first.display.split(" ")).toHaveLength(12);
    expect(first.qrPayload).toContain("identity_sig_pub=");
    expect(accountSafetyNumber(ONE32).hash).not.toBe(first.hash);
  });

  it("matches the relay's canonical SHA-256 directory hash and ordering", () => {
    const devices = [
      { sid: "z", pub_key: ZERO32, sig_pub: ONE32, kind: "web" },
      { sid: "a", pub_key: ONE32, sig_pub: TWO32, kind: "android_gateway" },
    ];
    expect(serverDirectoryHash(devices)).toBe(serverDirectoryHash([...devices].reverse()));
    expect(serverDirectoryHash(devices)).toBe("WhyUnwZYhm5sO6L6Dy4sStlx2nM3w6elFu228j-pT5M");
  });

  it("accepts a root-only verified_v2 directory at security epoch 1", () => {
    const root = generateKeypair();
    const active = [{ sid: "root", pub_key: root.box.pk, sig_pub: root.sign.pk, kind: "web" as const }];
    const proof = {
      user_id: 75,
      identity_sig_pub: root.sign.pk,
      security_epoch: 1,
      security_mode: "verified_v2" as const,
      directory_hash: serverDirectoryHash(active),
      device_history: [{
        ...active[0],
        fingerprint: deviceFingerprint(root.box.pk, root.sign.pk).hash,
        trust_state: "approved" as const,
        challenge: ZERO32,
        approved_by_sid: "root",
        verification_state: "verified" as const,
      }],
      approval_certificates: [],
      revocation_certificates: [],
      security_upgrade_certificates: [],
    };

    expect(() => verifyDirectoryProof(proof, active)).not.toThrow();
  });

  it("accepts a verified_v2 legacy-upgrade chain that starts after epoch 1", () => {
    const root = generateKeypair();
    const fields = { uid: 76, identitySid: "root", identitySigPub: root.sign.pk, parentEpoch: 11 };
    const active = [{ sid: "root", pub_key: root.box.pk, sig_pub: root.sign.pk, kind: "web" as const }];
    const proof = {
      user_id: 76,
      identity_sig_pub: root.sign.pk,
      security_epoch: 12,
      security_mode: "verified_v2" as const,
      directory_hash: serverDirectoryHash(active),
      device_history: [{
        ...active[0],
        fingerprint: deviceFingerprint(root.box.pk, root.sign.pk).hash,
        trust_state: "approved" as const,
        challenge: ZERO32,
        approved_by_sid: "legacy_tofu",
        verification_state: "verified" as const,
      }],
      approval_certificates: [],
      revocation_certificates: [],
      security_upgrade_certificates: [{
        identity_sid: "root",
        parent_epoch: 11,
        resulting_epoch: 12,
        statement: canonicalLegacyUpgrade(fields),
        signature: signLegacyUpgrade(fields, root.sign.sk),
        created_at: 1,
      }],
    };

    expect(() => verifyDirectoryProof(proof, active)).not.toThrow();
  });

  it("rejects an inflated verified_v2 security epoch after the certificate chain", () => {
    const root = generateKeypair();
    const fields = { uid: 76, identitySid: "root", identitySigPub: root.sign.pk, parentEpoch: 11 };
    const active = [{ sid: "root", pub_key: root.box.pk, sig_pub: root.sign.pk, kind: "web" as const }];
    const proof = {
      user_id: 76,
      identity_sig_pub: root.sign.pk,
      security_epoch: 13,
      security_mode: "verified_v2" as const,
      directory_hash: serverDirectoryHash(active),
      device_history: [{
        ...active[0],
        fingerprint: deviceFingerprint(root.box.pk, root.sign.pk).hash,
        trust_state: "approved" as const,
        challenge: ZERO32,
        approved_by_sid: "legacy_tofu",
        verification_state: "verified" as const,
      }],
      approval_certificates: [],
      revocation_certificates: [],
      security_upgrade_certificates: [{
        identity_sid: "root",
        parent_epoch: 11,
        resulting_epoch: 12,
        statement: canonicalLegacyUpgrade(fields),
        signature: signLegacyUpgrade(fields, root.sign.sk),
        created_at: 1,
      }],
    };

    expect(() => verifyDirectoryProof(proof, active)).toThrow(/certificate epoch/);
  });

  it("verifies an anchored approval certificate chain, including revoked approvers", () => {
    const root = generateKeypair();
    const subject = generateKeypair();
    const challenge = b64u(new Uint8Array(32).fill(7));
    const fields = {
      uid: 77, subjectSid: "subject", pubKey: subject.box.pk, sigPub: subject.sign.pk,
      kind: "web", challenge, parentEpoch: 1,
    };
    const signature = signDeviceApproval(fields, root.sign.sk);
    const revokeFields = {
      uid: 77, subjectSid: "root", subjectPubKey: root.box.pk, subjectSigPub: root.sign.pk,
      actorSid: "subject", parentEpoch: 2,
    };
    const revokeSignature = signDeviceRevoke(revokeFields, subject.sign.sk);
    const active = [{ sid: "subject", pub_key: subject.box.pk, sig_pub: subject.sign.pk, kind: "web" as const }];
    const proof = {
      user_id: 77,
      identity_sig_pub: root.sign.pk,
      security_epoch: 3, // approval result 2, then root revoke increments to 3
      security_mode: "verified_v2" as const,
      directory_hash: serverDirectoryHash(active),
      device_history: [
        {
          sid: "root", kind: "web" as const, pub_key: root.box.pk, sig_pub: root.sign.pk,
          fingerprint: deviceFingerprint(root.box.pk, root.sign.pk).hash, trust_state: "revoked" as const, challenge: ZERO32,
          approved_by_sid: "root", verification_state: "verified" as const,
        },
        {
          sid: "subject", kind: "web" as const, pub_key: subject.box.pk, sig_pub: subject.sign.pk,
          fingerprint: deviceFingerprint(subject.box.pk, subject.sign.pk).hash, trust_state: "approved" as const, challenge,
          approved_by_sid: "root", verification_state: "verified" as const,
        },
      ],
      approval_certificates: [{
        subject_sid: "subject", approver_sid: "root", parent_epoch: 1, resulting_epoch: 2,
        statement: canonicalDeviceApproval(fields), signature, created_at: 1,
      }],
      revocation_certificates: [{
        subject_sid: "root", actor_sid: "subject", parent_epoch: 2, resulting_epoch: 3,
        reason: "user_revoked" as const, statement: canonicalDeviceRevoke(revokeFields),
        signature: revokeSignature, created_at: 2,
      }],
      security_upgrade_certificates: [],
    };
    expect(() => verifyDirectoryProof(proof, active)).not.toThrow();
    expect(() => verifyDirectoryProof({
      ...proof,
      approval_certificates: [{ ...proof.approval_certificates[0], statement: `${proof.approval_certificates[0].statement}x` }],
    }, active)).toThrow(/certificate/);
  });
});
