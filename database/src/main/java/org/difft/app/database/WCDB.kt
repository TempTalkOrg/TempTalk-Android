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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    
    @param:ApplicationContext internal val context: Context,
    @param:Named("application") private val applicationScope: CoroutineScope,
) {

    companion object {
        const val DATABASE_NAME = "tt_wcdb_database.db"

        
        
        private const val SYNCHRONOUS_CONFIG_NAME = "tt_synchronous_normal"
        private const val SYNCHRONOUS_NORMAL = 1 
    }

    







    @Volatile
    var dbCorrupted: Boolean = false
        private set

    




    fun markCorrupted() {
        dbCorrupted = true
    }

    




    @Volatile
    var keyUnavailable: Boolean = false
        private set

    
    internal fun markKeyUnavailable() {
        keyUnavailable = true
    }

    
    val isDbInaccessible: Boolean get() = dbCorrupted || keyUnavailable

    




    
    @Volatile
    internal var healthyProbePassed = false

    
    val isHealthyProbed: Boolean get() = healthyProbePassed

    private val probeFinishedState = MutableStateFlow(false)

    



    val probeFinished: StateFlow<Boolean> = probeFinishedState.asStateFlow()

    
    internal fun markProbeFinished() {
        probeFinishedState.value = true
    }

    





    
    internal val corruptionReported = AtomicBoolean(false)

    





    @Volatile
    private var cipherKeyResult: Result<ByteArray>? = null

    @VisibleForTesting 
    @Synchronized 
    internal fun resolveCipherKeyOnce(): ByteArray {
        cipherKeyResult?.let { cached ->
            return cached.getOrElse { cachedFailure ->
                
                
                throw WCDBKeyUnavailableException(
                    "WCDB cipher key unavailable (rethrow of cached failure)", cachedFailure
                )
            }
        }
        val r = runCatching { WCDBKeyManager.getOrCreateKey(context) } 
        cipherKeyResult = r
        if (r.isFailure) markKeyUnavailable()
        return r.getOrThrow() 
    }

    











    val db: Database by lazy {
        val path = context.getDatabasePath(DATABASE_NAME).absolutePath
        Database(path).apply {
            if (!BuildConfig.DEBUG) {
                setCipherKey(resolveCipherKeyOnce()) 
            }
            enableAutoBackup(true)
            
            
            
            
            
            
            setNotificationWhenCorrupted {
                reportCorruptionOnce("notification_callback")
                markCorrupted()
            }
            
            
            setConfig(
                SYNCHRONOUS_CONFIG_NAME,
                { handle -> handle.execute("PRAGMA synchronous=$SYNCHRONOUS_NORMAL") },
                Database.ConfigPriority.high,
            )
        }
    }

    




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

    
    fun testCorruptDatabase() {
        if (!BuildConfig.DEBUG) return
        try {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (!dbFile.exists()) {
                L.d { "[WCDB] Database file does not exist: ${dbFile.absolutePath}" }
                return
            }

            
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
