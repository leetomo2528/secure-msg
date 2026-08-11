# SecureMsg crypto-core

Isolated Rust foundation for SecureMsg's future account-device Messaging Layer
Security (MLS) protocol. This crate uses the real RFC 9420 state machine from
OpenMLS; it does not implement a custom ratchet or claim compatibility with the
current v0.10 envelope protocol.

## What is implemented

- one MLS group per SecureMsg account's approved devices;
- account identity-root-signed device certificates embedded in MLS Basic
  Credentials, binding the domain/version, account, device ID, root key, and
  exact MLS Ed25519 leaf signing key;
- single-use KeyPackage generation;
- staged add/remove Commit generation, explicit finalize/abort, and offline
  Welcome consumption;
- authenticated application messages with canonical JSON AAD containing `cid`,
  `mid`, and `direction`;
- bounded same-epoch reordering (`32` generations, forward distance `1000`);
- `max_past_epochs = 0`, so a Commit discards the preceding epoch's application
  secrets rather than retaining them for late messages;
- mandatory transactional persistence on every MLS mutation, using monotonic
  trusted checkpoints and atomic compare-and-swap snapshot replacement;
- explicit whole-group cryptographic erasure through `destroy_group()`.

All MLS network objects use TLS codec wire encoding. They must not be decoded or
re-encoded as ad-hoc JSON by the delivery server.

## Security semantics

`MessageAad` is visible to the delivery server but covered by the MLS content
signature and AEAD authentication. The relay cannot change those fields without
decryption failing. The SMS body remains encrypted.

There is no unchecked decrypt API. `decrypt_application_expected()` requires
the caller's exact route metadata and returns plaintext only after MLS
authentication and the receiver-ratchet state CAS both succeed. It returns the
verified certificate device ID and MLS leaf index.

`add_device()` and `remove_device()` leave a pending Commit. The caller must
publish its Commit/Welcome idempotently, then call `finalize_pending_commit()`
only after the delivery service accepts it. On rejection, call
`abort_pending_commit()`. An aborted but already leaked Welcome cannot be
cryptographically recalled; therefore a Welcome must never be delivered before
the corresponding Commit is accepted.

OpenMLS advances sender ratchets and asks its `StorageProvider` to delete
consumed and expired secrets. `max_past_epochs = 0` explicitly prevents old
epoch retention. The configured reordering window necessarily retains a bounded
set of skipped same-epoch message keys, trading some forward secrecy for relay
reordering tolerance.

`destroy_group()` deletes the reloadable group tree, epoch secrets, message
secrets, proposals, and configuration from the provider. It does not prove
physical erasure from RAM, flash translation layers, browser storage, WAL files,
backups, crash dumps, or filesystem snapshots. `MemoryStorage` uses ordinary
heap allocations and does not zeroize removed map entries. Production hosts must
encrypt state with a non-exportable platform key, atomically replace the sole
latest snapshot, prohibit stale backups, and use platform-key destruction as the
documented cryptographic-erasure boundary.

Every mutating method takes `&mut AccountDevice` and a mutable
`StatePersistence`. The backend must atomically compare the prior trusted
checkpoint, replace the encrypted latest-state bytes, and advance the trusted
checkpoint. On an MLS-operation or CAS failure, the crate restores the exact
pre-call OpenMLS storage map and returns no output/plaintext. Restore rejects
missing trusted checkpoints, stale/inconsistent checkpoints, and account or
device mismatches. A backend that separates the blob write from checkpoint
advancement does not satisfy this contract.

The exported state is secret. It includes the Ed25519 signing private key, HPKE
KeyPackage material, live epoch secrets, and sender-ratchet state. Never upload
or store it unencrypted. The provided `StatePersistence` trait is deliberately a
host boundary, not a secure storage implementation.

## Tests

```bash
export PATH=/opt/homebrew/opt/rustup/bin:$PATH
cd crypto-core
cargo fmt --check
cargo test --all-targets
cargo clippy --all-targets -- -D warnings
cargo build --target wasm32-unknown-unknown
```

