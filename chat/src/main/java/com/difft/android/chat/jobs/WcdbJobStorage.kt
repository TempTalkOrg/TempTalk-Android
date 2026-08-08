package com.difft.android.chat.jobs

import androidx.annotation.WorkerThread
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.chat.common.SendType
import com.difft.android.chat.jobmanager.persistence.ConstraintSpec
import com.difft.android.chat.jobmanager.persistence.FullSpec
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.tencent.wcdb.base.Value
import com.tencent.wcdb.winq.Order
import com.tencent.wcdb.winq.OrderingTerm
import difft.android.messageserialization.model.ROOM_SEND_STATUS_FAILED
import org.difft.app.database.WCDB
import org.difft.app.database.roomIdsWithStaleSendingOutgoing
import org.difft.app.database.writeRoomSendStatusFor
import org.difft.app.database.models.DBJobConstraintModel
import org.difft.app.database.models.DBJobSpecModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.JobConstraintModel
import org.difft.app.database.models.JobSpecModel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WCDB-backed persistence collaborator for [FastJobStorage].
 *
 * Replaces the legacy SQLCipher [com.difft.android.chat.database.JobDatabase]. The
 * method surface matches legacy 1:1 so [FastJobStorage] only needs a constructor
 * parameter swap (hard constraint C, design §4.2.2).
 *
 * **Per-method exception parity (design §4.2.1)**:
 * - **Swallow** (log + continue): [insertJobs], [getAllJobSpecs],
 *   [getAllConstraintSpecs], [updateJobs] — matches legacy `catch (e) { L.e(...) }`
 *   in `JobDatabase.kt:87-123 / 162-195 / 214-234`.
 * - **Propagate** (rethrow after log): [updateJobRunningState],
 *   [updateJobAfterRetry], [updateAllJobsToBePending], [deleteJobs] — matches
 *   legacy no-catch `finally { endTransaction() }` pattern in
 *   `JobDatabase.kt:125-160 / 197-211`.
 *
 * **Thread safety (D10)**: no `@Synchronized`. `JobController`'s management
 * dispatcher (`limitedParallelism(1)`) serializes all calls from the job-manager
 * side; WCDB's connection pool serializes writes at the handle level.
 * [WcdbJobStorage] itself holds no in-memory state.
 */
