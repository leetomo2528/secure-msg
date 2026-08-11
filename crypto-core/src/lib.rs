//! Transactional, account-authorized RFC 9420 MLS state for `SecureMsg`.

use std::{
    collections::{HashMap, HashSet},
    sync::RwLock,
};

use anyhow::{Context, Result, anyhow, bail};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use ed25519_dalek::{Signature, Signer as _, SigningKey, Verifier as _, VerifyingKey};
use openmls::prelude::*;
use openmls_basic_credential::SignatureKeyPair;
use openmls_memory_storage::MemoryStorage;
use openmls_rust_crypto::RustCrypto;
use openmls_traits::{OpenMlsProvider, types::SignatureScheme};
use serde::{Deserialize, Serialize};
use tls_codec::{Deserialize as TlsDeserialize, Serialize as TlsSerialize};
use zeroize::Zeroize;

pub mod wasm;

pub const CIPHERSUITE: Ciphersuite = Ciphersuite::MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519;
pub const OUT_OF_ORDER_TOLERANCE: u32 = 32;
pub const MAX_FORWARD_DISTANCE: u32 = 1_000;
const CERT_DOMAIN: &str = "securemsg.mls.device_certificate";
const AAD_DOMAIN: &str = "securemsg.mls.application_aad";
const STATE_DOMAIN: &str = "securemsg.mls.secret_state";
const VERSION: u8 = 1;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct MessageAad {
    pub cid: String,
    pub mid: String,
    pub direction: Direction,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Direction {
    Incoming,
    Outgoing,
}

impl MessageAad {
    fn canonical_bytes(&self) -> Result<Vec<u8>> {
        if self.cid.is_empty() || self.mid.is_empty() {
            bail!("cid and mid must be non-empty");
        }
        serde_json::to_vec(&CanonicalAad {
            domain: AAD_DOMAIN,
            version: VERSION,
            cid: &self.cid,
            mid: &self.mid,
            direction: self.direction,
        })
        .context("serialize canonical AAD")
    }
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct CanonicalAad<'a> {
    domain: &'a str,
    version: u8,
    cid: &'a str,
    mid: &'a str,
    direction: Direction,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct EnrollmentRequest {
    pub domain: String,
    pub version: u8,
    pub account_id: Vec<u8>,
    pub device_id: String,
    pub mls_signature_public_key: Vec<u8>,
}

impl EnrollmentRequest {
    fn validate(&self) -> Result<()> {
        if self.domain != CERT_DOMAIN
            || self.version != VERSION
            || self.account_id.is_empty()
            || self.device_id.is_empty()
            || self.mls_signature_public_key.len() != 32
        {
            bail!("invalid device enrollment request");
        }
        Ok(())
    }
    fn canonical_bytes(&self) -> Result<Vec<u8>> {
        self.validate()?;
        serde_json::to_vec(self).context("serialize enrollment request")
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DeviceCertificate {
    pub request: EnrollmentRequest,
    pub account_root_public_key: Vec<u8>,
    pub root_signature: Vec<u8>,
}

impl DeviceCertificate {
    pub fn verify(&self) -> Result<()> {
        self.request.validate()?;
        let root: [u8; 32] = self
            .account_root_public_key
            .as_slice()
            .try_into()
            .map_err(|_| anyhow!("invalid account root public key"))?;
        let signature = Signature::from_slice(&self.root_signature)
            .context("invalid device certificate signature")?;
        VerifyingKey::from_bytes(&root)
            .context("invalid account root public key")?
            .verify(&self.request.canonical_bytes()?, &signature)
            .context("device certificate is not signed by the account identity root")
    }
    fn credential_bytes(&self) -> Result<Vec<u8>> {
        self.verify()?;
        serde_json::to_vec(self).context("serialize device certificate")
    }
}

/// Test/integration root signer. Production should keep this key outside this crate
/// and supply only a verified `DeviceCertificate` to enrollment finalization.
pub struct AccountRootSigner(SigningKey);
impl AccountRootSigner {
    pub fn generate() -> Result<Self> {
        let mut seed = [0u8; 32];
        getrandom_old::getrandom(&mut seed).context("generate account root")?;
        Ok(Self(SigningKey::from_bytes(&seed)))
    }
    #[must_use]
    pub fn public_key(&self) -> Vec<u8> {
        self.0.verifying_key().to_bytes().to_vec()
    }
    pub fn approve(&self, request: &EnrollmentRequest) -> Result<DeviceCertificate> {
        let bytes = request.canonical_bytes()?;
        Ok(DeviceCertificate {
            request: request.clone(),
            account_root_public_key: self.public_key(),
            root_signature: self.0.sign(&bytes).to_bytes().to_vec(),
        })
    }
}

#[derive(Default, Debug)]
struct Provider {
    crypto: RustCrypto,
    storage: MemoryStorage,
}
impl OpenMlsProvider for Provider {
    type CryptoProvider = RustCrypto;
    type RandProvider = RustCrypto;
    type StorageProvider = MemoryStorage;
    fn storage(&self) -> &Self::StorageProvider {
        &self.storage
    }
    fn crypto(&self) -> &Self::CryptoProvider {
        &self.crypto
    }
    fn rand(&self) -> &Self::RandProvider {
        &self.crypto
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoredState {
    pub checkpoint: u64,
    pub encrypted_state: Vec<u8>,
}

impl Drop for StoredState {
    fn drop(&mut self) {
        self.encrypted_state.zeroize();
    }
}

/// Host encrypted storage. CAS and trusted-checkpoint updates must be one atomic
/// transaction; on success no older snapshot may subsequently be returned.
pub trait StatePersistence {
    fn load(&self, account_id: &[u8], device_id: &str) -> Result<Option<StoredState>>;
    fn trusted_checkpoint(&self, account_id: &[u8], device_id: &str) -> Result<Option<u64>>;
    fn compare_and_swap(
        &mut self,
        account_id: &[u8],
        device_id: &str,
        expected: Option<u64>,
        replacement: StoredState,
    ) -> Result<()>;
    fn delete_compare_and_swap(
        &mut self,
        account_id: &[u8],
        device_id: &str,
        expected: u64,
        tombstone_checkpoint: u64,
    ) -> Result<()>;
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct PersistedDevice {
    domain: String,
    version: u8,
    checkpoint: u64,
    account_id: Vec<u8>,
    device_id: String,
    certificate: DeviceCertificate,
    signature_public_key: Vec<u8>,
    storage: Vec<(String, String)>,
}

pub struct PendingEnrollment {
    account_id: Vec<u8>,
    device_id: String,
    signer: SignatureKeyPair,
    provider: Provider,
    request: EnrollmentRequest,
}
impl PendingEnrollment {
    pub const fn request(&self) -> &EnrollmentRequest {
        &self.request
    }
    pub fn finalize(
        self,
        certificate: DeviceCertificate,
        backend: &mut impl StatePersistence,
    ) -> Result<AccountDevice> {
        certificate.verify()?;
        if certificate.request != self.request {
            bail!("certificate does not match this enrollment request");
        }
        let device = AccountDevice {
            account_id: self.account_id,
            device_id: self.device_id,
            certificate,
            signature_public_key: self.request.mls_signature_public_key,
            signer: self.signer,
            provider: self.provider,
            checkpoint: 0,
        };
        let state = device.export_secret_state_at(0)?;
        backend.compare_and_swap(
            &device.account_id,
            &device.device_id,
            None,
            StoredState {
                checkpoint: 0,
                encrypted_state: state,
            },
        )?;
        Ok(device)
    }
}

pub struct AccountDevice {
    account_id: Vec<u8>,
    device_id: String,
    certificate: DeviceCertificate,
    signature_public_key: Vec<u8>,
    signer: SignatureKeyPair,
    provider: Provider,
    checkpoint: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AddDeviceOutput {
    pub commit: Vec<u8>,
    pub welcome: Vec<u8>,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RemoveDeviceOutput {
    pub commit: Vec<u8>,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecryptedApplication {
    pub sender_device_id: String,
    pub sender_leaf_index: u32,
    pub aad: MessageAad,
    pub plaintext: Vec<u8>,
}
impl Drop for DecryptedApplication {
    fn drop(&mut self) {
        self.plaintext.zeroize();
    }
}

impl AccountDevice {
    pub fn begin_enrollment(
        account_id: impl AsRef<[u8]>,
        device_id: impl Into<String>,
    ) -> Result<PendingEnrollment> {
        let account_id = account_id.as_ref().to_vec();
        let device_id = device_id.into();
        if account_id.is_empty() || device_id.is_empty() {
            bail!("account_id and device_id must be non-empty");
        }
        let provider = Provider::default();
        let signer = SignatureKeyPair::new(SignatureScheme::ED25519)
            .context("generate MLS device signer")?;
        signer
            .store(provider.storage())
            .map_err(|e| anyhow!("store MLS device signer: {e}"))?;
        let request = EnrollmentRequest {
            domain: CERT_DOMAIN.to_owned(),
            version: VERSION,
            account_id: account_id.clone(),
            device_id: device_id.clone(),
            mls_signature_public_key: signer.to_public_vec(),
        };
        Ok(PendingEnrollment {
            account_id,
            device_id,
            signer,
            provider,
            request,
        })
    }
    pub fn device_id(&self) -> &str {
        &self.device_id
    }
    pub fn account_id(&self) -> &[u8] {
        &self.account_id
    }
    pub const fn checkpoint(&self) -> u64 {
        self.checkpoint
    }

    pub fn create_account_group(
        &mut self,
        group_id: &[u8],
        backend: &mut impl StatePersistence,
    ) -> Result<()> {
        if group_id.is_empty() {
            bail!("group_id must be non-empty");
        }
        self.transaction(backend, |this| {
            drop(
                MlsGroup::new_with_group_id(
                    &this.provider,
                    &this.signer,
                    &create_config(),
                    GroupId::from_slice(group_id),
                    this.credential()?,
                )
                .context("create account group")?,
            );
            Ok(())
        })
    }
    pub fn generate_key_package(&mut self, backend: &mut impl StatePersistence) -> Result<Vec<u8>> {
        self.transaction(backend, |this| {
            let b = KeyPackage::builder()
                .build(
                    CIPHERSUITE,
                    &this.provider,
                    &this.signer,
                    this.credential()?,
                )
                .context("generate KeyPackage")?;
            b.key_package()
                .tls_serialize_detached()
                .context("serialize KeyPackage")
        })
    }
    pub fn add_device(
        &mut self,
        group_id: &[u8],
        key_package: &[u8],
        backend: &mut impl StatePersistence,
    ) -> Result<AddDeviceOutput> {
        self.transaction(backend, |this| {
            let mut group = this.load_group(group_id)?;
            let kp = KeyPackageIn::tls_deserialize_exact(key_package)
                .context("decode KeyPackage")?
                .validate(this.provider.crypto(), ProtocolVersion::Mls10)
                .context("validate KeyPackage")?;
            let cert = parse_certificate(kp.leaf_node().credential().serialized_content())?;
            this.validate_peer_certificate(&cert)?;
            if cert.request.device_id == this.device_id {
                bail!("cannot add local device twice");
            }
            if group.members().any(|m| {
                certificate_device(m.credential.serialized_content()).as_deref()
                    == Some(cert.request.device_id.as_str())
            }) {
                bail!("device_id is already a group member");
            }
            let (commit, welcome, _) = group
                .add_members(&this.provider, &this.signer, &[kp])
                .context("create add commit")?;
            Ok(AddDeviceOutput {
                commit: commit
                    .tls_serialize_detached()
                    .context("serialize add commit")?,
                welcome: welcome
                    .tls_serialize_detached()
                    .context("serialize Welcome")?,
            })
        })
    }
    pub fn join_from_welcome(
        &mut self,
        welcome: &[u8],
        backend: &mut impl StatePersistence,
    ) -> Result<Vec<u8>> {
        self.transaction(backend, |this| {
            let MlsMessageBodyIn::Welcome(w) = MlsMessageIn::tls_deserialize_exact(welcome)
                .context("decode Welcome")?
                .extract()
            else {
                bail!("wire message is not a Welcome")
            };
            let mut group = ProcessedWelcome::new_from_welcome(&this.provider, &join_config(), w)
                .context("process Welcome")?
                .into_staged_welcome(&this.provider, None)
                .context("stage Welcome")?
                .into_group(&this.provider)
                .context("join group")?;
            if let Err(e) = this.validate_account_group(&group) {
                let _ = group.delete(this.provider.storage());
                return Err(e.context("reject unauthorized Welcome"));
            }
            Ok(group.group_id().as_slice().to_vec())
        })
    }
    pub fn apply_commit(
        &mut self,
        group_id: &[u8],
        commit: &[u8],
        backend: &mut impl StatePersistence,
    ) -> Result<()> {
        self.transaction(backend, |this| {
            let mut group = this.load_group(group_id)?;
            let protocol = MlsMessageIn::tls_deserialize_exact(commit)
                .context("decode Commit")?
                .try_into_protocol_message()
                .context("not protocol message")?;
            let processed = group
                .process_message(&this.provider, protocol)
                .context("authenticate Commit")?;
            match processed.into_content() {
                ProcessedMessageContent::StagedCommitMessage(staged) => {
                    this.validate_staged_adds(&group, &staged)?;
                    group
                        .merge_staged_commit(&this.provider, *staged)
                        .context("merge Commit")?;
                    this.validate_account_group(&group)
                }
                _ => bail!("wire message is not a Commit"),
            }
        })
    }
    pub fn encrypt_application(
        &mut self,
        group_id: &[u8],
        aad: &MessageAad,
        plaintext: &[u8],
        backend: &mut impl StatePersistence,
    ) -> Result<Vec<u8>> {
        if plaintext.is_empty() {
            bail!("plaintext must be non-empty");
        }
        self.transaction(backend, |this| {
            let mut group = this.load_group(group_id)?;
            group.set_aad(aad.canonical_bytes()?);
            group
                .create_message(&this.provider, &this.signer, plaintext)
                .context("encrypt message")?
                .tls_serialize_detached()
                .context("serialize message")
        })
    }
    /// This is deliberately the only decrypt API: relay metadata must match the
    /// exact authenticated, versioned/domain-separated AAD before plaintext exits.
    pub fn decrypt_application_expected(
        &mut self,
        group_id: &[u8],
        ciphertext: &[u8],
        expected: &MessageAad,
        backend: &mut impl StatePersistence,
    ) -> Result<DecryptedApplication> {
        self.transaction(backend, |this| {
            let mut group = this.load_group(group_id)?;
            let protocol = MlsMessageIn::tls_deserialize_exact(ciphertext)
                .context("decode message")?
                .try_into_protocol_message()
                .context("not protocol message")?;
            let processed = group
                .process_message(&this.provider, protocol)
                .context("authenticate/decrypt message")?;
            let index = match processed.sender() {
                Sender::Member(i) => *i,
                _ => bail!("sender is not a member"),
            };
            let cert = group
                .members()
                .find(|m| m.index == index)
                .map(|m| parse_certificate(m.credential.serialized_content()))
                .transpose()?
                .ok_or_else(|| anyhow!("sender leaf missing"))?;
            this.validate_peer_certificate(&cert)?;
            let wire: CanonicalAad<'_> =
                serde_json::from_slice(processed.aad()).context("decode authenticated AAD")?;
            if wire.domain != AAD_DOMAIN || wire.version != VERSION {
                bail!("unsupported AAD domain/version");
            }
            let aad = MessageAad {
                cid: wire.cid.to_owned(),
                mid: wire.mid.to_owned(),
                direction: wire.direction,
            };
            if aad != *expected {
                bail!("authenticated MLS AAD does not match expected relay route");
            }
            let plaintext = match processed.into_content() {
                ProcessedMessageContent::ApplicationMessage(m) => m.into_bytes(),
                _ => bail!("not application message"),
            };
            Ok(DecryptedApplication {
                sender_device_id: cert.request.device_id,
                sender_leaf_index: index.u32(),
                aad,
                plaintext,
            })
        })
    }
    pub fn remove_device(
        &mut self,
        group_id: &[u8],
        device_id: &str,
        backend: &mut impl StatePersistence,
    ) -> Result<RemoveDeviceOutput> {
        self.transaction(backend, |this| {
            let mut group = this.load_group(group_id)?;
            let index = group
                .members()
                .find(|m| {
                    certificate_device(m.credential.serialized_content()).as_deref()
                        == Some(device_id)
                })
                .ok_or_else(|| anyhow!("device not in group"))?
                .index;
            let (commit, _, _) = group
                .remove_members(&this.provider, &this.signer, &[index])
                .context("create remove commit")?;
            Ok(RemoveDeviceOutput {
                commit: commit
                    .tls_serialize_detached()
                    .context("serialize remove commit")?,
            })
        })
    }
    pub fn finalize_pending_commit(
        &mut self,
        group_id: &[u8],
        backend: &mut impl StatePersistence,
    ) -> Result<()> {
        self.transaction(backend, |this| {
            this.load_group(group_id)?
                .merge_pending_commit(&this.provider)
                .context("merge pending commit")
        })
    }
    pub fn abort_pending_commit(
        &mut self,
        group_id: &[u8],
        backend: &mut impl StatePersistence,
    ) -> Result<()> {
        self.transaction(backend, |this| {
            this.load_group(group_id)?
                .clear_pending_commit(this.provider.storage())
                .map_err(|e| anyhow!("clear pending commit: {e}"))
        })
    }
    pub fn destroy_group(
        &mut self,
        group_id: &[u8],
        backend: &mut impl StatePersistence,
    ) -> Result<()> {
        self.transaction(backend, |this| {
            this.load_group(group_id)?
                .delete(this.provider.storage())
                .map_err(|e| anyhow!("delete group: {e}"))
        })
    }

    pub fn restore(
        account_id: &[u8],
        device_id: &str,
        backend: &impl StatePersistence,
    ) -> Result<Option<Self>> {
        let Some(mut stored) = backend.load(account_id, device_id)? else {
            return Ok(None);
        };
        let trusted = backend
            .trusted_checkpoint(account_id, device_id)?
            .ok_or_else(|| anyhow!("missing trusted checkpoint"))?;
        if trusted != stored.checkpoint {
            stored.encrypted_state.zeroize();
            bail!("stale or inconsistent persisted checkpoint");
        }
        let result = Self::import_secret_state(&stored.encrypted_state).and_then(|d| {
            if d.account_id != account_id || d.device_id != device_id || d.checkpoint != trusted {
                bail!("persisted account/device/checkpoint mismatch");
            }
            Ok(d)
        });
        stored.encrypted_state.zeroize();
        result.map(Some)
    }
    pub(crate) fn import_secret_state(bytes: &[u8]) -> Result<Self> {
        let p: PersistedDevice = serde_json::from_slice(bytes).context("decode secret state")?;
        if p.domain != STATE_DOMAIN
            || p.version != VERSION
            || p.account_id.is_empty()
            || p.device_id.is_empty()
        {
            bail!("unsupported or invalid secret state");
        }
        p.certificate.verify()?;
        if p.certificate.request.account_id != p.account_id
            || p.certificate.request.device_id != p.device_id
            || p.certificate.request.mls_signature_public_key != p.signature_public_key
        {
            bail!("certificate/state identity mismatch");
        }
        let map = p
            .storage
            .into_iter()
            .map(|(k, v)| {
                Ok((
                    URL_SAFE_NO_PAD.decode(k).context("decode storage key")?,
                    URL_SAFE_NO_PAD.decode(v).context("decode storage value")?,
                ))
            })
            .collect::<Result<HashMap<_, _>>>()?;
        let provider = Provider {
            crypto: RustCrypto::default(),
            storage: MemoryStorage {
                values: RwLock::new(map),
            },
        };
        let signer = SignatureKeyPair::read(
            provider.storage(),
            &p.signature_public_key,
            SignatureScheme::ED25519,
        )
        .ok_or_else(|| anyhow!("missing MLS signing key"))?;
        Ok(Self {
            account_id: p.account_id,
            device_id: p.device_id,
            certificate: p.certificate,
            signature_public_key: p.signature_public_key,
            signer,
            provider,
            checkpoint: p.checkpoint,
        })
    }
    fn transaction<T>(
        &mut self,
        backend: &mut impl StatePersistence,
        operation: impl FnOnce(&Self) -> Result<T>,
    ) -> Result<T> {
        let next = self
            .checkpoint
            .checked_add(1)
            .ok_or_else(|| anyhow!("checkpoint exhausted"))?;
        let mut snapshot = self.export_secret_state_at(self.checkpoint)?;
        let output = match operation(self) {
            Ok(v) => v,
            Err(e) => {
                let rollback = self.restore_storage_from(&snapshot);
                snapshot.zeroize();
                rollback?;
                return Err(e);
            }
        };
        let new_state = self.export_secret_state_at(next)?;
        if let Err(e) = backend.compare_and_swap(
            &self.account_id,
            &self.device_id,
            Some(self.checkpoint),
            StoredState {
                checkpoint: next,
                encrypted_state: new_state,
            },
        ) {
            let rollback = self.restore_storage_from(&snapshot);
            snapshot.zeroize();
            rollback?;
            return Err(e.context("atomically persist MLS transition"));
        }
        snapshot.zeroize();
        self.checkpoint = next;
        Ok(output)
    }
    fn export_secret_state_at(&self, checkpoint: u64) -> Result<Vec<u8>> {
        let mut storage = self
            .provider
            .storage
            .values
            .read()
            .map_err(|_| anyhow!("storage lock poisoned"))?
            .iter()
            .map(|(k, v)| (URL_SAFE_NO_PAD.encode(k), URL_SAFE_NO_PAD.encode(v)))
            .collect::<Vec<_>>();
        storage.sort_unstable();
        serde_json::to_vec(&PersistedDevice {
            domain: STATE_DOMAIN.to_owned(),
            version: VERSION,
            checkpoint,
            account_id: self.account_id.clone(),
            device_id: self.device_id.clone(),
            certificate: self.certificate.clone(),
            signature_public_key: self.signature_public_key.clone(),
            storage,
        })
        .context("serialize secret state")
    }
    fn restore_storage_from(&self, bytes: &[u8]) -> Result<()> {
        let p: PersistedDevice = serde_json::from_slice(bytes).context("decode rollback state")?;
        let map = p
            .storage
            .into_iter()
            .map(|(k, v)| Ok((URL_SAFE_NO_PAD.decode(k)?, URL_SAFE_NO_PAD.decode(v)?)))
            .collect::<Result<HashMap<_, _>>>()?;
        *self
            .provider
            .storage
            .values
            .write()
            .map_err(|_| anyhow!("storage lock poisoned"))? = map;
        Ok(())
    }
    fn credential(&self) -> Result<CredentialWithKey> {
        Ok(CredentialWithKey {
            credential: BasicCredential::new(self.certificate.credential_bytes()?).into(),
            signature_key: self.signature_public_key.clone().into(),
        })
    }
    fn load_group(&self, id: &[u8]) -> Result<MlsGroup> {
        MlsGroup::load(self.provider.storage(), &GroupId::from_slice(id))
            .context("load group")?
            .ok_or_else(|| anyhow!("group does not exist"))
    }
    fn validate_peer_certificate(&self, cert: &DeviceCertificate) -> Result<()> {
        cert.verify()?;
        if cert.account_root_public_key != self.certificate.account_root_public_key
            || cert.request.account_id != self.account_id
        {
            bail!("device certificate belongs to another account identity root");
        }
        Ok(())
    }
    fn validate_account_group(&self, group: &MlsGroup) -> Result<()> {
        let mut ids = HashSet::new();
        for m in group.members() {
            let c = parse_certificate(m.credential.serialized_content())?;
            self.validate_peer_certificate(&c)?;
            if c.request.mls_signature_public_key.as_slice() != m.signature_key.as_slice() {
                bail!("certificate does not bind MLS leaf signing key");
            }
            if !ids.insert(c.request.device_id) {
                bail!("duplicate device certificate");
            }
        }
        if group.is_active() && !ids.contains(&self.device_id) {
            bail!("group lacks local device");
        }
        Ok(())
    }
    fn validate_staged_adds(&self, group: &MlsGroup, staged: &StagedCommit) -> Result<()> {
        let mut ids = group
            .members()
            .filter_map(|m| certificate_device(m.credential.serialized_content()))
            .collect::<HashSet<_>>();
        for p in staged.add_proposals() {
            let leaf = p.add_proposal().key_package().leaf_node();
            let c = parse_certificate(leaf.credential().serialized_content())?;
            self.validate_peer_certificate(&c)?;
            if c.request.mls_signature_public_key.as_slice() != leaf.signature_key().as_slice() {
                bail!("certificate does not bind added MLS signing key");
            }
            if !ids.insert(c.request.device_id) {
                bail!("Commit adds duplicate device");
            }
        }
        Ok(())
    }
}

fn parse_certificate(bytes: &[u8]) -> Result<DeviceCertificate> {
    let c: DeviceCertificate =
        serde_json::from_slice(bytes).context("credential is not a device certificate")?;
    c.verify()?;
    Ok(c)
}
fn certificate_device(bytes: &[u8]) -> Option<String> {
    parse_certificate(bytes).ok().map(|c| c.request.device_id)
}
fn create_config() -> MlsGroupCreateConfig {
    MlsGroupCreateConfig::builder()
        .ciphersuite(CIPHERSUITE)
        .use_ratchet_tree_extension(true)
        .max_past_epochs(0)
        .sender_ratchet_configuration(SenderRatchetConfiguration::new(
            OUT_OF_ORDER_TOLERANCE,
            MAX_FORWARD_DISTANCE,
        ))
        .build()
}
fn join_config() -> MlsGroupJoinConfig {
    MlsGroupJoinConfig::builder()
        .use_ratchet_tree_extension(true)
        .max_past_epochs(0)
        .sender_ratchet_configuration(SenderRatchetConfiguration::new(
            OUT_OF_ORDER_TOLERANCE,
            MAX_FORWARD_DISTANCE,
        ))
        .build()
}
