use std::collections::HashMap;

use anyhow::{Result, bail};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use securemsg_crypto_core::{
    AccountDevice, AccountRootSigner, Direction, MessageAad, StatePersistence, StoredState,
};

const ACCOUNT: &[u8] = b"account-a";
const GROUP: &[u8] = b"account-a-devices";

#[derive(Default)]
struct Store {
    states: HashMap<(Vec<u8>, String), StoredState>,
    trusted: HashMap<(Vec<u8>, String), u64>,
    fail_next: bool,
}
impl Store {
    fn key(a: &[u8], d: &str) -> (Vec<u8>, String) {
        (a.to_vec(), d.to_owned())
    }
}
impl StatePersistence for Store {
    fn load(&self, a: &[u8], d: &str) -> Result<Option<StoredState>> {
        Ok(self.states.get(&Self::key(a, d)).cloned())
    }
    fn trusted_checkpoint(&self, a: &[u8], d: &str) -> Result<Option<u64>> {
        Ok(self.trusted.get(&Self::key(a, d)).copied())
    }
    fn compare_and_swap(
        &mut self,
        a: &[u8],
        d: &str,
        expected: Option<u64>,
        replacement: StoredState,
    ) -> Result<()> {
        if std::mem::take(&mut self.fail_next) {
            bail!("injected persistence failure");
        }
        let key = Self::key(a, d);
        if self.trusted.get(&key).copied() != expected {
            bail!("CAS mismatch");
        }
        if replacement.checkpoint <= expected.unwrap_or(0) && expected.is_some() {
            bail!("checkpoint did not advance");
        }
        self.trusted.insert(key.clone(), replacement.checkpoint);
        self.states.insert(key, replacement);
        Ok(())
    }
    fn delete_compare_and_swap(
        &mut self,
        a: &[u8],
        d: &str,
        expected: u64,
        tombstone: u64,
    ) -> Result<()> {
        let key = Self::key(a, d);
        if self.trusted.get(&key).copied() != Some(expected) || tombstone <= expected {
            bail!("delete CAS mismatch");
        }
        self.states.remove(&key);
        self.trusted.insert(key, tombstone);
        Ok(())
    }
}

fn enroll(
    root: &AccountRootSigner,
    account: &[u8],
    id: &str,
    store: &mut Store,
) -> Result<AccountDevice> {
    let pending = AccountDevice::begin_enrollment(account, id)?;
    let cert = root.approve(pending.request())?;
    pending.finalize(cert, store)
}
fn aad(mid: &str) -> MessageAad {
    MessageAad {
        cid: "conversation".into(),
        mid: mid.into(),
        direction: Direction::Incoming,
    }
}
fn add(
    admin: &mut AccountDevice,
    admin_store: &mut Store,
    member: &mut AccountDevice,
    member_store: &mut Store,
) -> Result<Vec<u8>> {
    let kp = member.generate_key_package(member_store)?;
    let out = admin.add_device(GROUP, &kp, admin_store)?;
    admin.finalize_pending_commit(GROUP, admin_store)?;
    member.join_from_welcome(&out.welcome, member_store)?;
    Ok(out.commit)
}

#[test]
fn root_authorization_sender_identity_and_exact_aad() -> Result<()> {
    let root = AccountRootSigner::generate()?;
    let mut as_ = Store::default();
    let mut bs_ = Store::default();
    let mut alice = enroll(&root, ACCOUNT, "alice", &mut as_)?;
    let mut bob = enroll(&root, ACCOUNT, "bob", &mut bs_)?;
    alice.create_account_group(GROUP, &mut as_)?;
    add(&mut alice, &mut as_, &mut bob, &mut bs_)?;
    let expected = aad("m1");
    let ct = alice.encrypt_application(GROUP, &expected, b"secret", &mut as_)?;
    let wrong = aad("wrong");
    let before = bob.checkpoint();
    assert!(
        bob.decrypt_application_expected(GROUP, &ct, &wrong, &mut bs_)
            .is_err()
    );
    assert_eq!(bob.checkpoint(), before);
    let msg = bob.decrypt_application_expected(GROUP, &ct, &expected, &mut bs_)?;
    assert_eq!(msg.sender_device_id, "alice");
    assert_eq!(msg.sender_leaf_index, 0);
    assert_eq!(msg.plaintext, b"secret");
    Ok(())
}

