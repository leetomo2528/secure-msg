package com.yunjelee.securemsg

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sms_threads")
data class SmsThread(
    @PrimaryKey val cid: String,
    val phoneNumber: String,
    /** Relay-owned conversation label. Contact sync must never mutate this field. */
    @ColumnInfo(name = "contactName") val serverName: String?,
    val lastSeq: Int = 0,
    /** Local ordering key; advances even while a relay sequence is unavailable. */
    val lastActivityAt: Long = 0L,
    /** Android address-book name read on this device. */
    val localContactName: String? = null,
    /** Contact name shared through the relay by one of this account's devices. */
    val syncedContactName: String? = null,
) {
    val displayName: String
        get() = localContactName?.takeIf { it.isNotBlank() }
            ?: syncedContactName?.takeIf { it.isNotBlank() }
            ?: serverName?.takeIf { it.isNotBlank() }
            ?: phoneNumber

    val showsPhoneSubtitle: Boolean
        get() = displayName != phoneNumber
}

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["serverKey"], unique = true),
        Index(value = ["cid", "seq"]),
    ],
)
data class MessageRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cid: String,
    val seq: Int,
    val senderSid: String,
    val plaintext: String,
    val createdAt: Long,
    val mine: Boolean,
    val blocked: Boolean = false,
    val contentType: String = "text",
    val subject: String? = null,
    val attachmentsJson: String? = null,
    /** Stable dedupe key for acknowledged relay rows. NULL for provisional local sends. */
    val serverKey: String? = null,
    val carrierStatus: String = "none",
    val carrierError: String? = null,
    val carrierUpdatedAt: Long? = null,
)

@Entity(tableName = "blocklist")
data class BlockKeyword(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "blocked_sms")
data class BlockedSms(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phoneNumber: String,
    val body: String,
    val reason: String,
    val receivedAt: Long,
)

@Entity(tableName = "blocked_senders")
data class BlockedSender(
    @PrimaryKey val phoneNumber: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "processed_sms")
data class ProcessedSms(
    @PrimaryKey val providerId: Long,
    val processedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "device_cache")
data class DeviceCache(
    @PrimaryKey val sid: String,
    val userId: Int,
    val name: String,
    val pubKey: String,
)

/** Immutable TOFU/approval pin. Key material is never overwritten on conflict. */
@Entity(tableName = "trusted_device_pins", indices = [Index("accountUid")])
data class TrustedDevicePin(
    @PrimaryKey val sid: String,
    val accountUid: Long,
    val name: String,
    val kind: String,
    val pubKey: String,
    val sigPub: String,
    val fingerprint: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
)

/** Last accepted, monotonic key-directory view for split-view/rollback detection. */
@Entity(tableName = "trust_directory_state")
data class TrustDirectoryState(
    @PrimaryKey val accountUid: Long,
    val identityKey: String,
    val epoch: Long,
    val directoryHash: String,
    val safetyNumber: String,
    val updatedAt: Long,
)

/** Atomic idempotency claim for relay messages that may trigger carrier SMS. */
@Entity(tableName = "relay_receipts", primaryKeys = ["cid", "seq"])
data class RelayReceipt(
    val cid: String,
    val seq: Int,
    val claimedAt: Long = System.currentTimeMillis(),
    val status: String = "claimed",
    val lastError: String? = null,
    val sentAt: Long? = null,
    val deliveredAt: Long? = null,
    val statusSynced: Boolean = false,
)

/** Durable bridge work item. The payload is encrypted; plaintext is retained only
 * for local presentation after a relay ACK and never leaves the device in clear. */
@Entity(
    tableName = "relay_outbox",
    indices = [Index(value = ["mid"], unique = true), Index(value = ["relayState", "createdAt"])]
)
data class RelayOutbox(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mid: String,
    val cid: String,
    val payload: String,
    val plaintext: String,
    val contentType: String = "text",
    val subject: String? = null,
    val attachmentsJson: String? = null,
    val phoneNumber: String,
    val providerId: Long? = null,
    val localMessageId: Long? = null,
    val direction: String = "incoming_sms",
    val carrierState: String = "not_applicable",
    /** True until the current carrierState is acknowledged by the relay server. */
    val carrierStatusPending: Boolean = false,
    val relayState: String = "pending",
    val serverSeq: Int? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "processed_mms")
