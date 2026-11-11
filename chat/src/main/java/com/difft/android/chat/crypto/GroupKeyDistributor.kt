package com.difft.android.chat.crypto

import com.difft.android.PushGroupKeySendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.QueueAdmission
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton







@Singleton
class GroupKeyDistributor @Inject constructor(
    private val groupCryptoRepo: GroupCryptoRepo,
    private val pushGroupKeySendJobFactory: PushGroupKeySendJobFactory,
) {
    
    class PendingMembers internal constructor(
        val gid: String,
        val creatorLogin: String,
        val jobs: Map<String, Job>,
        val admissions: MutableMap<String, QueueAdmission> = mutableMapOf(),
    ) {
        internal val mutex = Mutex()
    }

    sealed interface Preparation {
        data object NotRequired : Preparation
        data object KeyUnavailable : Preparation
        data class Ready(val pending: PendingMembers) : Preparation
    }

    fun prepareMembers(gid: String, memberUids: List<String>, encrypted: Boolean, creatorLogin: String): Preparation {
        if (!encrypted) return Preparation.NotRequired
        if (groupCryptoRepo.getRGroupBytes(gid) == null) return Preparation.KeyUnavailable
        return Preparation.Ready(PendingMembers(gid, creatorLogin, memberUids.distinct()
            .filterNot { it == globalServices.myId }
            .associateWith { uid ->
                pushGroupKeySendJobFactory.create(null, gid, uid, false)
            }))
    }

    
    suspend fun enqueueMembersPersisted(pending: PendingMembers): Map<String, QueueAdmission> = pending.mutex.withLock {
        val keyAvailable = groupCryptoRepo.getRGroupBytes(pending.gid) != null
        pending.jobs.forEach { (uid, job) ->
            if (pending.admissions[uid] !is QueueAdmission.Persisted) {
                if (!keyAvailable) {
                    if (pending.admissions[uid] !is QueueAdmission.Unconfirmed) {
                        pending.admissions[uid] = QueueAdmission.FailedBeforePersist("key_unavailable")
                    }
                    return@forEach
                }
                pending.admissions[uid] = try {
                    ApplicationDependencies.getJobManager().enqueuePersisted(job, pending.creatorLogin)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.e { "[GroupKeyDistributor] event=admission_unconfirmed ${e.stackTraceToString()}" }
                    QueueAdmission.Unconfirmed(job.id, "submission_exception")
                }
            }
        }
        pending.admissions.toMap()
    }

    



    fun distributeToGroup(gid: String) {
        if (groupCryptoRepo.getRGroupBytes(gid) == null) {
            L.w { "[GE][GroupKeyDistributor] No R_group for group $gid, skip distribution" }
            return
        }
        L.i { "[GE][GroupKeyDistributor] Enqueue broadcast R_group for group $gid" }
        ApplicationDependencies.getJobManager().add(
            pushGroupKeySendJobFactory.create(
                parameters = null,
                gid = gid,
                recipientId = gid,
                isBroadcast = true,
            )
        )
    }

    




    suspend fun distributeToGroupPersisted(gid: String, creatorLogin: String): QueueAdmission {
        if (groupCryptoRepo.getRGroupBytes(gid) == null) {
            L.w { "[GE][GroupKeyDistributor] No R_group for group $gid, skip distribution" }
            return QueueAdmission.FailedBeforePersist("no_rgroup")
        }
        val job = pushGroupKeySendJobFactory.create(parameters = null, gid = gid, recipientId = gid, isBroadcast = true)
        return try {
            ApplicationDependencies.getJobManager().enqueuePersisted(job, creatorLogin)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e { "[GroupKeyDistributor] event=broadcast_admission_unconfirmed ${e.stackTraceToString()}" }
            QueueAdmission.Unconfirmed(job.id, "submission_exception")
        }
    }

    



    fun distributeToMember(gid: String, memberUid: String) {
        if (groupCryptoRepo.getRGroupBytes(gid) == null) {
            L.w { "[GE][GroupKeyDistributor] No R_group for group $gid, skip distribution to $memberUid" }
            return
        }
        L.i { "[GE][GroupKeyDistributor] Enqueue targeted R_group for group $gid to $memberUid" }
        ApplicationDependencies.getJobManager().add(
            pushGroupKeySendJobFactory.create(
                parameters = null,
                gid = gid,
                recipientId = memberUid,
                isBroadcast = false,
            )
        )
    }

    



    fun distributeToMembers(gid: String, memberUids: List<String>) {
        memberUids.forEach { uid ->
            if (uid != globalServices.myId) {
                distributeToMember(gid, uid)
            }
        }
    }
}
