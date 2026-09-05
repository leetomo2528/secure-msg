package com.yunjelee.securemsg

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yunjelee.securemsg.ui.LastOpened
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "securemsg",
    // A half-written preferences file throws CorruptionException from every
    // read — including the ones outside fromPreferences' recovery — so without
    // a handler the activity crash-loops until the user clears app data, which
    // also destroys the keypair every stored envelope is sealed to.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

object Credentials {
    private val K_USERNAME = stringPreferencesKey("username")
    private val K_UID = stringPreferencesKey("uid")
    private val K_SID = stringPreferencesKey("sid")
    private val K_TOKEN = stringPreferencesKey("token")
    private val K_BOX_PK = stringPreferencesKey("box_pk")
    private val K_BOX_SK = stringPreferencesKey("box_sk")
    private val K_SIGN_PK = stringPreferencesKey("sign_pk")
    private val K_SIGN_SK = stringPreferencesKey("sign_sk")
    private val K_DEVICE_NAME = stringPreferencesKey("device_name")
    private val K_SECRET_ENVELOPE = stringPreferencesKey("credential_secrets_v1")
    private val mutex = Mutex()
    private val cipher = AndroidCredentialCipher()

    suspend fun save(ctx: Context, data: SavedCredentials) {
        mutex.withLock { saveUnlocked(ctx, data) }
    }

    private suspend fun saveUnlocked(ctx: Context, data: SavedCredentials) {
        val encrypted = cipher.encrypt(
            CredentialSecretCodec.encode(
                CredentialSecrets(data.token, data.keypair.boxSk, data.keypair.signSk),
            ),
        )
        val previous = ctx.dataStore.data.first()
        if (isDifferentLocalIdentity(
                previous[K_USERNAME],
                previous[K_UID]?.toIntOrNull(),
                data.username,
                data.uid,
            )
        ) {
            clearAccountScopedData(ctx)
        }
        ctx.dataStore.edit { p ->
            p[K_USERNAME] = data.username
            p[K_UID] = data.uid.toString()
            p[K_SID] = data.sid
            p[K_BOX_PK] = data.keypair.boxPk
            p[K_SIGN_PK] = data.keypair.signPk
            p[K_DEVICE_NAME] = data.deviceName
            p[K_SECRET_ENVELOPE] = encrypted
            removeLegacySecrets(p)
        }
    }

    suspend fun load(ctx: Context): SavedCredentials? {
        val saved = loadDevice(ctx) ?: return null
        return saved.takeIf { it.token.isNotBlank() }
    }

    /** Load this installation's keypair even while its login token is cleared. */
    suspend fun loadDevice(ctx: Context): SavedCredentials? {
        return mutex.withLock { fromPreferences(ctx, ctx.dataStore.data.first()) }
    }

    /** Emits whenever login state or the installation key record changes. */
    fun observeDevice(ctx: Context): Flow<SavedCredentials?> = ctx.dataStore.data
        .map { p -> mutex.withLock { fromPreferences(ctx, p) } }
        .distinctUntilChanged()

    private suspend fun fromPreferences(ctx: Context, p: Preferences): SavedCredentials? {
        val username = p[K_USERNAME] ?: return null
        val uid = p[K_UID]?.toIntOrNull() ?: return null
        val sid = p[K_SID] ?: return null
        val boxPk = p[K_BOX_PK] ?: return null
        val signPk = p[K_SIGN_PK] ?: return null
        val secrets = try {
            p[K_SECRET_ENVELOPE]?.let { encrypted ->
                CredentialSecretCodec.decode(cipher.decrypt(encrypted))
            } ?: legacySecrets(p)?.also { migrateLegacySecrets(ctx, it) }
        } catch (e: Exception) {
            // Tampering, truncated data, backup without its hardware key, or key invalidation must
            // never crash startup. Remove the unusable identity so UI and service require login.
            //
            // Only when the failure is permanent, though: BootReceiver and the post-update
            // restart both reach here while the Keystore provider can still be unavailable, and
            // wiping over a transient throw would delete the box secret key every relay envelope
            // addressed to this phone is sealed to, silently dropping the device out of the
            // account. Returning null already keeps startup alive without destroying anything.
            if (isPermanentCredentialFailure(e)) {
                clearBrokenCredentials(ctx)
            } else {
                Log.w("Credentials", "Credential envelope temporarily unreadable", e)
            }
            return null
        } ?: return null
        return SavedCredentials(
            username = username,
            uid = uid,
            sid = sid,
            token = secrets.token,
            deviceName = p[K_DEVICE_NAME] ?: "android",
            keypair = CryptoUtil.DeviceKeypair(boxPk, secrets.boxSk, signPk, secrets.signSk),
        )
    }