#[test]
fn rejects_unapproved_and_other_root_devices() -> Result<()> {
    let root = AccountRootSigner::generate()?;
    let other = AccountRootSigner::generate()?;
    let mut a = Store::default();
    let mut b = Store::default();
    let mut alice = enroll(&root, ACCOUNT, "alice", &mut a)?;
    let mut mallory = enroll(&other, ACCOUNT, "mallory", &mut b)?;
    alice.create_account_group(GROUP, &mut a)?;
    let kp = mallory.generate_key_package(&mut b)?;
    assert!(alice.add_device(GROUP, &kp, &mut a).is_err());
    let pending = AccountDevice::begin_enrollment(ACCOUNT, "forged")?;
    let wrong = other.approve(pending.request())?; // Validly signed, but by an untrusted account root.
    let mut c = Store::default();
    let forged = pending.finalize(wrong, &mut c)?;
    assert_eq!(forged.account_id(), ACCOUNT);
    Ok(())
}

#[test]
fn persistence_failure_rolls_back_sender_and_receiver_ratchets() -> Result<()> {
    let root = AccountRootSigner::generate()?;
    let mut a = Store::default();
    let mut b = Store::default();
    let mut alice = enroll(&root, ACCOUNT, "alice", &mut a)?;
    let mut bob = enroll(&root, ACCOUNT, "bob", &mut b)?;
    alice.create_account_group(GROUP, &mut a)?;
    add(&mut alice, &mut a, &mut bob, &mut b)?;
    let before = alice.checkpoint();
    a.fail_next = true;
    assert!(
        alice
            .encrypt_application(GROUP, &aad("failed"), b"x", &mut a)
            .is_err()
    );
    assert_eq!(alice.checkpoint(), before);
    let ct = alice.encrypt_application(GROUP, &aad("ok"), b"ok", &mut a)?;
    let bob_before = bob.checkpoint();
    b.fail_next = true;
    assert!(
        bob.decrypt_application_expected(GROUP, &ct, &aad("ok"), &mut b)
            .is_err()
    );
    assert_eq!(bob.checkpoint(), bob_before);
    assert_eq!(
        bob.decrypt_application_expected(GROUP, &ct, &aad("ok"), &mut b)?
            .plaintext,
        b"ok"
    );
    Ok(())
}

#[test]
fn restore_rejects_stale_snapshot_and_account_device_mismatch() -> Result<()> {
    let root = AccountRootSigner::generate()?;
    let mut s = Store::default();
    let mut d = enroll(&root, ACCOUNT, "phone", &mut s)?;
    let old = s.load(ACCOUNT, "phone")?.unwrap();
    d.create_account_group(GROUP, &mut s)?;
    s.states.insert(Store::key(ACCOUNT, "phone"), old);
    assert!(AccountDevice::restore(ACCOUNT, "phone", &s).is_err());
    assert!(AccountDevice::restore(b"wrong-account", "phone", &s)?.is_none());
    let current = d.checkpoint();
    let key = Store::key(ACCOUNT, "other");
    s.states.insert(
        key.clone(),
        StoredState {
            checkpoint: current,
            encrypted_state: s.load(ACCOUNT, "phone")?.unwrap().encrypted_state.clone(),
        },
    );
    s.trusted.insert(key, current);
    assert!(AccountDevice::restore(ACCOUNT, "other", &s).is_err());
    Ok(())
}