data class ProcessedMms(
    @PrimaryKey val providerId: Long,
    val processedAt: Long = System.currentTimeMillis(),
)

/** Durable per-part callback aggregation for multipart SMS SENT/DELIVERED results. */
@Entity(tableName = "carrier_part_results", primaryKeys = ["mid", "action", "part"])
data class CarrierPartResult(
    val mid: String,
    val action: String,
    val part: Int,
    val partCount: Int,
    val successful: Boolean,
    val resultCode: Int,
    val receivedAt: Long = System.currentTimeMillis(),
)

@Dao
interface ThreadDao {
    @Query("SELECT * FROM sms_threads ORDER BY lastActivityAt DESC, lastSeq DESC")
    fun observeAll(): Flow<List<SmsThread>>

    @Query("SELECT * FROM sms_threads WHERE cid = :cid")
    suspend fun get(cid: String): SmsThread?

    @Query("SELECT * FROM sms_threads WHERE phoneNumber = :phone ORDER BY CASE WHEN cid LIKE 'local_%' THEN 1 ELSE 0 END, lastSeq DESC LIMIT 1")
    suspend fun getByPhone(phone: String): SmsThread?

    @Query("SELECT * FROM sms_threads")
    suspend fun getAll(): List<SmsThread>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(thread: SmsThread)

    @Update
    suspend fun update(thread: SmsThread)

    @Query("DELETE FROM sms_threads WHERE cid = :cid")
    suspend fun deleteByCid(cid: String)

    @Query("UPDATE sms_threads SET lastSeq = MAX(lastSeq, :seq) WHERE cid = :cid")
    suspend fun advanceLastSeq(cid: String, seq: Int)

    @Query("UPDATE sms_threads SET lastActivityAt = MAX(lastActivityAt, :at) WHERE cid = :cid")
    suspend fun touch(cid: String, at: Long)

    @Query("UPDATE sms_threads SET localContactName = :name WHERE cid = :cid")
    suspend fun updateLocalContactNameByCid(cid: String, name: String?)

    @Query("UPDATE sms_threads SET syncedContactName = :name WHERE cid = :cid")
    suspend fun updateSyncedContactNameByCid(cid: String, name: String?)

    @Query("UPDATE sms_threads SET contactName = :name WHERE cid = :cid")
    suspend fun updateServerNameByCid(cid: String, name: String?)
}

