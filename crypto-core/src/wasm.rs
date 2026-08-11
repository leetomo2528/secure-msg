//! Browser-facing `wasm-bindgen` adapter for the transactional MLS core.
//!
//! OpenMLS types never cross the JavaScript boundary. Wire messages, device
//! certificates and device state are opaque bytes. `exportSecretState()` still
//! contains secrets: the host MUST encrypt it with a non-exportable WebCrypto
//! key and atomically persist it together with `checkpoint`.

use anyhow::{Result, anyhow, bail};
use wasm_bindgen::prelude::*;

use crate::{
    AccountDevice, AddDeviceOutput, DecryptedApplication, DeviceCertificate, Direction, MessageAad,
    PendingEnrollment, RemoveDeviceOutput, StatePersistence, StoredState,
};

fn js_error(error: anyhow::Error) -> JsValue {
    JsValue::from_str(&error.to_string())
}

fn aad(cid: String, mid: String, outgoing: bool) -> MessageAad {
    MessageAad {
        cid,
        mid,
        direction: if outgoing {
            Direction::Outgoing
        } else {
            Direction::Incoming
        },
    }
}

/// Single-device transactional storage used inside one WASM instance.
///
/// JavaScript owns durable encryption and rollback-resistant checkpoint
/// storage. This adapter enforces the same CAS contract within the live
/// instance and exposes the latest state only after a successful transition.
struct WasmStatePersistence {
    account_id: Vec<u8>,
    device_id: String,
    stored: Option<StoredState>,
    trusted_checkpoint: Option<u64>,
}

impl WasmStatePersistence {
    fn empty(account_id: Vec<u8>, device_id: String) -> Self {
        Self {
            account_id,
            device_id,
            stored: None,
            trusted_checkpoint: None,
        }
    }

    fn restored(
        account_id: Vec<u8>,
        device_id: String,
        checkpoint: u64,
        secret_state: Vec<u8>,
    ) -> Self {
        Self {
            account_id,
            device_id,
            stored: Some(StoredState {
                checkpoint,
                encrypted_state: secret_state,
            }),
            trusted_checkpoint: Some(checkpoint),
        }
    }

    fn require_identity(&self, account_id: &[u8], device_id: &str) -> Result<()> {
        if account_id != self.account_id || device_id != self.device_id {
            bail!("WASM persistence key does not match this account device");
        }
        Ok(())
    }

    fn latest(&self) -> Result<&StoredState> {
        self.stored
            .as_ref()
            .ok_or_else(|| anyhow!("device state has not been initialized"))
    }
}

impl StatePersistence for WasmStatePersistence {
    fn load(&self, account_id: &[u8], device_id: &str) -> Result<Option<StoredState>> {
        self.require_identity(account_id, device_id)?;
        Ok(self.stored.clone())
    }

    fn trusted_checkpoint(&self, account_id: &[u8], device_id: &str) -> Result<Option<u64>> {
        self.require_identity(account_id, device_id)?;
        Ok(self.trusted_checkpoint)
    }

    fn compare_and_swap(
        &mut self,
        account_id: &[u8],
        device_id: &str,
        expected: Option<u64>,
        replacement: StoredState,
    ) -> Result<()> {
        self.require_identity(account_id, device_id)?;
        let current = self.stored.as_ref().map(|state| state.checkpoint);
        if current != expected
            || expected.is_some_and(|checkpoint| replacement.checkpoint <= checkpoint)
        {
            bail!("WASM device-state compare-and-swap conflict");
        }
        self.trusted_checkpoint = Some(replacement.checkpoint);
        self.stored = Some(replacement);
        Ok(())
    }

    fn delete_compare_and_swap(
        &mut self,
        account_id: &[u8],
        device_id: &str,
        expected: u64,
        tombstone_checkpoint: u64,
    ) -> Result<()> {
        self.require_identity(account_id, device_id)?;
        if self.stored.as_ref().map(|state| state.checkpoint) != Some(expected)
            || tombstone_checkpoint <= expected
        {
            bail!("WASM device-state delete compare-and-swap conflict");
        }
        self.stored = None;
        self.trusted_checkpoint = Some(tombstone_checkpoint);
        Ok(())
    }
}

/// An enrollment whose MLS private signing key has not yet been certified.
#[wasm_bindgen]
pub struct WasmPendingEnrollment {
    inner: Option<PendingEnrollment>,
    account_id: Vec<u8>,
    device_id: String,
}

#[wasm_bindgen]
impl WasmPendingEnrollment {
    /// Generate a new device signing key and unsigned enrollment request.
    #[wasm_bindgen(constructor)]
    pub fn new(account_id: &[u8], device_id: String) -> Result<WasmPendingEnrollment, JsValue> {
        let inner =
            AccountDevice::begin_enrollment(account_id, device_id.clone()).map_err(js_error)?;
        Ok(Self {
            inner: Some(inner),
            account_id: account_id.to_vec(),
            device_id,
        })
    }

