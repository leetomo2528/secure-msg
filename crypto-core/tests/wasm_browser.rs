#![cfg(target_arch = "wasm32")]

use securemsg_crypto_core::{
    AccountRootSigner, EnrollmentRequest,
    wasm::{WasmAccountDevice, WasmPendingEnrollment},
};
use wasm_bindgen_test::*;

wasm_bindgen_test_configure!(run_in_browser);

const ACCOUNT: &[u8] = b"wasm-browser-account";
const GROUP: &[u8] = b"wasm-browser-group";

fn enroll(root: &AccountRootSigner, device_id: &str) -> WasmAccountDevice {
    let mut pending = WasmPendingEnrollment::new(ACCOUNT, device_id.to_owned()).unwrap();
    let request: EnrollmentRequest =
        serde_json::from_slice(&pending.request_bytes().unwrap()).unwrap();
    let certificate = root.approve(&request).unwrap();
    pending
        .finalize_certificate(&serde_json::to_vec(&certificate).unwrap())
        .unwrap()
}

#[wasm_bindgen_test]
fn browser_enrollment_state_keypackage_and_group_round_trip() {
    let root = AccountRootSigner::generate().unwrap();
    let mut alice = enroll(&root, "alice-browser");
    alice.create_account_group(GROUP).unwrap();

    let mut bob = enroll(&root, "bob-browser");
    let key_package = bob.generate_key_package().unwrap();
    assert!(!key_package.is_empty());

    let bob_checkpoint = bob.checkpoint();
    let bob_state = bob.export_secret_state().unwrap();
    let mut bob = WasmAccountDevice::import_secret_state(&bob_state, bob_checkpoint).unwrap();
    assert_eq!(bob.device_id(), "bob-browser");
    assert!(WasmAccountDevice::import_secret_state(&bob_state, bob_checkpoint + 1).is_err());

    let addition = alice.add_device(GROUP, &key_package).unwrap();
    alice.finalize_pending_commit(GROUP).unwrap();
    assert_eq!(bob.join_from_welcome(&addition.welcome()).unwrap(), GROUP);

    let ciphertext = alice
        .encrypt_application(
            GROUP,
            "conversation-1".to_owned(),
            "message-1".to_owned(),
            true,
            b"hello from browser wasm",
        )
        .unwrap();
    let decrypted = bob
        .decrypt_application_expected(
            GROUP,
            &ciphertext,
            "conversation-1".to_owned(),
            "message-1".to_owned(),
            true,
        )
        .unwrap();
    assert_eq!(decrypted.sender_device_id(), "alice-browser");
    assert_eq!(decrypted.cid(), "conversation-1");
    assert_eq!(decrypted.mid(), "message-1");
    assert!(decrypted.outgoing());
    assert_eq!(decrypted.plaintext(), b"hello from browser wasm");

    let route_bound = alice
        .encrypt_application(
            GROUP,
            "conversation-1".to_owned(),
            "message-2".to_owned(),
            true,
            b"must stay route-bound",
        )
        .unwrap();
    assert!(
        bob.decrypt_application_expected(
            GROUP,
            &route_bound,
            "conversation-1".to_owned(),
            "wrong-message".to_owned(),
            true,
        )
        .is_err()
    );
}