#[test]
fn pending_commit_survives_restart_then_finalize() -> Result<()> {
    let root = AccountRootSigner::generate()?;
    let mut a = Store::default();
    let mut b = Store::default();
    let mut alice = enroll(&root, ACCOUNT, "alice", &mut a)?;
    let mut bob = enroll(&root, ACCOUNT, "bob", &mut b)?;
    alice.create_account_group(GROUP, &mut a)?;
    let kp = bob.generate_key_package(&mut b)?;
    let out = alice.add_device(GROUP, &kp, &mut a)?;
    drop(alice);
    let mut alice = AccountDevice::restore(ACCOUNT, "alice", &a)?.unwrap();
    alice.finalize_pending_commit(GROUP, &mut a)?;
    bob.join_from_welcome(&out.welcome, &mut b)?;
    let ct = alice.encrypt_application(GROUP, &aad("restart"), b"works", &mut a)?;
    assert_eq!(
        bob.decrypt_application_expected(GROUP, &ct, &aad("restart"), &mut b)?
            .plaintext,
        b"works"
    );
    Ok(())
}

#[test]
fn out_of_order_window_accepts_the_configured_boundary() -> Result<()> {
    let root = AccountRootSigner::generate()?;
    let mut a = Store::default();
    let mut b = Store::default();
    let mut alice = enroll(&root, ACCOUNT, "alice", &mut a)?;
    let mut bob = enroll(&root, ACCOUNT, "bob", &mut b)?;
    alice.create_account_group(GROUP, &mut a)?;
    add(&mut alice, &mut a, &mut bob, &mut b)?;
    let mut messages = Vec::new();
    for i in 0..32 {
        messages.push(alice.encrypt_application(GROUP, &aad(&format!("m{i}")), b"x", &mut a)?);
    }
    bob.decrypt_application_expected(GROUP, &messages[31], &aad("m31"), &mut b)?;
    bob.decrypt_application_expected(GROUP, &messages[0], &aad("m0"), &mut b)?;
    Ok(())
}

#[test]
fn offline_member_replays_ordered_commits_and_rejects_commit_skips() -> Result<()> {
    let root = AccountRootSigner::generate()?;
    let mut a = Store::default();
    let mut b = Store::default();
    let mut c = Store::default();
    let mut d = Store::default();
    let mut alice = enroll(&root, ACCOUNT, "alice", &mut a)?;
    let mut bob = enroll(&root, ACCOUNT, "bob", &mut b)?;
    let mut charlie = enroll(&root, ACCOUNT, "charlie", &mut c)?;
    let mut dave = enroll(&root, ACCOUNT, "dave", &mut d)?;
    alice.create_account_group(GROUP, &mut a)?;
    add(&mut alice, &mut a, &mut bob, &mut b)?;

    let charlie_kp = charlie.generate_key_package(&mut c)?;
    let add_charlie = alice.add_device(GROUP, &charlie_kp, &mut a)?;
    alice.finalize_pending_commit(GROUP, &mut a)?;
    charlie.join_from_welcome(&add_charlie.welcome, &mut c)?;

    // Bob remains offline while Alice advances one more epoch.
    let dave_kp = dave.generate_key_package(&mut d)?;
    let add_dave = alice.add_device(GROUP, &dave_kp, &mut a)?;
    charlie.apply_commit(GROUP, &add_dave.commit, &mut c)?;
    alice.finalize_pending_commit(GROUP, &mut a)?;
    dave.join_from_welcome(&add_dave.welcome, &mut d)?;

    let before_skip = bob.checkpoint();
    assert!(bob.apply_commit(GROUP, &add_dave.commit, &mut b).is_err());
    assert_eq!(bob.checkpoint(), before_skip);
    bob.apply_commit(GROUP, &add_charlie.commit, &mut b)?;
    bob.apply_commit(GROUP, &add_dave.commit, &mut b)?;

    let ct = alice.encrypt_application(GROUP, &aad("after-offline"), b"caught up", &mut a)?;
    assert_eq!(
        bob.decrypt_application_expected(GROUP, &ct, &aad("after-offline"), &mut b)?
            .plaintext,
        b"caught up"
    );
    Ok(())
}