    /// Canonical JSON enrollment request to be approved by the account root.
    #[wasm_bindgen(js_name = requestBytes)]
    pub fn request_bytes(&self) -> Result<Vec<u8>, JsValue> {
        let enrollment = self
            .inner
            .as_ref()
            .ok_or_else(|| JsValue::from_str("enrollment was already finalized"))?;
        serde_json::to_vec(enrollment.request()).map_err(|error| js_error(error.into()))
    }

    /// Verify the root-signed certificate and create the transactional device.
    #[wasm_bindgen(js_name = finalizeCertificate)]
    pub fn finalize_certificate(
        &mut self,
        certificate_bytes: &[u8],
    ) -> Result<WasmAccountDevice, JsValue> {
        let certificate: DeviceCertificate =
            serde_json::from_slice(certificate_bytes).map_err(|error| js_error(error.into()))?;
        let enrollment = self
            .inner
            .take()
            .ok_or_else(|| JsValue::from_str("enrollment was already finalized"))?;
        let mut persistence =
            WasmStatePersistence::empty(self.account_id.clone(), self.device_id.clone());
        let inner = enrollment
            .finalize(certificate, &mut persistence)
            .map_err(js_error)?;
        Ok(WasmAccountDevice { inner, persistence })
    }
}

/// A browser-owned account device and its live MLS state.
#[wasm_bindgen]
pub struct WasmAccountDevice {
    inner: AccountDevice,
    persistence: WasmStatePersistence,
}

#[wasm_bindgen]
impl WasmAccountDevice {
    /// Restore opaque state only if its embedded checkpoint equals the trusted
    /// checkpoint supplied from rollback-resistant host storage.
    #[wasm_bindgen(js_name = importSecretState)]
    pub fn import_secret_state(
        secret_state: &[u8],
        trusted_checkpoint: u64,
    ) -> Result<WasmAccountDevice, JsValue> {
        let inspected = AccountDevice::import_secret_state(secret_state).map_err(js_error)?;
        if inspected.checkpoint() != trusted_checkpoint {
            return Err(JsValue::from_str(
                "secret-state checkpoint does not match trusted checkpoint",
            ));
        }
        let account_id = inspected.account_id().to_vec();
        let device_id = inspected.device_id().to_owned();
        drop(inspected);
        let persistence = WasmStatePersistence::restored(
            account_id.clone(),
            device_id.clone(),
            trusted_checkpoint,
            secret_state.to_vec(),
        );
        let inner = AccountDevice::restore(&account_id, &device_id, &persistence)
            .map_err(js_error)?
            .ok_or_else(|| JsValue::from_str("restored device state is missing"))?;
        Ok(Self { inner, persistence })
    }

    #[wasm_bindgen(getter, js_name = deviceId)]
    #[must_use]
    pub fn device_id(&self) -> String {
        self.inner.device_id().to_owned()
    }

    #[wasm_bindgen(getter)]
    #[must_use]
    pub fn checkpoint(&self) -> u64 {
        self.inner.checkpoint()
    }

    /// Latest opaque secret state. Persist atomically with `checkpoint`.
    #[wasm_bindgen(js_name = exportSecretState)]
    pub fn export_secret_state(&self) -> Result<Vec<u8>, JsValue> {
        self.persistence
            .latest()
            .map(|state| state.encrypted_state.clone())
            .map_err(js_error)
    }

    #[wasm_bindgen(js_name = createAccountGroup)]
    pub fn create_account_group(&mut self, group_id: &[u8]) -> Result<(), JsValue> {
        self.inner
            .create_account_group(group_id, &mut self.persistence)
            .map_err(js_error)
    }

    #[wasm_bindgen(js_name = generateKeyPackage)]
    pub fn generate_key_package(&mut self) -> Result<Vec<u8>, JsValue> {
        self.inner
            .generate_key_package(&mut self.persistence)
            .map_err(js_error)
    }

    #[wasm_bindgen(js_name = addDevice)]
    pub fn add_device(
        &mut self,
        group_id: &[u8],
        key_package: &[u8],
    ) -> Result<WasmAddDeviceOutput, JsValue> {
        self.inner
            .add_device(group_id, key_package, &mut self.persistence)
            .map(WasmAddDeviceOutput::from)
            .map_err(js_error)
    }

    #[wasm_bindgen(js_name = joinFromWelcome)]
    pub fn join_from_welcome(&mut self, welcome: &[u8]) -> Result<Vec<u8>, JsValue> {
        self.inner
            .join_from_welcome(welcome, &mut self.persistence)
            .map_err(js_error)
    }

    #[wasm_bindgen(js_name = applyCommit)]
    pub fn apply_commit(&mut self, group_id: &[u8], commit: &[u8]) -> Result<(), JsValue> {
        self.inner
            .apply_commit(group_id, commit, &mut self.persistence)
            .map_err(js_error)
    }

    #[wasm_bindgen(js_name = encryptApplication)]
    pub fn encrypt_application(
        &mut self,
        group_id: &[u8],
        cid: String,
        mid: String,
        outgoing: bool,
        plaintext: &[u8],
    ) -> Result<Vec<u8>, JsValue> {
        self.inner
            .encrypt_application(
                group_id,
                &aad(cid, mid, outgoing),
                plaintext,
                &mut self.persistence,
            )
            .map_err(js_error)
    }

