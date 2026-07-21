package org.difft.app.database

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.WCDBKeyUnavailableException
import com.difft.android.base.log.lumberjack.L
import com.difft.android.database.BuildConfig
import com.tencent.wcdb.core.Database
import com.tencent.wcdb.core.Table
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.DBDraftModel
import org.difft.app.database.models.DBFailedMessageModel
import org.difft.app.database.models.DBFavoriteGifModel
import org.difft.app.database.models.DBForwardContextModel
import org.difft.app.database.models.DBGroupCryptoKeysModel
import org.difft.app.database.models.DBForwardModel
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.DBJobConstraintModel
import org.difft.app.database.models.DBJobSpecModel
import org.difft.app.database.models.DBMentionModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBNotificationCacheModel
import org.difft.app.database.models.DBPendingMessageModelNew
import org.difft.app.database.models.DBPendingRemovalContactModel
import org.difft.app.database.models.DBPublicKeyInfoModel
import org.difft.app.database.models.DBQuoteModel
import org.difft.app.database.models.DBReactionModel
import org.difft.app.database.models.DBReadInfoModel
import org.difft.app.database.models.DBResetIdentityKeyModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.DBSharedContactModel
import org.difft.app.database.models.DBSharedContactPhoneModel
import org.difft.app.database.models.DBSpeechToTextModel
import org.difft.app.database.models.DBTranslateModel
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class WCDB @Inject constructor(
    // internal (was private): the probe/telemetry extensions in WCDBHealthProbe.kt
    // (same module :database) read `context` via the WCDB receiver.
    @param:ApplicationContext internal val context: Context,
    @param:Named("application") private val applicationScope: CoroutineScope,
) {

    companion object {
        const val DATABASE_NAME = "tt_wcdb_database.db"

        // #971 write-throughput main fix. WCDB's encrypted+WAL pool reports synchronous=NORMAL
        // via PRAGMA query but its C++ layer actually fsyncs every commit (FULL-like, ~90 msgs/s
        // measured). Explicitly executing `PRAGMA synchronous=1` on every handle overrides that
        // layer so fsync is deferred to WAL checkpoint — on-device benchmark: 7.3x on the real
        // production init path (autoBackup + corruption callback). See wcdb-benchmark-results.md.
        private const val SYNCHRONOUS_CONFIG_NAME = "tt_synchronous_normal"
        private const val SYNCHRONOUS_NORMAL = 1 // SQLite: 0=OFF 1=NORMAL 2=FULL 3=EXTRA
    }

    /**
     * Process-level corruption flag. Set by [probeHealthy] when the DB is unreadable,
     * or by [markCorrupted] just before MainActivity's recovery closes the handle.
     *
     * DB-touching early/headless consumers should fast-skip their read when this is
     * `true` to avoid racing MainActivity's recovery (`retrieve`/`close`/delete). The
     * flag resets implicitly on the next process start after recovery's `restartApp()`.
     */
    @Volatile
    var dbCorrupted: Boolean = false
        private set

    /**
     * Explicit setter for the pre-close path in MainActivity.resetDatabaseAndResync():
     * flip the flag BEFORE `db.close()` so any straggler background consumer fast-skips
     * a closed handle (RACE-2). [probeHealthy] also sets it internally on detection.
     */
    fun markCorrupted() {
        dbCorrupted = true
    }

    /**
     * Fail-soft cipher-key-unavailable flag, distinct from file corruption ([dbCorrupted]). The
     * wipe trigger reads [dbCorrupted] only — never this flag, never [isDbInaccessible] — so a
     * key failure never wipes. One-way / process-lifetime; resets on the next cold start.
     */
    @Volatile
    var keyUnavailable: Boolean = false
        private set

    /** Monotonic set-true helper for [keyUnavailable]; also called by the probes in WCDBHealthProbe.kt. */
    internal fun markKeyUnavailable() {
        keyUnavailable = true
    }

    /** Fast-skip predicate for early/headless DB consumers. The wipe trigger must not read this — it reads [dbCorrupted] only. */
    val isDbInaccessible: Boolean get() = dbCorrupted || keyUnavailable

    /**
     * Telemetry: `true` once [probeHealthy] has confirmed the DB readable this process.
     * Lets [reportCorruptionOnce] classify a later corruption as genuinely *mid-session*
     * (corruption appeared AFTER a healthy probe) vs *startup_open* (corrupt at first open).
     */
    // internal (was private): read/written by probeHealthy/reportCorruptionOnce in WCDBHealthProbe.kt.
    @Volatile
    internal var healthyProbePassed = false

    /**
     * One-shot guard — the WCDB corruption notification can fire repeatedly AND
     * [reportCorruptionOnce] is called from two threads (IO probe + WCDB notification
     * callback). AtomicBoolean.compareAndSet makes the check-then-set atomic so only one
     * Crashlytics non-fatal is emitted per process.
     */
    // internal (was private): read by reportCorruptionOnce in WCDBHealthProbe.kt.
    internal val corruptionReported = AtomicBoolean(false)

    /**
     * Process-lifetime one-shot cache of the cipher-key resolution outcome (success →
     * cached key, failure → cached exception). `by lazy` does NOT cache a *failed*
     * initializer, so without this every re-touch of [db] after a key failure would
     * re-hit the Keystore — a retry storm on a permanently-dead Keystore (ARCH-CRIT-1).
     */
    @Volatile
    private var cipherKeyResult: Result<ByteArray>? = null

    @VisibleForTesting // was private — internal so the cache+cause contract is unit-testable
    @Synchronized // cheap defensive guard against any future second entry point bypassing the db-lazy lock
    internal fun resolveCipherKeyOnce(): ByteArray {
        cipherKeyResult?.let { cached ->
            return cached.getOrElse { cachedFailure ->
                // Cache holds the original failure Result; rethrow a new exception here so the
                // stack captures this call site.
                throw WCDBKeyUnavailableException(
                    "WCDB cipher key unavailable (rethrow of cached failure)", cachedFailure
                )
            }
        }
        val r = runCatching { WCDBKeyManager.getOrCreateKey(context) } // hits Keystore at most ONCE per process
        cipherKeyResult = r
        if (r.isFailure) markKeyUnavailable()
        return r.getOrThrow() // first-time throw already has a live stack
    }

    /**
     * The single DB handle. Open-only responsibility: construct + cipher + autoBackup.
     *
     * The first real open is heavy (cipher/PBKDF + header I/O) — first touch must be
     * OFF the main thread; it is owned by MainActivity.checkDatabaseIntegrity() on IO.
     * Any startup/headless DB touch launched onto a no-CEH scope must `runCatching`
     * the access and check [dbCorrupted] first (see ContactRemarkCache.preload).
     *
     * No PRAGMA probe, no corruption callback, no restart here — corruption is detected
     * later by [probeHealthy] (called by the MainActivity gate and the early
     * "probe db health" startup task).
     */
    val db: Database by lazy {
        val path = context.getDatabasePath(DATABASE_NAME).absolutePath
        Database(path).apply {
            if (!BuildConfig.DEBUG) {
                setCipherKey(resolveCipherKeyOnce()) // may throw cached WCDBKeyUnavailableException
            }
            enableAutoBackup(true)
            // Runtime corruption detection (e.g. a write from a headless FCM/WS wake
            // corrupts the DB after the MainActivity probe already passed). WCDB invokes
            // this asynchronously on its own thread AFTER confirming corruption via
            // PRAGMA integrity_check. We ONLY flip the @Volatile flag — no restart, no
            // persisted flag, no DB touch. Consumers fast-skip; the next foreground
            // MainActivity launch drives the real recovery via probeHealthy().
            setNotificationWhenCorrupted {
                reportCorruptionOnce("notification_callback")
                markCorrupted()
            }
            // #971 write-throughput fix: force synchronous=NORMAL. setConfig runs per-handle so it
            // reaches the write handle (a one-shot execute might hit a read one); high priority runs
            // after the cipher config. WAL+NORMAL never corrupts the DB but may drop writes committed
            // after the last checkpoint on power loss (IM messages are resendable) — team sign-off, see PR.
            setConfig(
                SYNCHRONOUS_CONFIG_NAME,
                { handle -> handle.execute("PRAGMA synchronous=$SYNCHRONOUS_NORMAL") },
                Database.ConfigPriority.high,
            )
        }
    }

    /**
     * Diagnostic only: log-confirms `synchronous=NORMAL` reached a write handle. WCDB's PRAGMA query
     * can report NORMAL while writes still fsync like FULL, so we read it back through `getHandle(true)`.
     * Mismatch is a soft `L.w` (perf-only, no Crashlytics). Once per process; call off the main thread.
     */
    private val synchronousVerifyAttempted = AtomicBoolean(false)

    fun verifySynchronousApplied() {
        if (dbCorrupted || !synchronousVerifyAttempted.compareAndSet(false, true)) return
        runCatching {
            val onWriteHandle = db.getHandle(true).use { it.getValueFromSQL("PRAGMA synchronous")?.int }
            if (onWriteHandle == SYNCHRONOUS_NORMAL) {
                L.i { "[WCDB] synchronous=NORMAL confirmed on write handle (value=$onWriteHandle)" }
            } else {
                L.w { "[WCDB] synchronous NOT NORMAL on write handle (value=$onWriteHandle), perf fix may be inert" }
            }
        }.onFailure { L.w { "[WCDB] synchronous verify failed: ${it.stackTraceToString()}" } }
    }

    val attachment by lazy {
        db.createTable("attachment", DBAttachmentModel)
        db.getTable("attachment", DBAttachmentModel)
    }
    val contactor by lazy {
        db.createTable("contactor", DBContactorModel)
        db.getTable("contactor", DBContactorModel)
    }

    val forwardContext by lazy {
        db.createTable("forward_context", DBForwardContextModel)
        db.getTable("forward_context", DBForwardContextModel)
    }

    val forward by lazy {
        db.createTable("forward", DBForwardModel)
        db.getTable("forward", DBForwardModel)
    }

    val groupMemberContactor by lazy {
        db.createTable("group_member_contactor", DBGroupMemberContactorModel)
        db.getTable("group_member_contactor", DBGroupMemberContactorModel)
    }

    val group by lazy {
        db.createTable("groups", DBGroupModel)
        db.getTable("groups", DBGroupModel)
    }

    val mention by lazy {
        db.createTable("mention", DBMentionModel)
        db.getTable("mention", DBMentionModel)
    }

    val message by lazy {
        db.createTable("message", DBMessageModel)
        db.getTable("message", DBMessageModel)
    }

    val pendingMessageNew by lazy {
        db.createTable("pending_message_new", DBPendingMessageModelNew)
        db.getTable("pending_message_new", DBPendingMessageModelNew)
    }

    val quote by lazy {
        db.createTable("quote", DBQuoteModel)
        db.getTable("quote", DBQuoteModel)
    }

    val reaction by lazy {
        db.createTable("reaction", DBReactionModel)
        db.getTable("reaction", DBReactionModel)
    }

    val room by lazy {
        db.createTable("room", DBRoomModel)
        db.getTable("room", DBRoomModel)
    }

    val sharedContact by lazy {
        db.createTable("shared_contact", DBSharedContactModel)
        db.getTable("shared_contact", DBSharedContactModel)
    }

    val sharedContactPhone by lazy {
        db.createTable("shared_contact_phone", DBSharedContactPhoneModel)
        db.getTable("shared_contact_phone", DBSharedContactPhoneModel)
    }

    val translate by lazy {
        db.createTable("translate", DBTranslateModel)
        db.getTable("translate", DBTranslateModel)
    }

    val speechToText by lazy {
        db.createTable("speech_to_Text", DBSpeechToTextModel)
        db.getTable("speech_to_Text", DBSpeechToTextModel)
    }

    val draft by lazy {
        db.createTable("draft", DBDraftModel)
        db.getTable("draft", DBDraftModel)
    }

    val failedMessage by lazy {
        db.createTable("failed_message", DBFailedMessageModel)
        db.getTable("failed_message", DBFailedMessageModel)
    }

    val readInfo by lazy {
        db.createTable("read_info", DBReadInfoModel)
        db.getTable("read_info", DBReadInfoModel)
    }

    val resetIdentityKey by lazy {
        db.createTable("reset_identity_key", DBResetIdentityKeyModel)
        db.getTable("reset_identity_key", DBResetIdentityKeyModel)
    }

    val notificationCache by lazy {
        db.createTable("notification_cache", DBNotificationCacheModel)
        db.getTable("notification_cache", DBNotificationCacheModel)
    }

    val groupCryptoKeys by lazy {
        db.createTable("group_crypto_keys", DBGroupCryptoKeysModel)
        db.getTable("group_crypto_keys", DBGroupCryptoKeysModel)
    }

    val publicKeyInfo by lazy {
        db.createTable("public_key_info", DBPublicKeyInfoModel)
        db.getTable("public_key_info", DBPublicKeyInfoModel)
    }

    val jobSpec by lazy {
        db.createTable("job_spec", DBJobSpecModel)
        db.getTable("job_spec", DBJobSpecModel)
    }

    val jobConstraint by lazy {
        db.createTable("job_constraint", DBJobConstraintModel)
        db.getTable("job_constraint", DBJobConstraintModel)
    }

    val pendingRemovalContact by lazy {
        db.createTable("pending_removal_contact", DBPendingRemovalContactModel)
        db.getTable("pending_removal_contact", DBPendingRemovalContactModel)
    }

    val favoriteGifs by lazy {
        db.createTable("favorite_gifs", DBFavoriteGifModel)
        db.getTable("favorite_gifs", DBFavoriteGifModel)
    }

    // Map from lowercase tableName to the actual table
    val tablesMap by lazy {
        listOf(
            room,
            message,
            draft,
            contactor,
            groupMemberContactor,
            group,
            attachment,
            forwardContext,
            forward,
            mention,
            pendingMessageNew,
            quote,
            reaction,
            sharedContact,
            sharedContactPhone,
            translate,
            failedMessage,
            readInfo,
            resetIdentityKey,
            notificationCache,
            groupCryptoKeys,
            publicKeyInfo,
            jobSpec,
            jobConstraint,
            pendingRemovalContact,
            favoriteGifs
        ).associateBy { it.tableName.lowercase() }
    }
    fun deleteDatabaseFile() {
        try {
            context.deleteDatabase(DATABASE_NAME)
        } catch (e: Exception) {
            val exception = RuntimeException("[WCDB] Failed to delete database file", e)
            L.e { "[WCDB] Failed to delete database file,e:${exception.stackTraceToString()}" }
        }
    }

    /**
     * 模拟数据库损坏: 直接破坏数据库文件头部
     *
     * Debug-only: hard floor `if (!BuildConfig.DEBUG) return` so this destructive
     * hook can never run in a release artifact even if its entry point ships.
     */
    fun testCorruptDatabase() {
        if (!BuildConfig.DEBUG) return
        try {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (!dbFile.exists()) {
                L.d { "[WCDB] Database file does not exist: ${dbFile.absolutePath}" }
                return
            }

            // 破坏数据库文件的前16字节（包含SQLite魔数）
            RandomAccessFile(dbFile, "rw").use { raf ->
                raf.seek(0)
                val randomBytes = ByteArray(16)
                SecureRandom().nextBytes(randomBytes)
                raf.write(randomBytes)
            }

            L.d { "[WCDB] Successfully corrupted database header" }

        } catch (e: Exception) {
            L.d { "[WCDB] Failed to corrupt database header:${e.stackTraceToString()}" }
        }
    }


    fun testBackupManually() {
        if (!BuildConfig.DEBUG) return
        db.backup()
    }
}
