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

/**
 * The ONE component that knows where attachments used to live.
 *
 * Before per-copy addressing an attachment's file was addressed by something OTHER than the file: a
 * forwarded copy by the `authorityId` it shared with every other copy of the same remote file, a
 * normal message's attachment by the message that owned it. Files now live at
 * `attachment/<localId>/`, one directory per copy, and this migration brings the existing ones
 * across — version 1 the forwarded copies ([ForwardAttachmentStages]), version 2 the normal ones and
 * the final cleanup ([NormalAttachmentStages]).
 *
 * What lives HERE is what both waves share: the version/stage state machine, the per-row lock, the
 * legacy-address knowledge (which directories a row's file may still sit in), the copy primitive and
 * the status repair. The stages themselves are the work of a wave and live beside it.
 *
 * Copies when a legacy directory may be shared, moves only where sole ownership is PROVEN — O(1)
 * with no byte copy, and the common case. What licenses that proof is that the reference set of
 * legacy directories was frozen at the addressing flip: no new row ever points at one. The sweep is
 * what eventually reclaims the shared directories, and it refuses to delete anything it cannot
 * verify — a directory it does not fully understand is simply kept for the next run.
 *
 * Progress is a **versioned marker**, like `LegacyPlaintextAttachmentMigration`'s version stamp and
 * the `syncedContactsVn` contact refresh: [stages] lists what [TARGET_VERSION] requires, and the
 * version is stamped only once every one of them has finished. A later wave raises the target
 * version and appends its stage; the in-flight stage/watermark keys are scoped to the version being
 * migrated and are cleared when it completes, and the stages a lower version already covered are
 * seeded as finished rather than re-run ([startingStageFor]).
 *
 * Nothing here is on a send or display path. The background pass runs on its own scope after a
 * startup delay and can be switched off entirely ([backgroundPassEnabled]); the per-row rescue paths
 * ([materializeFromLegacyAddress], reached from the download job before it hits the network, and
 * [migrateIfNeeded], the resolver seam) are never gated, because for a file the server has already
 * expired the legacy copy is the only one left.
 *
 * The version stamp retires the BACKGROUND PASS only — it means "the bulk pass is done trying", not
 * "no legacy file can exist". Rows the pass gave up on, and files it deliberately preserved (a
 * same-fileName re-forward whose server copy later expires), still have to be rescuable, so the
 * per-row paths above and the stray-temp sweep in [start] stay live until this module is deleted a
 * version or two later. Their steady-state cost is one stat on a path that is about to do far more.
 */