    /// Decrypt only when host route metadata exactly matches authenticated AAD.
    #[wasm_bindgen(js_name = decryptApplicationExpected)]
    pub fn decrypt_application_expected(
        &mut self,
        group_id: &[u8],
        ciphertext: &[u8],
        cid: String,
        mid: String,
        outgoing: bool,
    ) -> Result<WasmDecryptedApplication, JsValue> {
        self.inner
            .decrypt_application_expected(
                group_id,
                ciphertext,
                &aad(cid, mid, outgoing),
                &mut self.persistence,
            )
            .map(WasmDecryptedApplication::from)
            .map_err(js_error)
    }

    #[wasm_bindgen(js_name = removeDevice)]
    pub fn remove_device(
        &mut self,
        group_id: &[u8],
        device_id: &str,
    ) -> Result<WasmRemoveDeviceOutput, JsValue> {
        self.inner
            .remove_device(group_id, device_id, &mut self.persistence)
            .map(WasmRemoveDeviceOutput::from)
            .map_err(js_error)
    }

    #[wasm_bindgen(js_name = finalizePendingCommit)]
    pub fn finalize_pending_commit(&mut self, group_id: &[u8]) -> Result<(), JsValue> {
        self.inner
            .finalize_pending_commit(group_id, &mut self.persistence)
            .map_err(js_error)
    }

    #[wasm_bindgen(js_name = abortPendingCommit)]
    pub fn abort_pending_commit(&mut self, group_id: &[u8]) -> Result<(), JsValue> {
        self.inner
            .abort_pending_commit(group_id, &mut self.persistence)
            .map_err(js_error)
    }

    #[wasm_bindgen(js_name = destroyGroup)]
    pub fn destroy_group(&mut self, group_id: &[u8]) -> Result<(), JsValue> {
        self.inner
            .destroy_group(group_id, &mut self.persistence)
            .map_err(js_error)
    }
}

#[wasm_bindgen]
pub struct WasmAddDeviceOutput {
    commit: Vec<u8>,
    welcome: Vec<u8>,
}

impl From<AddDeviceOutput> for WasmAddDeviceOutput {
    fn from(output: AddDeviceOutput) -> Self {
        Self {
            commit: output.commit,
            welcome: output.welcome,
        }
    }
}

#[wasm_bindgen]
impl WasmAddDeviceOutput {
    #[wasm_bindgen(getter)]
    #[must_use]
    pub fn commit(&self) -> Vec<u8> {
        self.commit.clone()
    }

    #[wasm_bindgen(getter)]
    #[must_use]
    pub fn welcome(&self) -> Vec<u8> {
        self.welcome.clone()
    }
}

#[wasm_bindgen]
pub struct WasmRemoveDeviceOutput {
    commit: Vec<u8>,
}

impl From<RemoveDeviceOutput> for WasmRemoveDeviceOutput {
    fn from(output: RemoveDeviceOutput) -> Self {
        Self {
            commit: output.commit,
        }
    }
}

#[wasm_bindgen]
impl WasmRemoveDeviceOutput {
    #[wasm_bindgen(getter)]
    #[must_use]
    pub fn commit(&self) -> Vec<u8> {
        self.commit.clone()
    }
}

#[wasm_bindgen]
pub struct WasmDecryptedApplication {
    sender_device_id: String,
    sender_leaf_index: u32,
    cid: String,
    mid: String,
    outgoing: bool,
    plaintext: Vec<u8>,
}

impl From<DecryptedApplication> for WasmDecryptedApplication {
    fn from(message: DecryptedApplication) -> Self {
        Self {
            sender_device_id: message.sender_device_id.clone(),
            sender_leaf_index: message.sender_leaf_index,
            cid: message.aad.cid.clone(),
            mid: message.aad.mid.clone(),
            outgoing: message.aad.direction == Direction::Outgoing,
            plaintext: message.plaintext.clone(),
        }
    }
}

#[wasm_bindgen]
impl WasmDecryptedApplication {
    #[wasm_bindgen(getter, js_name = senderDeviceId)]
    #[must_use]
    pub fn sender_device_id(&self) -> String {
        self.sender_device_id.clone()
    }

    #[wasm_bindgen(getter, js_name = senderLeafIndex)]
    #[must_use]
    pub fn sender_leaf_index(&self) -> u32 {
        self.sender_leaf_index
    }

    #[wasm_bindgen(getter)]
    #[must_use]
    pub fn cid(&self) -> String {
        self.cid.clone()
    }

    #[wasm_bindgen(getter)]
    #[must_use]
    pub fn mid(&self) -> String {
        self.mid.clone()
    }

    #[wasm_bindgen(getter)]
    #[must_use]
    pub fn outgoing(&self) -> bool {
        self.outgoing
    }

    #[wasm_bindgen(getter)]
    #[must_use]
    pub fn plaintext(&self) -> Vec<u8> {
        self.plaintext.clone()
    }
}
