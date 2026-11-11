package com.difft.android.chat.attachment.migration

import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateDefaults
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.attachment.AttachmentPathResolver
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.difft.app.database.LegacyAttachmentAddresses
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.wcdb
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton

















@Singleton
class ForwardAttachmentMigration @Inject constructor(
    @param:AppStateDataStore
    private val appState: DataStore<Preferences>
) : AttachmentPathResolver.Migrator {

    private val state = AttachmentMigrationState(appState)

    



    private val rowFileMigrator = object : RowFileMigrator {
        override fun <T> withRowLock(localId: String, block: () -> T): T =
            this@ForwardAttachmentMigration.withRowLock(localId, block)

        override fun materialize(
            localId: String,
            legacyKeys: List<String>,
            fileName: String,
            targetDirectory: String,
            movableSource: File?
        ): LegacyAttachmentFiles.CopyOutcome =
            migrateOne(localId, legacyKeys, fileName, targetDirectory, movableSource)

        override fun legacyKeysFor(row: AttachmentModel): List<String> =
            this@ForwardAttachmentMigration.legacyKeysFor(row)

        override fun repairStatus(row: AttachmentModel, targetBasePath: File) =
            this@ForwardAttachmentMigration.repairStatus(row, targetBasePath)
    }

    
    private val forwardStages by lazy { ForwardAttachmentStages(state, rowFileMigrator) }
    private val normalStages by lazy { NormalAttachmentStages(state, rowFileMigrator) }

    




    @Volatile
    var backgroundPassEnabled: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val lazyMigrations = AtomicInteger(0)

    




    @Volatile
    private var completedVersion: Int = AppStateDefaults.ATTACHMENT_MIGRATION_VERSION

    
    @Volatile
    private var stage: Int = AppStateDefaults.ATTACHMENT_MIGRATION_STAGE

    








    private val stages: List<Stage> by lazy {
        listOf(
            Stage(STAGE_BACKFILLED, introducedInVersion = 1) { forwardStages.backfillLocalIds() },
            Stage(STAGE_FILES_MIGRATED, introducedInVersion = 1) { forwardStages.migrateFiles() },
            Stage(STAGE_SWEPT, introducedInVersion = 1) { forwardStages.sweepLegacyDirectories() },
            Stage(STAGE_NORMAL_MIGRATED, introducedInVersion = 2) { normalStages.migrateNormalFiles() },
            Stage(STAGE_FINAL_CLEANUP, introducedInVersion = 2) { normalStages.finalCleanup() }
        )
    }

    







    private val rowLocks = ConcurrentHashMap<String, ReentrantLock>()

    
    @VisibleForTesting
    internal fun activeRowLocks(): Int = rowLocks.size

    



    fun start() {
        if (!started.compareAndSet(false, true)) return
        AttachmentPathResolver.migrator = this
        scope.launch {
            
            
            
            
            
            
            val stateReadable = runCatching { readState() }.onFailure {
                L.w { "[FwdAttachMigration] state unreadable, skipping background pass: ${it.stackTraceToString()}" }
            }.isSuccess
            
            delay(START_DELAY_MS)
            runCatching {
                
                
                
                val strays = LegacyAttachmentFiles.sweepStrayTempFiles(File(FileUtil.getFilePath(FileUtil.FILE_DIR_ATTACHMENT)))
                if (strays > 0) L.i { "[FwdAttachMigration] cleared strayTempFiles=$strays" }
                if (!stateReadable || isComplete()) return@runCatching
                if (!backgroundPassEnabled) {
                    L.i { "[FwdAttachMigration] background pass disabled, version=$completedVersion stage=$stage" }
                    return@runCatching
                }
                runStages()
            }.onFailure {
                
                L.w { "[FwdAttachMigration] run failed at stage=$stage: ${it.stackTraceToString()}" }
            }
        }
    }

    




    fun cancel() {
        scope.cancel()
    }

    




    override fun migrateIfNeeded(
        attachment: Attachment,
        targetDirectory: String,
        legacyOwnerMessageId: String?
    ): Boolean {
        val fileName = attachment.fileName
        if (fileName.isNullOrEmpty()) return false
        val legacyKeys = legacyKeysFor(
            isForwardCopy = attachment.isForwardCopy,
            authorityId = attachment.authorityId,
            ownerMessageId = legacyOwnerMessageId
        )
        if (legacyKeys.isEmpty()) return false
        val outcome = migrateOne(
            localId = attachment.localId,
            legacyKeys = legacyKeys,
            fileName = fileName,
            targetDirectory = targetDirectory
        )
        return outcome == LegacyAttachmentFiles.CopyOutcome.COPIED ||
            outcome == LegacyAttachmentFiles.CopyOutcome.ALREADY_PRESENT
    }

    




















    fun materializeFromLegacyAddress(row: AttachmentModel, rowKey: String, targetFilePath: String): Boolean {
        val fileName = row.fileName
        if (rowKey.isEmpty() || fileName.isNullOrEmpty()) return false

        val targetDirectory = targetFilePath.substringBeforeLast(File.separatorChar, "")
        if (targetDirectory.isEmpty()) return false

        val outcome = migrateOne(rowKey, legacyKeysFor(row), fileName, targetDirectory)
        if (outcome != LegacyAttachmentFiles.CopyOutcome.COPIED) return false
        if (!LegacyAttachmentFiles.isVerified(File(targetDirectory, fileName), row.size)) return false

        val count = lazyMigrations.incrementAndGet()
        if (count == 1 || count % LAZY_LOG_INTERVAL == 0) {
            L.i { "[FwdAttachMigration] lazy migrations=$count" }
        }
        return true
    }

    
    private fun legacyKeysFor(row: AttachmentModel): List<String> = legacyKeysFor(
        isForwardCopy = row.forwardModelDatabaseId != null,
        authorityId = row.authorityId ?: 0L,
        ownerMessageId = row.messageId
    )

    










    private fun legacyKeysFor(
        isForwardCopy: Boolean,
        authorityId: Long,
        ownerMessageId: String?
    ): List<String> = listOfNotNull(
        authorityId.takeIf { isForwardCopy && it != 0L }?.toString(),
        ownerMessageId?.takeIf { it.isNotEmpty() }
    )

    












    fun <T> withAttachmentRowLock(rowKey: String?, block: () -> T): T {
        if (isComplete() || rowKey.isNullOrEmpty()) return block()
        return withRowLock(rowKey, block)
    }

    private fun migrateOne(
        localId: String,
        legacyKeys: List<String>,
        fileName: String,
        targetDirectory: String,
        movableSource: File? = null
    ): LegacyAttachmentFiles.CopyOutcome = withRowLock(localId) {
        val legacyDirectories = legacyKeys
            .filter { it != localId }
            .map { File(FileUtil.getMessageAttachmentFilePath(it)) }
        LegacyAttachmentFiles.materialize(legacyDirectories, File(targetDirectory), fileName, movableSource)
    }

    
















    private fun <T> withRowLock(localId: String, block: () -> T): T {
        while (true) {
            val lock = rowLocks.computeIfAbsent(localId) { ReentrantLock() }
            lock.lock()
            if (rowLocks[localId] !== lock) {
                
                lock.unlock()
                continue
            }
            return try {
                block()
            } finally {
                if (lock.holdCount == 1 && !lock.hasQueuedThreads()) rowLocks.remove(localId, lock)
                lock.unlock()
            }
        }
    }

    

    private suspend fun runStages() {
        
        
        
        
        
        stage = maxOf(stage, startingStageFor(completedVersion, stages))
        for (next in stages) {
            if (stage >= next.ordinal) continue
            
            
            if (!next.run()) return
            setStage(next.ordinal)
        }
        stampVersion()
    }

    internal class Stage(
        val ordinal: Int,
        val introducedInVersion: Int,
        val run: suspend () -> Boolean
    )

    
    private fun repairStatus(row: AttachmentModel, targetBasePath: File) {
        if (row.status != AttachmentStatus.LOADING.code) return
        if (!LegacyAttachmentFiles.isVerified(targetBasePath, row.size)) return
        wcdb.attachment.updateValue(
            AttachmentStatus.SUCCESS.code,
            DBAttachmentModel.status,
            DBAttachmentModel.localId.eq(row.localId)
        )
    }

    

    

    
    private fun isComplete(): Boolean = completedVersion >= TARGET_VERSION

    @VisibleForTesting
    internal suspend fun readState() {
        completedVersion = state.completedVersion()
        stage = state.stage()
        publishLegacyAddressWindow()
    }

    private suspend fun setStage(next: Int) {
        stage = state.advanceStage(next)
    }

    @VisibleForTesting
    internal suspend fun stampVersion() {
        state.stampVersion(TARGET_VERSION)
        completedVersion = TARGET_VERSION
        publishLegacyAddressWindow()
        L.i { "[FwdAttachMigration] version=$TARGET_VERSION completed, lazyMigrations=${lazyMigrations.get()}" }
    }

    









    private fun publishLegacyAddressWindow() {
        LegacyAttachmentAddresses.isWindowOpen = !isComplete()
    }

    

    companion object {
        




        const val TARGET_VERSION = 2

        const val STAGE_BACKFILLED = 1
        const val STAGE_FILES_MIGRATED = 2
        const val STAGE_SWEPT = 3
        const val STAGE_NORMAL_MIGRATED = 4
        const val STAGE_FINAL_CLEANUP = 5

        











        @VisibleForTesting
        internal fun startingStageFor(completedVersion: Int, stages: List<Stage>): Int {
            var seeded = AppStateDefaults.ATTACHMENT_MIGRATION_STAGE
            for (stage in stages) {
                if (stage.introducedInVersion > completedVersion) break
                seeded = stage.ordinal
            }
            return seeded
        }

        private const val START_DELAY_MS = 10_000L
        private const val LAZY_LOG_INTERVAL = 25

        
        internal const val BATCH_SIZE = 100L

        



        internal val MIGRATION_FIELDS by lazy {
            arrayOf(
                DBAttachmentModel.databaseId,
                DBAttachmentModel.localId,
                DBAttachmentModel.messageId,
                DBAttachmentModel.authorityId,
                DBAttachmentModel.forwardModelDatabaseId,
                DBAttachmentModel.fileName,
                DBAttachmentModel.size,
                DBAttachmentModel.status
            )
        }

        
        internal val SWEEP_FIELDS by lazy {
            arrayOf(
                DBAttachmentModel.localId,
                DBAttachmentModel.fileName,
                DBAttachmentModel.size
            )
        }
    }
}
