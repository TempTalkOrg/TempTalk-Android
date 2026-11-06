package com.difft.android.chat.jobmanager

import com.difft.android.chat.jobmanager.persistence.DurableRestore
import com.difft.android.chat.jobmanager.persistence.DurableJob
import com.difft.android.chat.jobmanager.persistence.FullSpec
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.chat.jobmanager.persistence.JobStorage
import com.difft.android.chat.jobmanager.persistence.StrictWrite
import kotlin.coroutines.cancellation.CancellationException

internal data class PersistedAdmission(
    val admission: QueueAdmission,
    val newlyCommitted: Boolean = false,
    val needsScheduling: Boolean = false,
)

/** Runs only on the controller's serial management dispatcher. Never consults the fast cache. */
internal fun admitPersistedJob(
    job: Job,
    storage: JobStorage,
    exceedsLimit: () -> Boolean,
    buildSpec: () -> FullSpec,
    matchesCreator: (JobSpec) -> Boolean,
): PersistedAdmission {
    if (job.parameters.isMemoryOnly) return PersistedAdmission(QueueAdmission.FailedBeforePersist("memory_only"))
    when (val existing = storage.findDurableJob(job.id)) {
        is DurableJob.Found -> {
            if (!matchesCreator(existing.spec)) return PersistedAdmission(QueueAdmission.Unconfirmed(job.id, "creator_mismatch"))
            if (storage.restoreDurableJob(existing) == DurableRestore.Unsupported) {
                return PersistedAdmission(QueueAdmission.Unconfirmed(job.id, "cache_recovery_failed"))
            }
            return PersistedAdmission(QueueAdmission.Persisted(job.id), needsScheduling = true)
        }
        is DurableJob.ReadFailed -> return PersistedAdmission(QueueAdmission.Unconfirmed(job.id, existing.reason))
        DurableJob.Absent -> Unit
    }
    if (exceedsLimit()) return PersistedAdmission(QueueAdmission.RejectedLimit)
    val spec = try {
        buildSpec()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return PersistedAdmission(QueueAdmission.FailedBeforePersist("serialization_exception"))
    } catch (_: AssertionError) {
        return PersistedAdmission(QueueAdmission.FailedBeforePersist("serialization_exception"))
    }
    val write = try {
        storage.insertJobsStrict(listOf(spec))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return PersistedAdmission(QueueAdmission.Unconfirmed(job.id, "storage_exception"))
    }
    when (write) {
        StrictWrite.Committed -> Unit
        StrictWrite.RejectedMemoryOnly -> return PersistedAdmission(QueueAdmission.FailedBeforePersist("memory_only"))
        is StrictWrite.Failed -> return PersistedAdmission(QueueAdmission.FailedBeforePersist(write.reason))
        is StrictWrite.Unknown -> when (val durable = storage.findDurableJob(job.id)) {
            is DurableJob.Found -> {
                if (!matchesCreator(durable.spec)) {
                    return PersistedAdmission(QueueAdmission.Unconfirmed(job.id, "creator_mismatch"))
                }
                if (storage.restoreDurableJob(durable) == DurableRestore.Unsupported) {
                    return PersistedAdmission(QueueAdmission.Unconfirmed(job.id, "cache_recovery_failed"))
                }
            }
            DurableJob.Absent -> return PersistedAdmission(QueueAdmission.FailedBeforePersist("verified_absent"))
            is DurableJob.ReadFailed -> return PersistedAdmission(QueueAdmission.Unconfirmed(job.id, durable.reason))
        }
    }
    return PersistedAdmission(QueueAdmission.Persisted(job.id), newlyCommitted = true, needsScheduling = true)
}
