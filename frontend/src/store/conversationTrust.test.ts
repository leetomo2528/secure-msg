import "fake-indexeddb/auto";
import { beforeAll, describe, expect, it } from "vitest";
import { generateKeypair, initCrypto } from "../crypto/keys";
import { deviceFingerprint, recipientKeysetHash, serverDirectoryHash } from "../crypto/deviceTrust";
import type { ConvMember, DirectoryCheckpoint, DirectoryProof } from "../net/api";
import { verifyConversationKeyDirectory } from "./useStore";

describe("conversation member key-directory enforcement", () => {
  beforeAll(async () => { await initCrypto(); });

  const makeMember = (userId: number, sid: string, kind: ConvMember["kind"] = "web"): ConvMember => {
    const keys = generateKeypair();
    return {
      user_id: userId, device_id: userId * 10, sid, name: sid,
      pub_key: keys.box.pk, sig_pub: keys.sign.pk, kind,
    };
  };

  const checkpoint = (userId: number, identity: string, epoch: number, members: ConvMember[]): DirectoryCheckpoint => ({
    user_id: userId,
    identity_sig_pub: identity,
    security_epoch: epoch,
    directory_hash: serverDirectoryHash(members.map((member) => ({
      sid: member.sid, pub_key: member.pub_key, sig_pub: member.sig_pub, kind: member.kind,
    }))),
  });

  const proof = (userId: number, identity: string, epoch: number, members: ConvMember[]): DirectoryProof => ({
    ...checkpoint(userId, identity, epoch, members),
    device_history: members.map((member, index) => ({
      sid: member.sid,
      kind: member.kind,
      pub_key: member.pub_key,
      sig_pub: member.sig_pub,
      fingerprint: deviceFingerprint(member.pub_key, member.sig_pub).hash,
      trust_state: "approved",
      challenge: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      approved_by_sid: index === 0 ? member.sid : "legacy_tofu",
      verification_state: "verified",
    })),
    approval_certificates: [],
    revocation_certificates: [],
    security_upgrade_certificates: [],
    security_mode: "verified_v2",
  });

  it("pins every participant directory and validates the aggregate keyset", async () => {
    const alice = makeMember(9101, "alice-web");
    const bob = makeMember(9102, "bob-phone", "android_gateway");
    await expect(verifyConversationKeyDirectory({
      ok: true,
      members: [bob, alice],
      recipient_keyset_hash: recipientKeysetHash([bob, alice]),
      directory_checkpoints: [
        checkpoint(9101, alice.sig_pub, 1, [alice]),
        checkpoint(9102, bob.sig_pub, 1, [bob]),
      ],
      directory_proofs: [
        proof(9101, alice.sig_pub, 1, [alice]),
        proof(9102, bob.sig_pub, 1, [bob]),
      ],
    })).resolves.toBeUndefined();
  });

  it("rejects member-list tampering against recipient_keyset_hash", async () => {
    const member = makeMember(9201, "original");
    const claimed = recipientKeysetHash([member]);
    await expect(verifyConversationKeyDirectory({
      ok: true,
      members: [{ ...member, sid: "substituted" }],
      recipient_keyset_hash: claimed,
      directory_checkpoints: [checkpoint(9201, member.sig_pub, 1, [member])],
      directory_proofs: [proof(9201, member.sig_pub, 1, [member])],
    })).rejects.toThrow(/keyset/);
  });

  it("rejects a directory checkpoint that does not cover its member keys", async () => {
    const member = makeMember(9301, "device");
    await expect(verifyConversationKeyDirectory({
      ok: true,
      members: [member],
      recipient_keyset_hash: recipientKeysetHash([member]),
      directory_checkpoints: [{
        ...checkpoint(9301, member.sig_pub, 1, [member]),
        directory_hash: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      }],
      directory_proofs: [proof(9301, member.sig_pub, 1, [member])],
    })).rejects.toThrow(/checkpoint|해시/);
  });

  it("rejects a changed key for an already-pinned peer SID", async () => {
    const first = makeMember(9401, "peer-stable");
    await verifyConversationKeyDirectory({
      ok: true,
      members: [first],
      recipient_keyset_hash: recipientKeysetHash([first]),
      directory_checkpoints: [checkpoint(9401, first.sig_pub, 1, [first])],
      directory_proofs: [proof(9401, first.sig_pub, 1, [first])],
    });
    const attacker = makeMember(9401, "peer-stable");
    await expect(verifyConversationKeyDirectory({
      ok: true,
      members: [attacker],
      recipient_keyset_hash: recipientKeysetHash([attacker]),
      directory_checkpoints: [checkpoint(9401, first.sig_pub, 2, [attacker])],
      directory_proofs: [proof(9401, first.sig_pub, 2, [attacker])],
    })).rejects.toThrow();
  });
});