The lifecycle suite covers two- and three-device operation, restart before an
offline Welcome, canonical AAD authentication, ciphertext tamper rejection,
same-epoch ordered/out-of-order delivery at and beyond the 32-generation
boundary, the 1000-generation maximum-forward boundary, old-epoch rejection,
ordered catch-up after multiple offline Commits, add/remove, single-use Welcome
behavior, removed-member rejection even without receiving its removal Commit,
pending Commit finalize/abort after restart, account-root authorization,
persistence-failure rollback, stale snapshot/account/device rejection,
plaintext-marker absence, and final group destruction after reload.

Cryptographic output itself is intentionally nondeterministic because production
entropy and fresh MLS secrets are required. The tests are deterministic protocol
scenarios and interoperability checks between independently persisted device
states; they are not fixed-ciphertext KATs.

## Remaining integration work and blockers

This crate is not wired into the production application yet. Shipping protocol
v2 still requires all of the following:

1. **WASM host integration:** this crate now builds a `cdylib` with an opaque
   `wasm-bindgen` API and contains a browser lifecycle test. The in-instance CAS
   adapter is not durable storage. Production must run the state machine in one
   worker/mutex and encrypt/export each latest snapshot into a crash-safe,
   rollback-resistant WebCrypto/IndexedDB adapter. IndexedDB is asynchronous
   while OpenMLS storage calls are synchronous, so the host must finish the
   durable transaction before accepting the wrapper's exported checkpoint.
2. **Android JNI/UniFFI:** build an AAR for every supported ABI, define stable
   byte/error ownership across JNI, serialize all group mutations, and connect
   encrypted snapshots to Android Keystore. OpenMLS provides no official Kotlin
   AAR.
3. **Delivery state machine:** idempotent Commit/Welcome upload, pending Commit
   recovery after restart, accept/reject acknowledgement, epoch-order queues,
   fork handling, replay limits, and transactional client-state rollback.
4. **Account-root UX and custody:** the core rejects KeyPackages, Welcome
   members, and remote Adds whose root-signed certificate is invalid or belongs
   to another pinned root. Production still needs secure root-key custody,
   existing-device approval UI, Safety Number/QR comparison, recovery and
   revocation distribution, plus key-transparency/split-view detection.
5. **Migration:** add a new protocol version, v2-only send policy, dual-read for
   retained v1 history, and a tested rollback plan. Do not silently mix static
   v0.10 envelopes with MLS epochs.
6. **History policy:** a Welcome grants only current/future group access. Past
   plaintext needs a separately authenticated device-to-device transfer or an
   encrypted recovery backup; MLS must not be weakened by retaining every old
   epoch.
7. **Secure deletion:** browser GC/IndexedDB and Android flash cannot guarantee
   physical overwrite. Claims must be limited to bounded key retention and
   cryptographic erasure by destruction of the platform wrapping key.

## Versions and licenses

The lockfile pins the stable OpenMLS release line rather than the `0.9` release
candidate:

| Package | Version | License |
|---|---:|---|
| `openmls` | `0.8.1` | MIT |
| `openmls_traits` | `0.5.0` | MIT |
| `openmls_rust_crypto` | `0.5.1` | MIT |
| `openmls_memory_storage` | `0.5.0` | MIT |
| `openmls_basic_credential` | `0.5.0` | MIT |
| `tls_codec` | `0.4.2` | MIT OR Apache-2.0 |

This crate's own source is MIT. The complete binary is **not MIT-only**:
`openmls_rust_crypto` transitively uses MPL-2.0 `hpke-rs` packages, BSD-3-Clause
`ed25519-dalek`, and MIT/Apache-2.0 RustCrypto packages. Those dependencies keep
their original licenses. Release packaging must include the generated
`THIRD_PARTY_NOTICES.md`/SBOM and satisfy MPL-2.0 file-level source obligations;
the dependency graph must not be relabeled as MIT.

Primary references:

- <https://docs.rs/openmls/0.8.1/openmls/>
- <https://book.openmls.tech/user_manual/persistence.html>
- <https://github.com/openmls/openmls>