@Singleton
class ForwardAttachmentMigration @Inject constructor(
    @param:AppStateDataStore
    private val appState: DataStore<Preferences>
) : AttachmentPathResolver.Migrator {

    private val state = AttachmentMigrationState(appState)

    /**
     * The row primitives lent to each wave's stages. An adapter rather than a supertype: this class
     * is public (Hilt injects it), the seam is internal, and the primitives themselves stay private.
     */
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

    /** Each wave's stages, which borrow this class's row primitives through [RowFileMigrator]. */
    private val forwardStages by lazy { ForwardAttachmentStages(state, rowFileMigrator) }
    private val normalStages by lazy { NormalAttachmentStages(state, rowFileMigrator) }

    /**
     * Kill switch for the BACKGROUND pass only.
     *
     * There is no generic remote-config key mechanism to hang this on (`GlobalConfigsManager`
     * exposes a fixed server-defined schema, and `FeatureGrayManager` keys default to OFF and must
     * be agreed with the server), so the lever is local: flip this to false and ship. The lazy path
     * stays live either way — it is load-bearing for display correctness, and it touches one row.
     */
    @Volatile
    var backgroundPassEnabled: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val lazyMigrations = AtomicInteger(0)

    /**
     * Highest migration version fully completed on this device. 0 until [start] reads the stored
     * value; only ever moves forward. `>= TARGET_VERSION` means the background pass has finished
     * trying — the per-row rescue paths stay live regardless.
     */
    @Volatile
    private var completedVersion: Int = AppStateDefaults.ATTACHMENT_MIGRATION_VERSION

    /** Last finished stage of the version being migrated. Meaningless once it is stamped. */
    @Volatile
    private var stage: Int = AppStateDefaults.ATTACHMENT_MIGRATION_STAGE

    /**
     * What [TARGET_VERSION] requires, in order. Each stage reports whether it finished: one that did
     * not (the sweep, when a directory could not be verified) keeps its position so the next launch
     * retries it, and the version stays unstamped.
     *
     * A later wave appends its own stage here, tags it with the version that introduced it, and
     * raises [TARGET_VERSION]. Stages a device has already stamped into a lower version are seeded
     * as finished by [startingStageFor] rather than re-run.
     */
    private val stages: List<Stage> by lazy {
        listOf(
            Stage(STAGE_BACKFILLED, introducedInVersion = 1) { forwardStages.backfillLocalIds() },
            Stage(STAGE_FILES_MIGRATED, introducedInVersion = 1) { forwardStages.migrateFiles() },
            Stage(STAGE_SWEPT, introducedInVersion = 1) { forwardStages.sweepLegacyDirectories() },
            Stage(STAGE_NORMAL_MIGRATED, introducedInVersion = 2) { normalStages.migrateNormalFiles() },
            Stage(STAGE_FINAL_CLEANUP, introducedInVersion = 2) { normalStages.finalCleanup() }
        )
    }

    /**
     * One lock per attachment copy, shared by the background pass and the lazy path so the same row
     * is never migrated twice concurrently.
     *
     * `computeIfAbsent` (never `getOrPut`, which races two callers into two different locks — see
     * `ReactionSendCoordinator`), and the entry is dropped once nobody holds or wants it, so a heavy
     * forwarder's map never grows to the size of the attachment table.
     */
    private val rowLocks = ConcurrentHashMap<String, ReentrantLock>()

    /** Live row-lock entries — the map is expected to drain back to empty once migrations finish. */
    @VisibleForTesting
    internal fun activeRowLocks(): Int = rowLocks.size

    /**
     * Registers the migration seam and schedules the background pass. Idempotent; call from app
     * startup.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        AttachmentPathResolver.migrator = this
        scope.launch {
            // Its own guard: reading the stored marker goes through DataStore, which throws on a
            // corrupt or unreadable preferences file. This scope has no exception handler, so an
            // escaping throw would reach the default handler and crash the app on EVERY launch.
            // Skipping the background pass instead costs nothing that matters — completedVersion
            // keeps its default, so the per-row rescue paths stay live and the next launch tries
            // again.
            val stateReadable = runCatching { readState() }.onFailure {
                L.w { "[FwdAttachMigration] state unreadable, skipping background pass: ${it.stackTraceToString()}" }
            }.isSuccess
            // Let the cold start finish first: this is bulk file IO with no deadline.
            delay(START_DELAY_MS)
            runCatching {
                // Ahead of the version check on purpose: a process death can orphan a decrypt temp
                // long after the stamp, and nothing but this bounded once-per-launch walk of the
                // attachment root ever reclaims one.
                val strays = LegacyAttachmentFiles.sweepStrayTempFiles(File(FileUtil.getFilePath(FileUtil.FILE_DIR_ATTACHMENT)))
                if (strays > 0) L.i { "[FwdAttachMigration] cleared strayTempFiles=$strays" }
                if (!stateReadable || isComplete()) return@runCatching
                if (!backgroundPassEnabled) {
                    L.i { "[FwdAttachMigration] background pass disabled, version=$completedVersion stage=$stage" }
                    return@runCatching
                }
                runStages()
            }.onFailure {
                // Never fatal: an interrupted run resumes from its watermark on the next launch.
                L.w { "[FwdAttachMigration] run failed at stage=$stage: ${it.stackTraceToString()}" }
            }
        }
    }

    /**
     * Stops the background pass. Called on logout, before the database and the files it is scanning
     * are wiped — without it the pass spends the moments before process death logging IO errors
     * about rows that no longer exist.
     */
    fun cancel() {
        scope.cancel()
    }

    /**
     * Resolver seam. Not gated on the version stamp: the bulk pass finishing is not a proof that no
     * legacy file is left — see the class KDoc — and this is one directory stat per forward row on a
     * read gate that is already off the main thread.
     */
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

    /**
     * Copies this row's file from its legacy address when the current address has none. Applies to
     * every attachment, forwarded or not: with one addressing rule, a normal attachment written
     * before the flip sits under its owner message's directory and needs the same rescue.
     *
     * Returns true only when THIS call put a verified file there, so a caller about to download can
     * skip the network — and, crucially, still downloads when the file that is already at the current
     * address is unusable.
     *
     * Not gated on the version stamp: this is the ONLY thing that can rescue a legacy file the bulk
     * pass deliberately preserved or gave up on, and it sits in a job that is about to hit the
     * network anyway — once the file is at the current address the call is a single stat.
     *
     * [rowKey] is the EFFECTIVE identity the caller addressed [targetFilePath] by — the row's
     * persisted localId once the backfill has reached it, its deterministic synthesized id before
     * that (`attachmentRowKey`). Taken from the caller rather than re-derived from the row, because it
     * is also the row-lock key: keying the lock on anything but the identity the address was built
     * from splits the mutual exclusion this shares with the caller's own file placement. Reading the
     * NULL column instead would refuse every un-backfilled row — exactly the rows whose file is still
     * at a legacy address.
     */
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

    /** Legacy directory keys of a row, in the order the pre-migration code wrote them. */
    private fun legacyKeysFor(row: AttachmentModel): List<String> = legacyKeysFor(
        isForwardCopy = row.forwardModelDatabaseId != null,
        authorityId = row.authorityId ?: 0L,
        ownerMessageId = row.messageId
    )

    /**
     * Where an attachment's file may still sit, newest legacy address first.
     *
     * The authorityId directory is a FORWARD-only address: the pseudo message built for a forwarded
     * bubble was keyed by it. Offering it for a normal attachment would reach across into a forward
     * sibling's directory, so it is gated on the row's own ownership fact rather than on whether the
     * value happens to be set.
     *
     * The owner message's directory is where every pre-migration write landed — the receive path for
     * a normal attachment, and `createForward` for a forwarded one.
     */
    private fun legacyKeysFor(
        isForwardCopy: Boolean,
        authorityId: Long,
        ownerMessageId: String?
    ): List<String> = listOfNotNull(
        authorityId.takeIf { isForwardCopy && it != 0L }?.toString(),
        ownerMessageId?.takeIf { it.isNotEmpty() }
    )

    /**
     * Runs [block] holding this attachment copy's row lock, so a file write cannot interleave with a
     * migration of the same copy.
     *
     * The public half of the lock the lazy path and the background stages already share: the
     * download job takes it around the moment it puts a file at the copy's address, which is exactly
     * when stage 4 might be renaming that copy's legacy directory onto it. A completed migration
     * makes this a straight call — no lock, no map entry — so the coupling retires with the module.
     *
     * [rowKey] must be the identity the ADDRESS being written was built from, not the row's possibly
     * still-NULL localId column: the two only coincide after the backfill, and a lock taken under a
     * different key than the migration uses for the same address is no lock at all.
     */
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

    /**
     * Runs [block] with this copy's row lock held, dropping the lock once nobody else holds or wants
     * it.
     *
     * The map entry must still BE this lock after acquiring it: a holder's eviction check cannot see
     * a thread that took the lock object from the map but has not called `lock()` yet
     * (`hasQueuedThreads` only sees blocked threads), so that thread can end up holding an orphaned
     * lock while another mints a fresh one for the same copy — two "exclusive" sections running at
     * once. Since stage 4's directory rename and the download job's file placement rely on this lock
     * for REAL mutual exclusion, a stale acquisition is released and retried instead of tolerated.
     *
     * The eviction happens while the lock is still HELD, which is the other half of the same
     * argument: evicting after `unlock()` can drop an entry a newly arrived thread has already
     * validated, leaving it holding an orphaned lock while a third thread mints a fresh one. Removing
     * under the lock cannot — a thread that took this object before the removal still fails the
     * identity check above and retries, and one blocked on it is visible to `hasQueuedThreads`.
     */
    private fun <T> withRowLock(localId: String, block: () -> T): T {
        while (true) {
            val lock = rowLocks.computeIfAbsent(localId) { ReentrantLock() }
            lock.lock()
            if (rowLocks[localId] !== lock) {
                // Evicted between computeIfAbsent and lock(): someone else may own a fresh entry.
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

    // region background pass

    private suspend fun runStages() {
        // Stamping a version clears the in-flight stage, so a device that finished an earlier one
        // arrives here reading stage 0 and would re-run every stage that version already completed.
        // Those re-runs are correct but not free (a full-table forward scan, a full attachment-root
        // sweep), so the stages that version covered are seeded as finished instead. Never a
        // regression: an interrupted run's own stage is always the higher of the two.
        stage = maxOf(stage, startingStageFor(completedVersion, stages))
        for (next in stages) {
            if (stage >= next.ordinal) continue
            // An unfinished stage stops the run WITHOUT stamping: the version means "all of it is
            // done", so leaving it unstamped is what brings the next launch back to this same stage.
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

    /**
     * A file that is readable at its current address but whose row still says LOADING would be
     * downloaded again for nothing — the cross-row status writes that used to cover this row are
     * exactly what per-copy addressing removed.
     */
    private fun repairStatus(row: AttachmentModel, targetBasePath: File) {
        if (row.status != AttachmentStatus.LOADING.code) return
        if (!LegacyAttachmentFiles.isVerified(targetBasePath, row.size)) return
        wcdb.attachment.updateValue(
            AttachmentStatus.SUCCESS.code,
            DBAttachmentModel.status,
            DBAttachmentModel.localId.eq(row.localId)
        )
    }

    // endregion

    // region state

    /** True when the background pass has nothing left to attempt for this build's target version. */
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

    /**
     * Tells the deletion path whether a file may still sit at a pre-per-copy address.
     *
     * This module is the only component that knows the answer, so it is the one that publishes it —
     * and it does so from the one fact that decides it, the completed version. Deletion lives in
     * `:database`, far from any migration type, which is why the answer travels as a plain flag
     * rather than as a dependency. Republished on every state read and on completion; the default
     * (open) is the safe one, so the window between process start and the first read over-deletes at
     * worst.
     */
    private fun publishLegacyAddressWindow() {
        LegacyAttachmentAddresses.isWindowOpen = !isComplete()
    }

    // endregion

    companion object {
        /**
         * Migration version this build requires. v1 = forwarded attachments addressed per copy;
         * v2 = every attachment addressed per copy, normal messages included.
         * A later wave raises this and appends the stage it needs to [stages].
         */
        const val TARGET_VERSION = 2

        const val STAGE_BACKFILLED = 1
        const val STAGE_FILES_MIGRATED = 2
        const val STAGE_SWEPT = 3
        const val STAGE_NORMAL_MIGRATED = 4
        const val STAGE_FINAL_CLEANUP = 5

        /**
         * The stage a device whose highest completed version is [completedVersion] may start FROM —
         * the last ordinal that version's own stage list already covered.
         *
         * Pure and total, so the upgrade paths that matter can be pinned without a database: a
         * v1-complete device seeds past v1's stages and starts at the first v2 one, a fresh install
         * seeds nothing and starts at the first stage, and a device interrupted mid-version seeds
         * nothing either (its own recorded stage is what resumes it — see [runStages]).
         *
         * Reads the list in order and stops at the first stage the version does not cover: stages
         * are ordered by ordinal, and a version is only ever completed as a whole.
         */
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

        /** Rows read per page by every stage that walks the attachment table. */
        internal const val BATCH_SIZE = 100L

        /**
         * Lazy on purpose: touching a generated `DB*Model` binding loads the native WCDB library, so
         * building this eagerly would make the class unloadable wherever that library is absent.
         */
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

        /** What the sweep needs to state where each claiming row's own copy must be. Lazy, as above. */
        internal val SWEEP_FIELDS by lazy {
            arrayOf(
                DBAttachmentModel.localId,
                DBAttachmentModel.fileName,
                DBAttachmentModel.size
            )
        }
    }
}
