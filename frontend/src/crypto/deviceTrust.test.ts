import { beforeAll, describe, expect, it } from "vitest";
import { b64u, generateKeypair, initCrypto, type DeviceKeypair } from "./keys";
import type { ApprovalCertificate, RevocationCertificate } from "../net/api";
import {
  accountSafetyNumber,
  canonicalDeviceApproval,
  canonicalDeviceApprovalV2,
  signDeviceApprovalV2,
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

  describe("QR pairing (v2) approval certificates", () => {
    // Literals, not b64u(...): a describe body runs at collection time,
    // before beforeAll's initCrypto(), so calling into libsodium here races
    // its WASM startup. It won locally and lost on CI.
    const NONCE_NEW = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM";
    const NONCE_APPROVER = "BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ";
    const PAIRING = { pairingId: "pair_abc-123", nonceNew: NONCE_NEW, nonceApprover: NONCE_APPROVER };

    const buildPairedProof = () => {
      const root = generateKeypair();
      const phone = generateKeypair();
      const active = [
        { sid: "root", pub_key: root.box.pk, sig_pub: root.sign.pk, kind: "web" as const },
        { sid: "phone", pub_key: phone.box.pk, sig_pub: phone.sign.pk, kind: "android_gateway" as const },
      ];
      const fields = {
        uid: 90,
        subjectSid: "phone",
        pubKey: phone.box.pk,
        sigPub: phone.sign.pk,
        kind: "android_gateway",
        challenge: TWO32,
        parentEpoch: 1,
      };
      const proof = {
        user_id: 90,
        identity_sig_pub: root.sign.pk,
        security_epoch: 2,
        security_mode: "verified_v2" as const,
        directory_hash: serverDirectoryHash(active),
        device_history: [
          {
            ...active[0],
            fingerprint: deviceFingerprint(root.box.pk, root.sign.pk).hash,
            trust_state: "approved" as const,
            challenge: ZERO32,
            approved_by_sid: "root",
            verification_state: "verified" as const,
          },
          {
            ...active[1],
            fingerprint: deviceFingerprint(phone.box.pk, phone.sign.pk).hash,
            trust_state: "approved" as const,
            challenge: TWO32,
            approved_by_sid: "root",
            verification_state: "verified" as const,
          },
        ],
        approval_certificates: [{
          subject_sid: "phone",
          approver_sid: "root",
          parent_epoch: 1,
          resulting_epoch: 2,
          statement: canonicalDeviceApprovalV2(fields, PAIRING),
          signature: signDeviceApprovalV2(fields, PAIRING, root.sign.sk),
          created_at: 1,
        }],
        revocation_certificates: [],
        security_upgrade_certificates: [],
      };
      return { proof, active, fields, root };
    };

    it("matches the relay's v2 statement byte-for-byte", () => {
      expect(canonicalDeviceApprovalV2({
        uid: 42,
        subjectSid: "dev_new-01",
        pubKey: ZERO32,
        sigPub: ONE32,
        kind: "android_gateway",
        challenge: TWO32,
        parentEpoch: 7,
      }, PAIRING)).toBe(
        "securemsg-device-approval-v2\n"
        + "uid=42\n"
        + "subject_sid=dev_new-01\n"
        + `pub_key=${ZERO32}\n`
        + `sig_pub=${ONE32}\n`
        + "kind=android_gateway\n"
        + `challenge=${TWO32}\n`
        + "pairing_id=pair_abc-123\n"
        + `nonce_new=${NONCE_NEW}\n`
        + `nonce_approver=${NONCE_APPROVER}\n`
        + "parent_epoch=7\n",
      );
    });

    it("accepts a directory whose device was approved over QR pairing", () => {
      // Before v2 was understood here, ONE such certificate made the whole
      // account's directory unverifiable and locked every client.
      const { proof, active } = buildPairedProof();
      expect(() => verifyDirectoryProof(proof, active)).not.toThrow();
    });

    it("rejects a v2 certificate whose nonces were swapped after signing", () => {
      const { proof, active } = buildPairedProof();
      const tampered = {
        ...proof,
        approval_certificates: [{
          ...proof.approval_certificates[0],
          statement: proof.approval_certificates[0].statement
            .replace(`nonce_approver=${NONCE_APPROVER}`, `nonce_approver=${ZERO32}`),
        }],
      };
      expect(() => verifyDirectoryProof(tampered, active)).toThrow(/approval certificate/);
    });

    it("rejects a statement that claims v2 but carries no pairing binding", () => {
      const { proof, active, fields, root } = buildPairedProof();
      // A v1 body relabelled as v2 must not be re-checked under v1 rules.
      const downgraded = canonicalDeviceApproval(fields)
        .replace("securemsg-device-approval-v1", "securemsg-device-approval-v2");
      const tampered = {
        ...proof,
        approval_certificates: [{
          ...proof.approval_certificates[0],
          statement: downgraded,
          signature: signDeviceApproval(fields, root.sign.sk),
        }],
      };
      expect(() => verifyDirectoryProof(tampered, active)).toThrow(/approval certificate/);
    });
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

  describe("legacy_v1 and grandfathered TOFU directories", () => {
    // A migrated account stamps EVERY device `legacy_tofu`, and the relay still
    // lets any of them approve or revoke, so certificates naming a TOFU actor
    // are legitimately issued. Rejecting them threw on every login and every
    // conversation sync, locking the account out of the web client with no
    // recovery short of DB surgery.
    const entry = (device: {
      sid: string;
      keys: DeviceKeypair;
      challenge: string;
      trustState?: "approved" | "revoked";
      approvedBy?: string;
      verificationState?: "legacy_unverified" | "verified";
    }) => ({
      sid: device.sid,
      kind: "web" as const,
      pub_key: device.keys.box.pk,
      sig_pub: device.keys.sign.pk,
      fingerprint: deviceFingerprint(device.keys.box.pk, device.keys.sign.pk).hash,
      trust_state: device.trustState ?? ("approved" as const),
      challenge: device.challenge,
      approved_by_sid: device.approvedBy ?? "legacy_tofu",
      verification_state: device.verificationState ?? ("legacy_unverified" as const),
    });

    const buildProof = (options: {
      uid: number;
      history: Array<ReturnType<typeof entry>>;
      securityEpoch: number;
      mode?: "legacy_v1" | "verified_v2";
      approvals?: ApprovalCertificate[];
      revocations?: RevocationCertificate[];
    }) => {
      const activeHistory = options.history.filter((device) => device.trust_state === "approved");
      return {
        proof: {
          user_id: options.uid,
          identity_sig_pub: options.history[0].sig_pub,
          security_epoch: options.securityEpoch,
          security_mode: options.mode ?? ("legacy_v1" as const),
          directory_hash: serverDirectoryHash(activeHistory),
          device_history: options.history,
          approval_certificates: options.approvals ?? [],
          revocation_certificates: options.revocations ?? [],
          security_upgrade_certificates: [],
        },
        active: activeHistory.map(({ sid, pub_key, sig_pub, kind }) => ({ sid, pub_key, sig_pub, kind })),
      };
    };

    it("accepts a root-only legacy_v1 directory stamped legacy_tofu", () => {
      const root = generateKeypair();
      const { proof, active } = buildProof({
        uid: 80,
        securityEpoch: 4,
        history: [entry({ sid: "root", keys: root, challenge: ZERO32 })],
      });
      expect(() => verifyDirectoryProof(proof, active)).not.toThrow();
    });

    it("accepts a legacy_v1 peer that carries no approval certificate", () => {
      const root = generateKeypair();
      const peer = generateKeypair();
      const { proof, active } = buildProof({
        uid: 80,
        securityEpoch: 4,
        history: [
          entry({ sid: "root", keys: root, challenge: ZERO32 }),
          entry({ sid: "peer", keys: peer, challenge: ONE32 }),
        ],
      });
      expect(() => verifyDirectoryProof(proof, active)).not.toThrow();
    });

    it("accepts the revocation of a TOFU peer, whose history entry is already revoked", () => {
      const root = generateKeypair();
      const peer = generateKeypair();
      const fields = {
        uid: 80, subjectSid: "peer", subjectPubKey: peer.box.pk, subjectSigPub: peer.sign.pk,
        actorSid: "root", parentEpoch: 1,
      };
      const { proof, active } = buildProof({
        uid: 80,
        securityEpoch: 2,
        history: [
          entry({ sid: "root", keys: root, challenge: ZERO32 }),
          entry({ sid: "peer", keys: peer, challenge: ONE32, trustState: "revoked" }),
        ],
        revocations: [{
          subject_sid: "peer", actor_sid: "root", parent_epoch: 1, resulting_epoch: 2,
          reason: "user_revoked", statement: canonicalDeviceRevoke(fields),
          signature: signDeviceRevoke(fields, root.sign.sk), created_at: 1,
        }],
      });
      expect(active).toHaveLength(1);
      expect(() => verifyDirectoryProof(proof, active)).not.toThrow();
    });

    it("accepts an approval signed by a TOFU peer rather than by the root", () => {
      const root = generateKeypair();
      const peer = generateKeypair();
      const laptop = generateKeypair();
      const fields = {
        uid: 80, subjectSid: "laptop", pubKey: laptop.box.pk, sigPub: laptop.sign.pk,
        kind: "web", challenge: TWO32, parentEpoch: 1,
      };
      const { proof, active } = buildProof({
        uid: 80,
        securityEpoch: 2,
        history: [
          entry({ sid: "root", keys: root, challenge: ZERO32 }),
          entry({ sid: "peer", keys: peer, challenge: ONE32 }),
          entry({ sid: "laptop", keys: laptop, challenge: TWO32, approvedBy: "peer" }),
        ],
        approvals: [{
          subject_sid: "laptop", approver_sid: "peer", parent_epoch: 1, resulting_epoch: 2,
          statement: canonicalDeviceApproval(fields),
          signature: signDeviceApproval(fields, peer.sign.sk), created_at: 1,
        }],
      });
      expect(() => verifyDirectoryProof(proof, active)).not.toThrow();
    });

    it("rejects a legacy_v1 certificate from an epoch the directory has not reached", () => {
      const root = generateKeypair();
      const peer = generateKeypair();
      const fields = {
        uid: 80, subjectSid: "peer", subjectPubKey: peer.box.pk, subjectSigPub: peer.sign.pk,
        actorSid: "root", parentEpoch: 2,
      };
      const { proof, active } = buildProof({
        uid: 80,
        securityEpoch: 2,
        history: [
          entry({ sid: "root", keys: root, challenge: ZERO32 }),
          entry({ sid: "peer", keys: peer, challenge: ONE32, trustState: "revoked" }),
        ],
        revocations: [{
          subject_sid: "peer", actor_sid: "root", parent_epoch: 2, resulting_epoch: 3,
          reason: "user_revoked", statement: canonicalDeviceRevoke(fields),
          signature: signDeviceRevoke(fields, root.sign.sk), created_at: 1,
        }],
      });
      expect(() => verifyDirectoryProof(proof, active)).toThrow(/certificate epoch exceeds directory epoch/);
    });

    it("accepts a verified_v2 directory that still carries a pre-upgrade revoked device", () => {
      // The upgrade cannot heal an already-revoked device: it stays
      // legacy_unverified with no certificate, forever.
      const root = generateKeypair();
      const peer = generateKeypair();
      const { proof, active } = buildProof({
        uid: 80,
        securityEpoch: 1,
        mode: "verified_v2",
        history: [
          entry({ sid: "root", keys: root, challenge: ZERO32, verificationState: "verified" }),
          entry({ sid: "peer", keys: peer, challenge: ONE32, trustState: "revoked" }),
        ],
      });
      expect(() => verifyDirectoryProof(proof, active)).not.toThrow();
    });

    it("still rejects an ACTIVE TOFU device in a verified_v2 directory", () => {
      const root = generateKeypair();
      const peer = generateKeypair();
      const unverified = buildProof({
        uid: 80,
        securityEpoch: 1,
        mode: "verified_v2",
        history: [
          entry({ sid: "root", keys: root, challenge: ZERO32, verificationState: "verified" }),
          entry({ sid: "peer", keys: peer, challenge: ONE32 }),
        ],
      });
      expect(() => verifyDirectoryProof(unverified.proof, unverified.active)).toThrow(/unverified device/);

      const relabelled = buildProof({
        uid: 80,
        securityEpoch: 1,
        mode: "verified_v2",
        history: [
          entry({ sid: "root", keys: root, challenge: ZERO32, verificationState: "verified" }),
          entry({ sid: "peer", keys: peer, challenge: ONE32, verificationState: "verified" }),
        ],
      });
      expect(() => verifyDirectoryProof(relabelled.proof, relabelled.active))
        .toThrow(/no valid approval certificate/);
    });
  });
});