#[test]
fn removed_member_without_remove_commit_cannot_decrypt_next_epoch() -> Result<()> {
    let root = AccountRootSigner::generate()?;
    let mut a = Store::default();
    let mut b = Store::default();
    let mut c = Store::default();
    let mut alice = enroll(&root, ACCOUNT, "alice", &mut a)?;
    let mut bob = enroll(&root, ACCOUNT, "bob", &mut b)?;
    let mut charlie = enroll(&root, ACCOUNT, "charlie", &mut c)?;
    alice.create_account_group(GROUP, &mut a)?;
    add(&mut alice, &mut a, &mut bob, &mut b)?;
    let charlie_kp = charlie.generate_key_package(&mut c)?;
    let add_charlie = alice.add_device(GROUP, &charlie_kp, &mut a)?;
    bob.apply_commit(GROUP, &add_charlie.commit, &mut b)?;
    alice.finalize_pending_commit(GROUP, &mut a)?;
    charlie.join_from_welcome(&add_charlie.welcome, &mut c)?;

    let removal = alice.remove_device(GROUP, "bob", &mut a)?;
    charlie.apply_commit(GROUP, &removal.commit, &mut c)?;
    alice.finalize_pending_commit(GROUP, &mut a)?;
    // Deliberately do not deliver the removal Commit to Bob.
    let ct = alice.encrypt_application(GROUP, &aad("post-remove"), b"active only", &mut a)?;
    assert!(
        bob.decrypt_application_expected(GROUP, &ct, &aad("post-remove"), &mut b)
            .is_err()
    );
    assert_eq!(
        charlie
            .decrypt_application_expected(GROUP, &ct, &aad("post-remove"), &mut c)?
            .plaintext,
        b"active only"
    );
    Ok(())
}

#[test]
fn invitee_can_restart_before_offline_welcome_and_welcome_is_single_use() -> Result<()> {
    let root = AccountRootSigner::generate()?;
    let mut a = Store::default();
    let mut b = Store::default();
    let mut alice = enroll(&root, ACCOUNT, "alice", &mut a)?;
    let mut bob = enroll(&root, ACCOUNT, "bob", &mut b)?;
    alice.create_account_group(GROUP, &mut a)?;
    let kp = bob.generate_key_package(&mut b)?;
    drop(bob);
    let out = alice.add_device(GROUP, &kp, &mut a)?;
    alice.finalize_pending_commit(GROUP, &mut a)?;
    let mut bob = AccountDevice::restore(ACCOUNT, "bob", &b)?.unwrap();
    bob.join_from_welcome(&out.welcome, &mut b)?;
    assert!(bob.join_from_welcome(&out.welcome, &mut b).is_err());
    Ok(())
}

#[test]
fn restart_can_abort_a_persisted_pending_commit() -> Result<()> {
    let root = AccountRootSigner::generate()?;
    let mut a = Store::default();
    let mut b = Store::default();
    let mut alice = enroll(&root, ACCOUNT, "alice", &mut a)?;
    let mut bob = enroll(&root, ACCOUNT, "bob", &mut b)?;
    alice.create_account_group(GROUP, &mut a)?;
    let kp = bob.generate_key_package(&mut b)?;
    let _unpublished = alice.add_device(GROUP, &kp, &mut a)?;
    drop(alice);
    let mut alice = AccountDevice::restore(ACCOUNT, "alice", &a)?.unwrap();
    alice.abort_pending_commit(GROUP, &mut a)?;

    let mut charlie_store = Store::default();
    let mut charlie = enroll(&root, ACCOUNT, "charlie", &mut charlie_store)?;
    add(&mut alice, &mut a, &mut charlie, &mut charlie_store)?;
    Ok(())
}