@Dao
interface MessageDao {
    // Newest-first matches the reverse-layout conversation list: index 0 is
    // physically anchored at the bottom while older messages extend upward.
    @Query("SELECT * FROM messages WHERE cid = :cid ORDER BY createdAt DESC, id DESC")
    fun observeForCid(cid: String): Flow<List<MessageRow>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: Long): MessageRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(msg: MessageRow): Long

    @Query("UPDATE messages SET seq = :seq WHERE id = :id")
    suspend fun updateSeq(id: Long, seq: Int)

    @Query("UPDATE messages SET seq = :seq, serverKey = cid || ':' || :seq, contentType = :contentType, subject = :subject, attachmentsJson = :attachmentsJson WHERE id = :id")
    suspend fun updateRelayResult(
        id: Long,
        seq: Int,
        contentType: String,
        subject: String?,
        attachmentsJson: String?,
    )

    @Query("DELETE FROM messages WHERE serverKey = :serverKey AND id != :localId")
    suspend fun deleteServerDuplicate(serverKey: String, localId: Long)

    @Query("UPDATE messages SET blocked = :blocked WHERE cid = :cid AND seq = :seq")
    suspend fun setBlocked(cid: String, seq: Int, blocked: Boolean)

    @Query("UPDATE messages SET carrierStatus = :status, carrierError = :error, carrierUpdatedAt = :updatedAt WHERE id = :id")
    suspend fun setCarrierStatusById(
        id: Long,
        status: String,
        error: String?,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE messages SET carrierStatus = :status, carrierError = :error, carrierUpdatedAt = :updatedAt WHERE serverKey = :cid || ':' || :seq")
    suspend fun setCarrierStatus(
        cid: String,
        seq: Int,
        status: String,
        error: String?,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE messages SET cid = :newCid WHERE cid = :oldCid AND serverKey IS NULL")
    suspend fun moveProvisionalConversation(oldCid: String, newCid: String)

    /**
     * Merge a stale local SMS thread into the currently authoritative server
     * conversation. Historical serverKey values intentionally stay unchanged:
     * they remain valid dedupe identifiers for the old relay conversation.
     */
    @Query("UPDATE messages SET cid = :newCid WHERE cid = :oldCid")
    suspend fun moveConversation(oldCid: String, newCid: String)
}

@Dao
interface BlocklistDao {
    @Query("SELECT * FROM blocklist ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BlockKeyword>>

    @Query("SELECT * FROM blocklist")
    suspend fun getAll(): List<BlockKeyword>

    @Insert
    suspend fun insert(kw: BlockKeyword)

    @Delete
    suspend fun delete(kw: BlockKeyword)
}

@Dao
interface BlockedSmsDao {
    @Query("SELECT * FROM blocked_sms ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<BlockedSms>>

    @Insert
    suspend fun insert(msg: BlockedSms): Long

    @Delete
    suspend fun delete(msg: BlockedSms)
}

@Dao
interface BlockedSenderDao {
    @Query("SELECT * FROM blocked_senders ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BlockedSender>>

    @Query("SELECT * FROM blocked_senders")
    suspend fun getAll(): List<BlockedSender>

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_senders WHERE phoneNumber = :phone)")
    suspend fun contains(phone: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sender: BlockedSender)

    @Delete
    suspend fun delete(sender: BlockedSender)
}

@Dao
interface ProcessedSmsDao {
    @Query("SELECT EXISTS(SELECT 1 FROM processed_sms WHERE providerId = :providerId)")
    suspend fun contains(providerId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ProcessedSms)
}

@Dao
interface DeviceCacheDao {
    @Query("SELECT * FROM device_cache WHERE sid = :sid")
    suspend fun get(sid: String): DeviceCache?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(d: DeviceCache)

    @Query("UPDATE device_cache SET name = :name WHERE sid = :sid")
    suspend fun updateName(sid: String, name: String)

    /** TOFU pin: metadata may advance, but a SID can never silently change account/box key. */
    @Transaction
    suspend fun pinOrReject(d: DeviceCache): Boolean {
        val existing = get(d.sid)
        if (existing == null) {
            insert(d)
            return true
        }
        if (existing.userId != d.userId || existing.pubKey != d.pubKey) return false
        updateName(d.sid, d.name)
        return true
    }
}

@Dao
interface DeviceTrustDao {
    @Query("SELECT * FROM trusted_device_pins WHERE accountUid = :uid ORDER BY name, sid")
    fun observePins(uid: Long): Flow<List<TrustedDevicePin>>

    @Query("SELECT * FROM trusted_device_pins WHERE accountUid = :uid ORDER BY name, sid")
    suspend fun getPins(uid: Long): List<TrustedDevicePin>

    @Query("SELECT * FROM trusted_device_pins WHERE sid = :sid")
    suspend fun getPin(sid: String): TrustedDevicePin?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPin(pin: TrustedDevicePin)

    @Query("UPDATE trusted_device_pins SET name = :name, lastSeenAt = :at WHERE sid = :sid")
    suspend fun touchPin(sid: String, name: String, at: Long)

    @Query("SELECT * FROM trust_directory_state WHERE accountUid = :uid")
    fun observeState(uid: Long): Flow<TrustDirectoryState?>

    @Query("SELECT * FROM trust_directory_state WHERE accountUid = :uid")
    suspend fun getState(uid: Long): TrustDirectoryState?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertState(state: TrustDirectoryState)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateState(state: TrustDirectoryState)
}

@Dao
interface RelayReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun claim(receipt: RelayReceipt): Long

    @Query("DELETE FROM relay_receipts WHERE cid = :cid AND seq = :seq")
    suspend fun release(cid: String, seq: Int)

    @Query("SELECT * FROM relay_receipts WHERE cid = :cid AND seq = :seq")
    suspend fun get(cid: String, seq: Int): RelayReceipt?

    @Query("UPDATE relay_receipts SET claimedAt = :now, status = 'claimed', lastError = NULL WHERE cid = :cid AND seq = :seq AND status = 'claimed' AND claimedAt <= :cutoff")
    suspend fun reclaimStale(
        cid: String,
        seq: Int,
        cutoff: Long,
        now: Long = System.currentTimeMillis(),
    ): Int

    @Query("UPDATE relay_receipts SET status = :status, lastError = :error, statusSynced = 0, sentAt = CASE WHEN :status IN ('sent','delivered') THEN COALESCE(sentAt, :now) ELSE sentAt END, deliveredAt = CASE WHEN :status = 'delivered' THEN :now ELSE deliveredAt END WHERE cid = :cid AND seq = :seq")
    suspend fun markStatus(cid: String, seq: Int, status: String, error: String?, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM relay_receipts WHERE status != 'claimed' AND statusSynced = 0 ORDER BY claimedAt ASC LIMIT :limit")
    suspend fun pendingStatuses(limit: Int = 100): List<RelayReceipt>

    @Query("UPDATE relay_receipts SET statusSynced = 1 WHERE cid = :cid AND seq = :seq AND status = :status")
    suspend fun markStatusSynced(cid: String, seq: Int, status: String): Int
}

@Dao
interface RelayOutboxDao {
    @Query("SELECT * FROM relay_outbox WHERE (relayState != 'sent' OR (direction LIKE 'outgoing_%' AND carrierState = 'unknown' AND createdAt <= :unknownCutoff) OR (direction LIKE 'outgoing_%' AND carrierStatusPending = 1 AND serverSeq IS NOT NULL)) AND (direction NOT LIKE 'outgoing_%' OR carrierState != 'unknown' OR createdAt <= :unknownCutoff) ORDER BY createdAt ASC LIMIT :limit")
    suspend fun pending(unknownCutoff: Long, limit: Int = 100): List<RelayOutbox>

    @Query("SELECT * FROM relay_outbox WHERE mid = :mid LIMIT 1")
    suspend fun getByMid(mid: String): RelayOutbox?

    @Query("SELECT * FROM relay_outbox WHERE providerId = :providerId AND direction = :direction LIMIT 1")
    suspend fun getByProviderId(providerId: Long, direction: String): RelayOutbox?

    @Insert
    suspend fun insert(row: RelayOutbox): Long

    @Query("UPDATE relay_outbox SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun recordAttempt(id: Long, error: String?)

    @Query("UPDATE relay_outbox SET relayState = 'sent', lastError = NULL WHERE id = :id")
    suspend fun markSent(id: Long)

    @Query("UPDATE relay_outbox SET relayState = 'sent', serverSeq = :serverSeq, payload = '', plaintext = '', subject = NULL, attachmentsJson = NULL, lastError = NULL WHERE id = :id")
    suspend fun markRelaySent(id: Long, serverSeq: Int)

    @Query("UPDATE relay_outbox SET cid = :cid, payload = :payload, lastError = NULL WHERE id = :id")
    suspend fun markPrepared(id: Long, cid: String, payload: String)

    @Query("UPDATE relay_outbox SET cid = :newCid WHERE cid = :oldCid")
    suspend fun moveConversation(oldCid: String, newCid: String)

    @Query("UPDATE relay_outbox SET carrierState = :state, carrierStatusPending = CASE WHEN direction LIKE 'outgoing_%' THEN 1 ELSE carrierStatusPending END, lastError = :error WHERE id = :id")
    suspend fun markCarrierState(id: Long, state: String, error: String? = null)

    @Query("UPDATE relay_outbox SET carrierState = :state, carrierStatusPending = CASE WHEN direction LIKE 'outgoing_%' THEN 1 ELSE carrierStatusPending END, lastError = :error WHERE id = :id AND carrierState = 'unknown'")
    suspend fun markCarrierDispatchedIfUnknown(id: Long, state: String = "dispatched", error: String? = null): Int

    @Query("UPDATE relay_outbox SET carrierStatusPending = 0 WHERE id = :id AND carrierState = :state")
    suspend fun markCarrierStatusSynced(id: Long, state: String): Int

    @Query("DELETE FROM relay_outbox WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ProcessedMmsDao {
    @Query("SELECT EXISTS(SELECT 1 FROM processed_mms WHERE providerId = :providerId)")
    suspend fun contains(providerId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ProcessedMms)
}

@Dao
interface CarrierPartResultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(result: CarrierPartResult)

    @Query("SELECT * FROM carrier_part_results WHERE mid = :mid AND action = :action")
    suspend fun getAll(mid: String, action: String): List<CarrierPartResult>

    @Query("DELETE FROM carrier_part_results WHERE mid = :mid AND action = :action")
    suspend fun deleteAll(mid: String, action: String): Int

    @Query("DELETE FROM carrier_part_results WHERE receivedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

@Database(
    entities = [
        SmsThread::class,
        MessageRow::class,
        BlockKeyword::class,
        BlockedSms::class,
        BlockedSender::class,
        ProcessedSms::class,
        DeviceCache::class,
        RelayReceipt::class,
        RelayOutbox::class,
        ProcessedMms::class,
        CarrierPartResult::class,
        TrustedDevicePin::class,
        TrustDirectoryState::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun threadDao(): ThreadDao
    abstract fun messageDao(): MessageDao
    abstract fun blocklistDao(): BlocklistDao
    abstract fun blockedSmsDao(): BlockedSmsDao
    abstract fun blockedSenderDao(): BlockedSenderDao
    abstract fun processedSmsDao(): ProcessedSmsDao
    abstract fun deviceCacheDao(): DeviceCacheDao
    abstract fun relayReceiptDao(): RelayReceiptDao
    abstract fun relayOutboxDao(): RelayOutboxDao
    abstract fun processedMmsDao(): ProcessedMmsDao
    abstract fun carrierPartResultDao(): CarrierPartResultDao
    abstract fun deviceTrustDao(): DeviceTrustDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(ctx: android.content.Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext, AppDatabase::class.java, "securemsg.db"
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                ).build()
                    .also { INSTANCE = it }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS blocked_sms (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                        "phoneNumber TEXT NOT NULL," +
                        "body TEXT NOT NULL," +
                        "reason TEXT NOT NULL," +
                        "receivedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS blocked_senders (" +
                        "phoneNumber TEXT NOT NULL PRIMARY KEY," +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS processed_sms (" +
                        "providerId INTEGER NOT NULL PRIMARY KEY," +
                        "processedAt INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS relay_receipts (" +
                        "cid TEXT NOT NULL," +
                        "seq INTEGER NOT NULL," +
                        "claimedAt INTEGER NOT NULL," +
                        "PRIMARY KEY(cid, seq))"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN contentType TEXT NOT NULL DEFAULT 'text'")
                db.execSQL("ALTER TABLE messages ADD COLUMN subject TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentsJson TEXT")
                db.execSQL("ALTER TABLE relay_receipts ADD COLUMN status TEXT NOT NULL DEFAULT 'claimed'")
                db.execSQL("ALTER TABLE relay_receipts ADD COLUMN lastError TEXT")
                db.execSQL("ALTER TABLE relay_receipts ADD COLUMN sentAt INTEGER")
                db.execSQL("ALTER TABLE relay_receipts ADD COLUMN deliveredAt INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS relay_outbox (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                        "mid TEXT NOT NULL," +
                        "cid TEXT NOT NULL," +
                        "payload TEXT NOT NULL," +
                        "plaintext TEXT NOT NULL," +
                        "contentType TEXT NOT NULL," +
                        "subject TEXT," +
                        "attachmentsJson TEXT," +
                        "phoneNumber TEXT NOT NULL," +
                        "providerId INTEGER," +
                        "localMessageId INTEGER," +
                        "direction TEXT NOT NULL," +
                        "carrierState TEXT NOT NULL," +
                        "relayState TEXT NOT NULL," +
                        "serverSeq INTEGER," +
                        "attempts INTEGER NOT NULL," +
                        "lastError TEXT," +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_relay_outbox_mid ON relay_outbox(mid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_relay_outbox_state ON relay_outbox(relayState, createdAt)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS processed_mms (" +
                        "providerId INTEGER NOT NULL PRIMARY KEY," +
                        "processedAt INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN serverKey TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN carrierStatus TEXT NOT NULL DEFAULT 'none'")
                db.execSQL("ALTER TABLE messages ADD COLUMN carrierError TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN carrierUpdatedAt INTEGER")
                // Keep the newest local copy when older builds inserted the same
                // acknowledged relay sequence more than once.
                db.execSQL(
                    "DELETE FROM messages WHERE seq > 0 AND id NOT IN (" +
                        "SELECT MAX(id) FROM messages WHERE seq > 0 GROUP BY cid, seq)"
                )
                db.execSQL("UPDATE messages SET serverKey = cid || ':' || seq WHERE seq > 0")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_messages_serverKey " +
                        "ON messages(serverKey)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_cid_seq ON messages(cid, seq)"
                )
                db.execSQL(
                    "ALTER TABLE relay_outbox ADD COLUMN carrierStatusPending INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE relay_receipts ADD COLUMN statusSynced INTEGER NOT NULL DEFAULT 0"
                )
                // Room validates index names as well as indexed columns. Version
                // 4 used a hand-written alias that does not match the entity's
                // generated index name, so replace it during the upgrade.
                db.execSQL("DROP INDEX IF EXISTS index_relay_outbox_state")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_relay_outbox_relayState_createdAt " +
                        "ON relay_outbox(relayState, createdAt)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS carrier_part_results (" +
                        "mid TEXT NOT NULL," +
                        "action TEXT NOT NULL," +
                        "part INTEGER NOT NULL," +
                        "partCount INTEGER NOT NULL," +
                        "successful INTEGER NOT NULL," +
                        "resultCode INTEGER NOT NULL," +
                        "receivedAt INTEGER NOT NULL," +
                        "PRIMARY KEY(mid, action, part))"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Repair prerelease v5 databases that may have retained the old
                // hand-written alias before Room performed schema validation.
                db.execSQL("DROP INDEX IF EXISTS index_relay_outbox_state")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_relay_outbox_relayState_createdAt " +
                        "ON relay_outbox(relayState, createdAt)"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE sms_threads ADD COLUMN lastActivityAt " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "UPDATE sms_threads SET lastActivityAt = COALESCE(" +
                        "(SELECT MAX(createdAt) FROM messages " +
                        "WHERE messages.cid = sms_threads.cid), 0)",
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Preserve contactName as the relay-owned label. Address-book
                // synchronization starts with an independent, local-only slot.
                db.execSQL("ALTER TABLE sms_threads ADD COLUMN localContactName TEXT")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Keep device address-book values independent from the name
                // shared across the user's other authenticated devices.
                db.execSQL("ALTER TABLE sms_threads ADD COLUMN syncedContactName TEXT")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS trusted_device_pins (" +
                        "sid TEXT NOT NULL PRIMARY KEY," +
                        "accountUid INTEGER NOT NULL," +
                        "name TEXT NOT NULL," +
                        "kind TEXT NOT NULL," +
                        "pubKey TEXT NOT NULL," +
                        "sigPub TEXT NOT NULL," +
                        "fingerprint TEXT NOT NULL," +
                        "firstSeenAt INTEGER NOT NULL," +
                        "lastSeenAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_trusted_device_pins_accountUid " +
                        "ON trusted_device_pins(accountUid)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS trust_directory_state (" +
                        "accountUid INTEGER NOT NULL PRIMARY KEY," +
                        "identityKey TEXT NOT NULL," +
                        "epoch INTEGER NOT NULL," +
                        "directoryHash TEXT NOT NULL," +
                        "safetyNumber TEXT NOT NULL," +
                        "updatedAt INTEGER NOT NULL)",
                )
            }
        }
    }
}