@Singleton
class WcdbJobStorage @Inject constructor(
    private val wcdb: WCDB
) {

    // ─── Writes ───────────────────────────────────────────────────────────────

    /**
     * Insert all non-memory-only specs in a single transaction using
     * `insertOrIgnoreObject` semantics (CONFLICT_IGNORE in legacy, D13).
     * Must not use `insertOrReplaceObject` — that would reset `runAttempt`.
     *
     * Legacy swallows exceptions (`JobDatabase.kt:87-105`); match legacy.
     */
    @WorkerThread
    fun insertJobs(fullSpecs: List<FullSpec>) {
        if (fullSpecs.all { it.jobSpec.isMemoryOnly }) return
        try {
            wcdb.db.runTransaction {
                fullSpecs.filterNot { it.jobSpec.isMemoryOnly }.forEach { spec ->
                    wcdb.jobSpec.insertOrIgnoreObject(spec.jobSpec.toModel())
                    spec.constraintSpecs
                        .filterNot { it.isMemoryOnly }
                        .forEach { c ->
                            wcdb.jobConstraint.insertOrIgnoreObject(c.toModel())
                        }
                }
                true
            }
        } catch (e: Exception) {
            // Legacy swallows — match legacy (JobDatabase.kt:87-105)
            L.e { "[JobStorage] insertJobs failed count=${fullSpecs.size}: ${e.stackTraceToString()}" }
        }
    }

    /**
     * Update durable jobs in a single transaction. Each `job.id` row is updated
     * in place by `jobSpecId` — uses `updateRow` with explicit column list rather
     * than `insertOrReplace` to avoid touching unset fields.
     *
     * Legacy swallows exceptions (`JobDatabase.kt:162-195`); match legacy.
     */
    @WorkerThread
    fun updateJobs(jobs: List<JobSpec>) {
        if (jobs.all { it.isMemoryOnly }) return
        try {
            wcdb.db.runTransaction {
                jobs.filterNot { it.isMemoryOnly }.forEach { job ->
                    wcdb.jobSpec.updateRow(
                        arrayOf(
                            Value(job.id),
                            Value(job.factoryKey),
                            Value(job.queueKey),
                            Value(job.createTime),
                            Value(job.nextRunAttemptTime),
                            Value(job.runAttempt),
                            Value(job.maxAttempts),
                            Value(job.lifespan),
                            Value(job.serializedData),
                            Value(job.isRunning)
                        ),
                        arrayOf(
                            DBJobSpecModel.jobSpecId,
                            DBJobSpecModel.factoryKey,
                            DBJobSpecModel.queueKey,
                            DBJobSpecModel.createTime,
                            DBJobSpecModel.nextRunAttemptTime,
                            DBJobSpecModel.runAttempt,
                            DBJobSpecModel.maxAttempts,
                            DBJobSpecModel.lifespan,
                            DBJobSpecModel.serializedData,
                            DBJobSpecModel.isRunning
                        ),
                        DBJobSpecModel.jobSpecId.eq(job.id)
                    )
                }
                true
            }
        } catch (e: Exception) {
            // Legacy swallows — match legacy (JobDatabase.kt:162-195)
            L.e { "[JobStorage] updateJobs failed count=${jobs.size}: ${e.stackTraceToString()}" }
        }
    }

    /**
     * Single-row update. Called on every `onRunning=true/false` transition — no
     * batching needed. Legacy has no catch (`JobDatabase.kt:125-133`) → propagate.
     */
    @WorkerThread
    fun updateJobRunningState(id: String, isRunning: Boolean) {
        wcdb.jobSpec.updateValue(
            Value(isRunning),
            DBJobSpecModel.isRunning,
            DBJobSpecModel.jobSpecId.eq(id)
        )
    }

    /**
     * Single-row update. Called per retry attempt — kept lean: no logging on
     * success (hot path, called once per retry — logging would flood).
     * Legacy has no catch (`JobDatabase.kt:135-152`) → propagate.
     */
    @WorkerThread
    fun updateJobAfterRetry(
        id: String,
        isRunning: Boolean,
        runAttempt: Int,
        nextRunAttemptTime: Long,
        serializedData: String
    ) {
        wcdb.jobSpec.updateRow(
            arrayOf(
                Value(isRunning),
                Value(runAttempt),
                Value(nextRunAttemptTime),
                Value(serializedData)
            ),
            arrayOf(
                DBJobSpecModel.isRunning,
                DBJobSpecModel.runAttempt,
                DBJobSpecModel.nextRunAttemptTime,
                DBJobSpecModel.serializedData
            ),
            DBJobSpecModel.jobSpecId.eq(id)
        )
    }

    /**
     * Unconditional all-rows update SET `isRunning = false`. Called exactly once
     * at startup by `JobController` to clear any stale running flags left by a
     * process kill. Legacy has no catch (`JobDatabase.kt:154-160`) → propagate.
     */
    @WorkerThread
    fun updateAllJobsToBePending() {
        wcdb.jobSpec.updateValue(
            Value(false),
            DBJobSpecModel.isRunning
        )
    }

    /**
     * Delete jobs by id. Both `job_spec` and `job_constraint` rows are removed
     * inside a single transaction — explicit cascade, no SQL FK/ON DELETE CASCADE
     * (matches legacy which also issued two DELETEs per id; see D9 and
     * `JobDatabase.kt:197-211`).
     *
     * **PROPAGATE (rethrow after logging)** — legacy parity (`JobDatabase.kt:197-211`
     * has no `catch`, only `finally { endTransaction() }`) + future-refactor guard.
     */
    @WorkerThread
    fun deleteJobs(ids: List<String>) {
        if (ids.isEmpty()) return
        try {
            wcdb.db.runTransaction {
                val idsArray = ids.toTypedArray()
                wcdb.jobSpec.deleteObjects(DBJobSpecModel.jobSpecId.`in`(*idsArray))
                wcdb.jobConstraint.deleteObjects(DBJobConstraintModel.jobSpecId.`in`(*idsArray))
                true
            }
        } catch (e: Exception) {
            L.e { "[JobStorage] deleteJobs failed count=${ids.size}: ${e.stackTraceToString()}" }
            // PROPAGATE — legacy parity (JobDatabase.kt:197-211 has no catch) +
            // future-refactor guard. Do not silently swallow.
            throw e
        }
        // Diagnostic count: deletion already committed above. Any failure here
        // is logged-only and MUST NOT be propagated — the caller would otherwise
        // treat a successful delete as a failure.
        val remaining = try {
            wcdb.jobSpec.getValue(DBJobSpecModel.jobSpecId.count())?.int ?: -1
        } catch (e: Exception) {
            L.w { "[JobStorage] deleteJobs remaining-count query failed: ${e.stackTraceToString()}" }
            -1
        }
        L.i { "[JobStorage] deleted ${ids.size}, jobSpec remaining=$remaining" }
    }

    // ─── Reads ────────────────────────────────────────────────────────────────

    /**
     * Read all persisted specs ordered by `createTime ASC, jobSpecId ASC`.
     * Legacy sorted by `create_time, _id ASC` with `_id` as the implicit
     * tiebreaker (legacy had an AUTOINCREMENT `_id`); since the new schema has no
     * `_id`, we use `jobSpecId` as a stable tiebreaker (D12 — documented delta in
     * design §4.1.2). Legacy swallows (`JobDatabase.kt:108-123`); match legacy.
     *
     * WCDB's `Table.getAllObjects` helpers only accept a single `OrderingTerm`;
     * use `prepareSelect()` fluent chain to pass two terms via `orderBy(vararg)`.
     */
    @WorkerThread
    fun getAllJobSpecs(): List<JobSpec> = try {
        wcdb.jobSpec.prepareSelect()
            .select(*DBJobSpecModel.allBindingFields())
            .orderBy(
                OrderingTerm(DBJobSpecModel.createTime).order(Order.Asc),
                OrderingTerm(DBJobSpecModel.jobSpecId).order(Order.Asc)
            )
            .allObjects()
            .map { it.toJobSpec() }
    } catch (e: Exception) {
        // Legacy swallows — match legacy (JobDatabase.kt:108-123)
        L.e { "[JobStorage] getAllJobSpecs failed: ${e.stackTraceToString()}" }
        emptyList()
    }

    /**
     * Read all persisted constraint specs. Ordering not required by callers.
     * Legacy swallows (`JobDatabase.kt:214-234`); match legacy.
     */
    @WorkerThread
    fun getAllConstraintSpecs(): List<ConstraintSpec> = try {
        wcdb.jobConstraint.allObjects.map { it.toConstraintSpec() }
    } catch (e: Exception) {
        // Legacy swallows — match legacy (JobDatabase.kt:214-234)
        L.e { "[JobStorage] getAllConstraintSpecs failed: ${e.stackTraceToString()}" }
        emptyList()
    }

    // ─── Startup helper (not part of legacy interface) ────────────────────────

    /**
     * Startup sweep for orphan `Sending` messages — those whose `PushTextSendJob`
     * was lost (e.g. legacy `signal-jobmanager.db` deleted on PR 2 upgrade).
     *
     * **Skips if any `PushTextSendJob` is still persisted in `job_spec`** — those
     * Jobs are alive in the new WCDB queue and will resume retry / settle the
     * message status (Sent or SentFailed) themselves. Sweeping while they're
     * mid-retry would prematurely flip in-flight messages to SentFailed and
     * lose the auto-recovery the persistence layer guarantees.
     *
     * Genuine orphans only happen when `job_spec` has no PushTextSendJob — the
     * upgrade-boundary case the sweep was designed for.
     *
     * Idempotent by WHERE clause. Called from
     * [com.difft.android.app.TempTalkApplication]'s `AppStartup` chain.
     *
     * `SendType.rawValue` is `Int` (from `SendMessageUtils.kt:21-23`):
     * - `SendType.Sending.rawValue` = 0
     * - `SendType.SentFailed.rawValue` = 2
     *
     * The message-row flip keeps its original predicate. Only the room-tag side narrows to real
     * outgoing messages (`roomIdsWithStaleSendingOutgoing`, shared spelling with
     * `hasFailedOutgoingMessage`): a locally created notify row such as the archive tombstone
     * carries `sendType == 0` without ever having been sent, and must not earn its room a tag.
     */
    @WorkerThread
    fun sweepStaleSendingMessages(): Int = try {
        val pendingSendJobs = wcdb.jobSpec.getValue(
            DBJobSpecModel.jobSpecId.count(),
            DBJobSpecModel.factoryKey.eq(PushTextSendJob.KEY)
        )?.int ?: 0
        if (pendingSendJobs > 0) {
            L.i { "[JobStorage] sweepStaleSendingMessages: skip, $pendingSendJobs PushTextSendJob in flight" }
            return 0
        }
        val before = wcdb.message.getValue(
            DBMessageModel.databaseId.count(),
            DBMessageModel.sendType.eq(SendType.Sending.rawValue)
        )?.int ?: 0
        if (before == 0) {
            L.i { "[JobStorage] sweepStaleSendingMessages: nothing to do" }
            return 0
        }
        // Collected BEFORE the flip — afterwards no row matches. Tag-side scope only: rooms whose
        // sole stale-looking row is a notify tombstone are excluded, so they never get a tag.
        val roomsToFlag = wcdb.roomIdsWithStaleSendingOutgoing()
        // Message rows first, room rows second — the same load-bearing ordering as
        // PushTextSendJob.updateMessage: the clear side is a conditional UPDATE that re-reads the
        // message table, so committing the message flip first is what makes any interleaving safe.
        // Do NOT hoist the room write above the flip. No transaction: the failure window costs the
        // room its tag only, and the room's next failure restores it — not worth a write lock on
        // the startup path.
        wcdb.message.updateValue(
            Value(SendType.SentFailed.rawValue),
            DBMessageModel.sendType,
            DBMessageModel.sendType.eq(SendType.Sending.rawValue)
        )
        wcdb.writeRoomSendStatusFor(roomsToFlag, ROOM_SEND_STATUS_FAILED)
        // Best-effort nudge for an already-open conversation list; NOT the correctness path. The
        // room rows are written directly above because this runs from Application.onCreate, BEFORE
        // WCDBUpdateService registers its collector, and RoomChangeTracker.roomChanges is
        // replay = 0 — an emission with no subscriber is dropped.
        roomsToFlag.forEach { RoomChangeTracker.trackRoom(it, RoomChangeType.MESSAGE) }
        L.i { "[JobStorage] sweepStaleSendingMessages count=$before rooms=${roomsToFlag.size} Sending->SentFailed" }
        before
    } catch (e: Exception) {
        L.e { "[JobStorage] sweepStaleSendingMessages failed: ${e.stackTraceToString()}" }
        0
    }

    // ─── Mappers ──────────────────────────────────────────────────────────────

    private fun JobSpec.toModel(): JobSpecModel = JobSpecModel().also {
        it.jobSpecId = id
        it.factoryKey = factoryKey
        it.queueKey = queueKey
        it.createTime = createTime
        it.nextRunAttemptTime = nextRunAttemptTime
        it.runAttempt = runAttempt
        it.maxAttempts = maxAttempts
        it.lifespan = lifespan
        it.serializedData = serializedData
        it.isRunning = isRunning
    }

    private fun JobSpecModel.toJobSpec(): JobSpec = JobSpec(
        id = jobSpecId,
        factoryKey = factoryKey,
        queueKey = queueKey,
        createTime = createTime,
        nextRunAttemptTime = nextRunAttemptTime,
        runAttempt = runAttempt,
        maxAttempts = maxAttempts,
        lifespan = lifespan,
        serializedData = serializedData ?: "",
        isRunning = isRunning,
        // DB entries are always durable — the in-memory layer in FastJobStorage
        // handles memory-only specs and never persists them.
        isMemoryOnly = false
    )

    private fun ConstraintSpec.toModel(): JobConstraintModel = JobConstraintModel().also {
        it.jobSpecId = jobSpecId
        it.factoryKey = factoryKey
    }

    private fun JobConstraintModel.toConstraintSpec(): ConstraintSpec = ConstraintSpec(
        jobSpecId = jobSpecId,
        factoryKey = factoryKey,
        isMemoryOnly = false
    )
}