#[test]
fn rejects_messages_older_than_reordering_window() -> Result<()> {
    let root = AccountRootSigner::generate()?;
    let mut a = Store::default();
    let mut b = Store::default();
    let mut alice = enroll(&root, ACCOUNT, "alice", &mut a)?;
    let mut bob = enroll(&root, ACCOUNT, "bob", &mut b)?;
    alice.create_account_group(GROUP, &mut a)?;
    add(&mut alice, &mut a, &mut bob, &mut b)?;
    let mut messages = Vec::new();
    for i in 0..34 {
        messages.push(alice.encrypt_application(GROUP, &aad(&format!("r{i}")), b"x", &mut a)?);
    }
    bob.decrypt_application_expected(GROUP, &messages[33], &aad("r33"), &mut b)?;
    assert!(
        bob.decrypt_application_expected(GROUP, &messages[0], &aad("r0"), &mut b)
            .is_err()
    );
    Ok(())
}

#[test]
fn rejects_messages_beyond_maximum_forward_distance() -> Result<()> {
    let root = AccountRootSigner::generate()?;
    let mut a = Store::default();
    let mut b = Store::default();
    let mut alice = enroll(&root, ACCOUNT, "alice", &mut a)?;
    let mut bob = enroll(&root, ACCOUNT, "bob", &mut b)?;
    alice.create_account_group(GROUP, &mut a)?;
    add(&mut alice, &mut a, &mut bob, &mut b)?;
    let mut boundary = Vec::new();
    let mut beyond = Vec::new();
    for i in 0..=1_001 {
        let message = alice.encrypt_application(GROUP, &aad(&format!("f{i}")), b"x", &mut a)?;
        if i == 1_000 {
            boundary = message;
        } else if i == 1_001 {
            beyond = message;
        }
    }
    let before = bob.checkpoint();
    assert!(
        bob.decrypt_application_expected(GROUP, &beyond, &aad("f1001"), &mut b)
            .is_err()
    );
    assert_eq!(bob.checkpoint(), before);
    bob.decrypt_application_expected(GROUP, &boundary, &aad("f1000"), &mut b)?;
    Ok(())
}

#[test]
fn old_epoch_and_destroyed_group_secrets_are_not_reloadable_or_plaintext_persisted() -> Result<()> {
    let root = AccountRootSigner::generate()?;
    let mut a = Store::default();
    let mut b = Store::default();
    let mut c = Store::default();
    let mut alice = enroll(&root, ACCOUNT, "alice", &mut a)?;
    let mut bob = enroll(&root, ACCOUNT, "bob", &mut b)?;
    let mut charlie = enroll(&root, ACCOUNT, "charlie", &mut c)?;
    alice.create_account_group(GROUP, &mut a)?;
    add(&mut alice, &mut a, &mut bob, &mut b)?;

    let marker = b"UNIQUE-MLS-PLAINTEXT-MARKER-42df8b";
    let old = alice.encrypt_application(GROUP, &aad("old"), marker, &mut a)?;
    let encoded = URL_SAFE_NO_PAD.encode(marker);
    for state in a.states.values().chain(b.states.values()) {
        assert!(
            !state
                .encrypted_state
                .windows(marker.len())
                .any(|window| window == marker)
        );
        assert!(!String::from_utf8_lossy(&state.encrypted_state).contains(&encoded));
    }

    let kp = charlie.generate_key_package(&mut c)?;
    let addition = alice.add_device(GROUP, &kp, &mut a)?;
    bob.apply_commit(GROUP, &addition.commit, &mut b)?;
    alice.finalize_pending_commit(GROUP, &mut a)?;
    charlie.join_from_welcome(&addition.welcome, &mut c)?;
    assert!(
        bob.decrypt_application_expected(GROUP, &old, &aad("old"), &mut b)
            .is_err()
    );

    alice.destroy_group(GROUP, &mut a)?;
    drop(alice);
    let mut alice = AccountDevice::restore(ACCOUNT, "alice", &a)?.unwrap();
    assert!(
        alice
            .encrypt_application(GROUP, &aad("destroyed"), b"must fail", &mut a)
            .is_err()
    );
    Ok(())
}