    suspend fun clear(ctx: Context) {
        mutex.withLock {
            ctx.dataStore.edit { it.clear() }
            runCatching(cipher::deleteKey)
        }
    }

    /**
     * Persist a renewed relay token without touching the identity or keys.
     * Goes through [saveUnlocked], NOT [saveSecretsUnlocked] — the latter
     * always writes an empty token (it exists for [clearSession]).
     */
    suspend fun updateToken(ctx: Context, token: String): Boolean {
        mutex.withLock {
            val current = fromPreferences(ctx, ctx.dataStore.data.first()) ?: return false
            saveUnlocked(ctx, current.copy(token = token))
            return true
        }
    }

    /** Log out without orphaning the server-side device/public key. */
    suspend fun clearSession(ctx: Context) {
        mutex.withLock {
            val current = fromPreferences(ctx, ctx.dataStore.data.first()) ?: return
            saveSecretsUnlocked(ctx, current.copy(token = ""))
        }
    }

    private suspend fun saveSecretsUnlocked(ctx: Context, current: SavedCredentials) {
        val encrypted = cipher.encrypt(
            CredentialSecretCodec.encode(
                CredentialSecrets("", current.keypair.boxSk, current.keypair.signSk),
            ),
        )
        ctx.dataStore.edit { p ->
            p[K_SECRET_ENVELOPE] = encrypted
            removeLegacySecrets(p)
        }
    }

    private fun legacySecrets(p: Preferences): CredentialSecrets? {
        val boxSk = p[K_BOX_SK] ?: return null
        val signSk = p[K_SIGN_SK] ?: return null
        return CredentialSecrets(p[K_TOKEN].orEmpty(), boxSk, signSk)
    }

    private suspend fun migrateLegacySecrets(ctx: Context, secrets: CredentialSecrets) {
        val encrypted = cipher.encrypt(CredentialSecretCodec.encode(secrets))
        ctx.dataStore.edit { p ->
            p[K_SECRET_ENVELOPE] = encrypted
            removeLegacySecrets(p)
        }
    }

    /**
     * A retry can never open the envelope again: the ciphertext failed its tag, the
     * envelope/codec framing is not what this build writes, or the hardware key is gone
     * or invalidated. Every other throw (KeyStoreException, ProviderException, IOException,
     * UnrecoverableKeyException) can be the provider being momentarily unavailable.
     */
    private fun isPermanentCredentialFailure(e: Exception): Boolean = when (e) {
        is KeyPermanentlyInvalidatedException -> true
        is AEADBadTagException -> true
        is IllegalArgumentException -> true
        is IllegalStateException -> true
        else -> false
    }

    private suspend fun clearBrokenCredentials(ctx: Context) {
        ctx.dataStore.edit { it.clear() }
        runCatching(cipher::deleteKey)
        wipeAccountData(ctx)
    }

    private suspend fun clearAccountScopedData(ctx: Context) = withContext(Dispatchers.IO) {
        AppDatabase.get(ctx).clearAllTables()
        BlocklistSync.clear(ctx)
        ContactSync.clearStatus(ctx)
    }

    /**
     * Everything on this phone that belongs to the account being removed, for the paths that
     * remove the identity itself. On top of [clearAccountScopedData] this covers the
     * device-local prefs that are really per-account: a different account signing in here must
     * not inherit the previous one's stars, pins or read positions. One owner, because a list
     * that lives in two places drifts — the forget-device button and broken-credential
     * recovery used to clear different sets.
     */
    suspend fun wipeAccountData(ctx: Context) = withContext(Dispatchers.IO) {
        clearAccountScopedData(ctx)
        Favorites.clear(ctx)
        PinnedConversations.clear(ctx)
        LastOpened.clear(ctx)
    }

    private fun removeLegacySecrets(p: androidx.datastore.preferences.core.MutablePreferences) {
        p.remove(K_TOKEN)
        p.remove(K_BOX_SK)
        p.remove(K_SIGN_SK)
    }
}

/** Prevent one account from inheriting another account's local plaintext caches. */
internal fun isDifferentLocalIdentity(
    existingUsername: String?,
    existingUid: Int?,
    incomingUsername: String,
    incomingUid: Int,
): Boolean = existingUsername != incomingUsername || existingUid != incomingUid

data class SavedCredentials(
    val username: String,
    val uid: Int,
    val sid: String,
    val token: String,
    val deviceName: String,
    val keypair: CryptoUtil.DeviceKeypair,
)
