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

    suspend fun save(ctx: Context, data: SavedCredentials) {
        ctx.dataStore.edit { p ->
            p[K_USERNAME] = data.username
            p[K_UID] = data.uid.toString()
            p[K_SID] = data.sid
            p[K_TOKEN] = data.token
            p[K_BOX_PK] = data.keypair.boxPk
            p[K_BOX_SK] = data.keypair.boxSk
            p[K_SIGN_PK] = data.keypair.signPk
            p[K_SIGN_SK] = data.keypair.signSk
            p[K_DEVICE_NAME] = data.deviceName
        }
    }

    suspend fun load(ctx: Context): SavedCredentials? {
        val saved = loadDevice(ctx) ?: return null
        return saved.takeIf { it.token.isNotBlank() }
    }

    /** Load this installation's keypair even while its login token is cleared. */
    suspend fun loadDevice(ctx: Context): SavedCredentials? {
        return fromPreferences(ctx.dataStore.data.first())
    }

    /** Emits whenever login state or the installation key record changes. */
    fun observeDevice(ctx: Context): Flow<SavedCredentials?> = ctx.dataStore.data
        .map(::fromPreferences)
        .distinctUntilChanged()

    private fun fromPreferences(p: Preferences): SavedCredentials? {
        val username = p[K_USERNAME] ?: return null
        val uid = p[K_UID]?.toIntOrNull() ?: return null
        val sid = p[K_SID] ?: return null
        val token = p[K_TOKEN].orEmpty()
        val boxPk = p[K_BOX_PK] ?: return null
        val boxSk = p[K_BOX_SK] ?: return null
        val signPk = p[K_SIGN_PK] ?: return null
        val signSk = p[K_SIGN_SK] ?: return null
        return SavedCredentials(
            username = username,
            uid = uid,
            sid = sid,
            token = token,
            deviceName = p[K_DEVICE_NAME] ?: "android",
            keypair = CryptoUtil.DeviceKeypair(boxPk, boxSk, signPk, signSk),
        )
    }

    suspend fun clear(ctx: Context) {
        ctx.dataStore.edit { it.clear() }
    }

    /** Log out without orphaning the server-side device/public key. */
    suspend fun clearSession(ctx: Context) {
        ctx.dataStore.edit { it.remove(K_TOKEN) }
    }
}

data class SavedCredentials(
    val username: String,
    val uid: Int,
    val sid: String,
    val token: String,
    val deviceName: String,
    val keypair: CryptoUtil.DeviceKeypair,
)
