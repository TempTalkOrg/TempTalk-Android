package org.difft.app.database

import android.content.Context
import android.content.Intent
import android.os.Process
import com.difft.android.base.log.lumberjack.L
import com.difft.android.database.BuildConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tencent.wcdb.base.WCDBCorruptOrIOException
import com.tencent.wcdb.core.Database
import com.tencent.wcdb.core.Table
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.models.DBCardModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.DBDraftModel
import org.difft.app.database.models.DBFailedMessageModel
import org.difft.app.database.models.DBForwardContextModel
import org.difft.app.database.models.DBForwardModel
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.DBMentionModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBPendingMessageModelNew
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
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import com.tencent.wcdb.base.WCDBException
import kotlin.system.exitProcess

@Singleton
class WCDB @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("application") private val applicationScope: CoroutineScope,
    private val recoveryPreferences: DatabaseRecoveryPreferences
) {

    companion object {
        const val DATABASE_NAME = "tt_wcdb_database.db"
    }

    // Instead of lambdas, define one callback object that implements both SQL and Performance tracing.
    private val wcdbTraceCallback = object : Database.SQLTracer, Database.PerformanceTracer {

        override fun onTrace(tag: Long, path: String, handleId: Long, sql: String, info: String) {
            sqlPreExecutionEvents.tryEmit(SQLPreExecutionEvent(sql, info))
        }

        override fun onTrace(
            tag: Long,
            path: String,
            handleId: Long,
            sql: String,
            info: Database.PerformanceInfo
        ) {
            // Emit the "post-execution" event with the performance info
            applicationScope.launch(Dispatchers.IO) {
                sqlPostExecutionEvents.emit(
                    SQLPostExecutionEvent(sql, info)
                )
            }
            //here there is a caution, that if the sql is execute in a transaction, this sql execute event will be emitted before the transaction is finished
            //so if you read the database in the same time or nearest time, you may not get the latest data
        }
    }

    // We now have TWO flows:
    // 1) One for pre-execution logging
    // 2) One for post-execution table-update notifications
    val sqlPreExecutionEvents = MutableSharedFlow<SQLPreExecutionEvent>(extraBufferCapacity = 100, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val sqlPostExecutionEvents = MutableSharedFlow<SQLPostExecutionEvent>(extraBufferCapacity = 30)

    // Your data classes for trace events:
    data class SQLPreExecutionEvent(val sql: String, val info: String)  // from SQLTracer
    data class SQLPostExecutionEvent(val sql: String, val performanceInfo: Database.PerformanceInfo) // from PerformanceTracer

    val db: Database by lazy {
        val path = context.getDatabasePath(DATABASE_NAME).absolutePath

        fun createDatabase(): Database {
            return Database(path).also { database ->
                if (!BuildConfig.DEBUG) {
                    database.setCipherKey(WCDBSecretKeyHelper.getOrCreateDBSecretKey(context))
                }
                database.setFullSQLTraceEnable(BuildConfig.DEBUG)

                // Register the SAME object for both callbacks:
                if (BuildConfig.DEBUG) {
                    // 1) Trace callback called BEFORE or DURING SQL execution
                    database.traceSQL(wcdbTraceCallback)
                }
                // 2) Performance callback called AFTER SQL executes
                database.tracePerformance(wcdbTraceCallback)
                database.enableAutoBackup(true)

                database.setNotificationWhenCorrupted { db ->
                    if (db.checkIfIsAlreadyCorrupted()) {
                        val exception = RuntimeException("[WCDB] Database corruption detected in notification callback. Path: $path")
                        L.e { "[WCDB] Database corruption detected in notification callback. Path: $path e:${exception.stackTraceToString()}" }
                        FirebaseCrashlytics.getInstance().recordException(exception)

                        // 直接记录恢复标记并重启应用
                        recoveryPreferences.setRecoveryNeeded()
                        restartApp()
                    }
                }

                // Force a trivial SQL so the handle really opens and we can detect corruption immediately
                // "PRAGMA journal_mode" is lightweight and will throw Code 26 if header/cipher mismatch.
                database.execute("PRAGMA journal_mode")
            }
        }

        try {
            createDatabase()
        } catch (e: WCDBCorruptOrIOException) {
            // Report the corruption to FirebaseCrashlytics
            val exception = RuntimeException("[WCDB] Database corruption detected. Code: ${e.code}, Path: $path", e)
            L.e { "[WCDB] Database corruption detected. Code: ${e.code}, Path: $path e:${exception.stackTraceToString()}" }
            FirebaseCrashlytics.getInstance().recordException(exception)

            if (e.code == WCDBException.Code.Corrupt || e.code == WCDBException.Code.NotADatabase) {
                recoveryPreferences.setRecoveryNeeded()
                restartApp()
            } else {
                throw e
            }
        }
    }

    val attachment by lazy {
        db.createTable("attachment", DBAttachmentModel.INSTANCE)
        db.getTable("attachment", DBAttachmentModel.INSTANCE)
    }
    val card by lazy {
        db.createTable("card", DBCardModel.INSTANCE)
        db.getTable("card", DBCardModel.INSTANCE)
    }
    val contactor by lazy {
        db.createTable("contactor", DBContactorModel.INSTANCE)
        db.getTable("contactor", DBContactorModel.INSTANCE)
    }

    val forwardContext by lazy {
        db.createTable("forward_context", DBForwardContextModel.INSTANCE)
        db.getTable("forward_context", DBForwardContextModel.INSTANCE)
    }

    val forward by lazy {
        db.createTable("forward", DBForwardModel.INSTANCE)
        db.getTable("forward", DBForwardModel.INSTANCE)
    }

    val groupMemberContactor by lazy {
        db.createTable("group_member_contactor", DBGroupMemberContactorModel.INSTANCE)
        db.getTable("group_member_contactor", DBGroupMemberContactorModel.INSTANCE)
    }

    val group by lazy {
        db.createTable("groups", DBGroupModel.INSTANCE)
        db.getTable("groups", DBGroupModel.INSTANCE)
    }

    val mention by lazy {
        db.createTable("mention", DBMentionModel.INSTANCE)
        db.getTable("mention", DBMentionModel.INSTANCE)
    }

    val message by lazy {
        db.createTable("message", DBMessageModel.INSTANCE)
        db.getTable("message", DBMessageModel.INSTANCE)
    }

    val pendingMessageNew by lazy {
        db.createTable("pending_message_new", DBPendingMessageModelNew.INSTANCE)
        db.getTable("pending_message_new", DBPendingMessageModelNew.INSTANCE)
    }

    val quote by lazy {
        db.createTable("quote", DBQuoteModel.INSTANCE)
        db.getTable("quote", DBQuoteModel.INSTANCE)
    }

    val reaction by lazy {
        db.createTable("reaction", DBReactionModel.INSTANCE)
        db.getTable("reaction", DBReactionModel.INSTANCE)
    }

    val room by lazy {
        db.createTable("room", DBRoomModel.INSTANCE)
        db.getTable("room", DBRoomModel.INSTANCE)
    }

    val sharedContact by lazy {
        db.createTable("shared_contact", DBSharedContactModel.INSTANCE)
        db.getTable("shared_contact", DBSharedContactModel.INSTANCE)
    }

    val sharedContactPhone by lazy {
        db.createTable("shared_contact_phone", DBSharedContactPhoneModel.INSTANCE)
        db.getTable("shared_contact_phone", DBSharedContactPhoneModel.INSTANCE)
    }

    val translate by lazy {
        db.createTable("translate", DBTranslateModel.INSTANCE)
        db.getTable("translate", DBTranslateModel.INSTANCE)
    }

    val speechToText by lazy {
        db.createTable("speech_to_Text", DBSpeechToTextModel.INSTANCE)
        db.getTable("speech_to_Text", DBSpeechToTextModel.INSTANCE)
    }

    val draft by lazy {
        db.createTable("draft", DBDraftModel.INSTANCE)
        db.getTable("draft", DBDraftModel.INSTANCE)
    }

    val failedMessage by lazy {
        db.createTable("failed_message", DBFailedMessageModel.INSTANCE)
        db.getTable("failed_message", DBFailedMessageModel.INSTANCE)
    }

    val readInfo by lazy {
        db.createTable("read_info", DBReadInfoModel.INSTANCE)
        db.getTable("read_info", DBReadInfoModel.INSTANCE)
    }

    val resetIdentityKey by lazy {
        db.createTable("reset_identity_key", DBResetIdentityKeyModel.INSTANCE)
        db.getTable("reset_identity_key", DBResetIdentityKeyModel.INSTANCE)
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
            card,
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
            resetIdentityKey
        ).associateBy { it.tableName.lowercase() }
    }

    fun extractTableNameFromSQL(sql: String): String? {
        // Regex to match INSERT INTO, UPDATE, or DELETE FROM statements and capture the table name.
        val regex = Regex("(?:INSERT INTO|UPDATE|DELETE FROM|INSERT OR REPLACE INTO)\\s+`?(\\w+)`?", RegexOption.IGNORE_CASE)
        return regex.find(sql)?.groupValues?.get(1)
    }

    fun deleteDatabaseFile() {
        try {
            context.deleteDatabase(DATABASE_NAME)
        } catch (e: Exception) {
            val exception = RuntimeException("[WCDB] Failed to delete database file", e)
            L.e { "[WCDB] Failed to delete database file,e:${exception.stackTraceToString()}" }
            FirebaseCrashlytics.getInstance().recordException(exception)
        }
    }

    /**
     * 模拟数据库损坏: 直接破坏数据库文件头部
     */
    fun testCorruptDatabase() {
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
            FirebaseCrashlytics.getInstance().log("Database header corrupted for testing")

        } catch (e: Exception) {
            L.d { "[WCDB] Failed to corrupt database header:${e.stackTraceToString()}" }
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }


    fun testBackupManually() {
        db.backup()
    }

    fun testRecoveryEvent() {
        recoveryPreferences.setRecoveryNeeded()
        restartApp()
    }

    /**
     * 重启应用
     */
    private fun restartApp(): Nothing {
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(this)
        }
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}

private val cacheObservableTables = mutableMapOf<String, ObservableTable<*>>()

class ObservableTable<T> internal constructor(val table: Table<T>) {
    val updateEvents = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    ).also { it.tryEmit(Unit) }

    fun notifyUpdate() {
        updateEvents.tryEmit(Unit)
    }
}

fun <T, R> observeTable(
    tableProvider: () -> Table<T>,
    queryAction: suspend Table<T>.() -> R
): Flow<R> = flow {
    val table = withContext(Dispatchers.IO) { tableProvider() }
    emitAll(table.observeRealtime(queryAction))
}

private fun <T> Table<T>.asObservable() = cacheObservableTables.getOrPut(tableName) { ObservableTable(this) }

fun <T> Table<T>.notifyUpdate() = asObservable().notifyUpdate()

fun <T> Table<T>.observeRealtime(): Flow<Unit> = this.asObservable().updateEvents

fun <T, R> Table<T>.observeRealtime(
    queryAction: suspend Table<T>.() -> R
) = this.observeRealtime()
    .map { queryAction() }
    .flowOn(Dispatchers.IO)
