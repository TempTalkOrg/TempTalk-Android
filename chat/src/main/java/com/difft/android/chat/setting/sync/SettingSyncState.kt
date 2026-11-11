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

    
    private val finalized = ConcurrentHashMap<String, String>()

    
    fun lockFor(roomId: String): Mutex = roomLocks.computeIfAbsent(roomId) { Mutex() }

    fun recordIntent(roomId: String, field: SyncField, ts: Long) {
        latestIntent[intentKey(roomId, field)] = Intent(ts, elapsedRealtime())
    }

    
    fun activeIntent(roomId: String, field: SyncField): Intent? {
        val key = intentKey(roomId, field)
        val intent = latestIntent[key] ?: return null
        if (elapsedRealtime() - intent.atElapsed > INTENT_TTL_MS) {
            
            latestIntent.remove(key, intent)
            L.i { "[SettingSync] intent expired room=$roomId field=${field.key} ts=${intent.ts}" }
            return null
        }
        return intent
    }

    
    fun clearIntentIf(roomId: String, field: SyncField, ts: Long) {
        latestIntent.computeIfPresent(intentKey(roomId, field)) { _, intent -> if (intent.ts == ts) null else intent }
    }

    fun markFinalized(jobId: String, queueKey: String) {
        finalized[jobId] = queueKey
    }

    



    suspend fun pendingJobs(roomId: String, field: SyncField, excludeJobId: String? = null): List<JobSpec> {
        val queue = queueKey(roomId, field)
        val specs = jobManagerProvider().findJobsInQueue(queue)
        val present = specs.mapTo(HashSet()) { it.id }
        finalized.entries.removeIf { (id, q) -> q == queue && id !in present }
        return specs.filter { it.id != excludeJobId && !finalized.containsKey(it.id) }
    }

    suspend fun isPending(roomId: String, field: SyncField): Boolean =
        activeIntent(roomId, field) != null || pendingJobs(roomId, field).isNotEmpty()

    
    suspend fun latestIntentTs(roomId: String, field: SyncField): Long? {
        val fromMemory = activeIntent(roomId, field)?.ts
        val fromQueue = pendingJobs(roomId, field).mapNotNull { operationTsOf(it) }.maxOrNull()
        return listOfNotNull(fromMemory, fromQueue).maxOrNull()
    }

    private fun operationTsOf(spec: JobSpec): Long? = try {
        dataSerializer.deserialize(spec.serializedData).getLongOrDefault(SettingSyncJob.KEY_TS, 0L).takeIf { it > 0 }
    } catch (e: Throwable) {
        
        L.w { "[SettingSync] spec unreadable job=${spec.id} err=${e.javaClass.simpleName}" }
        null
    }

    private fun intentKey(roomId: String, field: SyncField) = "$roomId::${field.key}"

    companion object {
        const val INTENT_TTL_MS = 10_000L

        fun queueKey(roomId: String, field: SyncField) = "[SettingSync::$roomId::${field.key}]"
    }
}
