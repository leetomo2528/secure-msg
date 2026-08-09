package com.yunjelee.securemsg

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "securemsg")

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
        } catch (_: Exception) {
            // Tampering, truncated data, backup without its hardware key, or key invalidation must
            // never crash startup. Remove the unusable identity so UI and service require login.
            clearBrokenCredentials(ctx)
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

    private suspend fun clearBrokenCredentials(ctx: Context) {
        ctx.dataStore.edit { it.clear() }
        runCatching(cipher::deleteKey)
        clearAccountScopedData(ctx)
    }

    private suspend fun clearAccountScopedData(ctx: Context) = withContext(Dispatchers.IO) {
        AppDatabase.get(ctx).clearAllTables()
        BlocklistSync.clear(ctx)
        ContactSync.clearStatus(ctx)
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
