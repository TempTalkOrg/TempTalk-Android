package com.difft.android.chat.setting.sync

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobmanager.Data
import com.difft.android.chat.jobmanager.JobManager
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.chat.jobs.SettingSyncJob
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory coordination state for local-first setting sync. The persisted truth is the job
 * queue itself: a pending [SettingSyncJob] in `[SettingSync::<room>::<field>]` means an unsynced
 * local operation. This class adds what the queue cannot express:
 *
 * - a per-room [Mutex] so user toggles, server-snapshot application and job write-backs never
 *   interleave;
 * - [recordIntent]: the toggle's timestamp, visible before `JobManager.add` lands on its
 *   single-thread management dispatcher. Entries expire after [INTENT_TTL_MS] so an enqueue that
 *   never landed cannot gate the room forever;
 * - [markFinalized]: jobs whose final handling ran but which the framework has not deleted yet,
 *   so a snapshot arriving in that window is not gated by them.
 *
 * The maps are never pruned beyond what the queries do: entries are a few dozen bytes each and
 * bounded by the conversations the user toggles in.
 */
@Singleton
class SettingSyncState @Inject constructor(
    private val dataSerializer: Data.Serializer,
) {
    data class Intent(val ts: Long, val atElapsed: Long)

    @VisibleForTesting
    internal var jobManagerProvider: () -> JobManager = { ApplicationDependencies.getJobManager() }

    @VisibleForTesting
    internal var elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() }

    private val roomLocks = ConcurrentHashMap<String, Mutex>()
    private val latestIntent = ConcurrentHashMap<String, Intent>()

    /** jobId → queueKey. */
    private val finalized = ConcurrentHashMap<String, String>()

    // computeIfAbsent, not getOrPut: concurrent first use for the same room must share one Mutex.
    fun lockFor(roomId: String): Mutex = roomLocks.computeIfAbsent(roomId) { Mutex() }

    fun recordIntent(roomId: String, field: SyncField, ts: Long) {
        latestIntent[intentKey(roomId, field)] = Intent(ts, elapsedRealtime())
    }

    /** The unexpired local intent, or null. Expired entries are dropped on read. */
    fun activeIntent(roomId: String, field: SyncField): Intent? {
        val key = intentKey(roomId, field)
        val intent = latestIntent[key] ?: return null
        if (elapsedRealtime() - intent.atElapsed > INTENT_TTL_MS) {
            // Normal while offline: the queued job carries the intent from here on.
            latestIntent.remove(key, intent)
            L.i { "[SettingSync] intent expired room=$roomId field=${field.key} ts=${intent.ts}" }
            return null
        }
        return intent
    }

    /** Clears the intent only if it is still the one recorded for [ts]; a newer intent survives. */
    fun clearIntentIf(roomId: String, field: SyncField, ts: Long) {
        latestIntent.computeIfPresent(intentKey(roomId, field)) { _, intent -> if (intent.ts == ts) null else intent }
    }

    fun markFinalized(jobId: String, queueKey: String) {
        finalized[jobId] = queueKey
    }

    /**
     * Jobs still pending in the field's queue, excluding finalized ones and [excludeJobId].
     * Also drops finalized entries whose job has since been deleted.
     */
    suspend fun pendingJobs(roomId: String, field: SyncField, excludeJobId: String? = null): List<JobSpec> {
        val queue = queueKey(roomId, field)
        val specs = jobManagerProvider().findJobsInQueue(queue)
        val present = specs.mapTo(HashSet()) { it.id }
        finalized.entries.removeIf { (id, q) -> q == queue && id !in present }
        return specs.filter { it.id != excludeJobId && !finalized.containsKey(it.id) }
    }

    suspend fun isPending(roomId: String, field: SyncField): Boolean =
        activeIntent(roomId, field) != null || pendingJobs(roomId, field).isNotEmpty()

    /** Operation time of the newest local intent (memory or queue), or null when nothing is pending. */
    suspend fun latestIntentTs(roomId: String, field: SyncField): Long? {
        val fromMemory = activeIntent(roomId, field)?.ts
        val fromQueue = pendingJobs(roomId, field).mapNotNull { operationTsOf(it) }.maxOrNull()
        return listOfNotNull(fromMemory, fromQueue).maxOrNull()
    }

    private fun operationTsOf(spec: JobSpec): Long? = try {
        dataSerializer.deserialize(spec.serializedData).getLongOrDefault(SettingSyncJob.KEY_TS, 0L).takeIf { it > 0 }
    } catch (e: Throwable) {
        // JsonDataSerializer wraps IOException in AssertionError; a corrupt spec must not break the query.
        L.w { "[SettingSync] spec unreadable job=${spec.id} err=${e.javaClass.simpleName}" }
        null
    }

    private fun intentKey(roomId: String, field: SyncField) = "$roomId::${field.key}"

    companion object {
        const val INTENT_TTL_MS = 10_000L

        fun queueKey(roomId: String, field: SyncField) = "[SettingSync::$roomId::${field.key}]"
    }
}
