package com.difft.android.chat.group.create

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.user.CredentialChangeSignal
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.crypto.GroupCryptoRepo
import com.difft.android.chat.crypto.GroupKeyDistributor
import com.difft.android.chat.group.GroupAvatarUploader
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.group.GroupWriteSession
import com.difft.android.chat.jobmanager.QueueAdmission
import com.difft.android.network.NetworkException
import com.difft.android.network.group.GroupRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException








@Singleton
class CreateGroupOperation @Inject constructor(
    private val groups: GroupRepo,
    private val groupUtil: GroupUtil,
    private val crypto: GroupCryptoRepo,
    private val distributor: GroupKeyDistributor,
    private val avatarUploader: GroupAvatarUploader,
    private val requests: CreateGroupRequestBuilder,
    private val credentials: CredentialChangeSignal,
    @Named("application") private val applicationScope: CoroutineScope,
) {
    data class Draft(
        val name: String,
        
        val memberIds: List<String>,
        val avatarPath: String?,
        val encrypted: Boolean,
    )

    sealed interface Outcome {
        data class Created(val gid: String) : Outcome
        
        data class CreatedFollowupFailed(val gid: String, val stage: String) : Outcome
        




        data class Rejected(val status: Int = 0, val reason: String? = null, val strangers: String? = null, val unknown: Boolean = false) : Outcome
    }

    private class Accepted(val gid: String, val rGroup: ByteArray?, val session: GroupWriteSession, var avatarPath: String?) {
        
        @Volatile var keySaved = false

        
        @Volatile var broadcastQueued = false
    }

    private val accepted = HashMap<String, Accepted>()

    
    fun currentSession(): GroupWriteSession? =
        credentials.credentialHash.value?.let { GroupWriteSession(globalServices.myId, it) }

    fun submit(draft: Draft, session: GroupWriteSession): Deferred<Outcome> = applicationScope.async(Dispatchers.IO) {
        val outcome = create(draft, session)
        L.i { "[CreateGroup] result=${outcome.javaClass.simpleName} members=${draft.memberIds.size} encrypted=${draft.encrypted}" }
        outcome
    }

    
    fun retryFollowups(gid: String): Deferred<Outcome> = applicationScope.async(Dispatchers.IO) {
        val entry = synchronized(accepted) { accepted[gid] } ?: return@async Outcome.CreatedFollowupFailed(gid, "unknown")
        finish(entry)
    }

    private suspend fun create(draft: Draft, session: GroupWriteSession): Outcome {
        try {
            if (!isCurrent(session)) return Outcome.Rejected()
            val avatarJson = draft.avatarPath?.let { avatarUploader.uploadAndBuildJson(it) }
            val built = requests.build(draft, avatarJson)
            if (!isCurrent(session)) return Outcome.Rejected()
            val response = try {
                groups.createGroup(built.request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                
                L.e { "[CreateGroup] request_unanswered ${e.stackTraceToString()}" }
                return Outcome.Rejected(reason = (e as? NetworkException)?.errorMsg, unknown = true)
            }
            if (response.status != 0) {
                val strangers = response.data?.strangers?.mapNotNull { it.name }?.joinToString(", ")?.takeIf { it.isNotEmpty() }
                L.w { "[CreateGroup] rejected status=${response.status}" }
                return Outcome.Rejected(response.status, response.reason, strangers)
            }
            val gid = response.data?.gid?.takeIf { it.isNotBlank() } ?: return Outcome.Rejected(reason = response.reason)
            val entry = Accepted(gid, built.rGroup, session, draft.avatarPath)
            synchronized(accepted) { accepted[gid] = entry }
            return finish(entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e { "[CreateGroup] request_failed ${e.stackTraceToString()}" }
            return Outcome.Rejected(reason = (e as? NetworkException)?.errorMsg)
        }
    }

    
    private suspend fun finish(entry: Accepted): Outcome {
        val gid = entry.gid
        if (!isCurrent(entry.session)) return Outcome.CreatedFollowupFailed(gid, "account_changed")
        val stage = runCatching {
            if (entry.rGroup != null && !entry.keySaved) {
                
                entry.keySaved = crypto.saveRGroupIfNeeded(gid, entry.rGroup) || crypto.getRGroupBytes(gid) != null
                if (!entry.keySaved) return@runCatching "key_save"
            }
            val fetched = groupUtil.fetchAndSaveSingleGroupInfo(gid, true, entry.session) != null
            
            if (entry.rGroup != null && !entry.broadcastQueued) {
                entry.broadcastQueued = distributor.distributeToGroupPersisted(gid, entry.session.creatorLogin) is QueueAdmission.Persisted
                if (!entry.broadcastQueued) return@runCatching "key_admission"
            }
            if (fetched) null else "fetch"
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            L.e { "[CreateGroup] followup_failed ${e.stackTraceToString()}" }
            "exception"
        }
        if (stage != null) {
            L.w { "[CreateGroup] followup_pending gid=$gid stage=$stage" }
            return Outcome.CreatedFollowupFailed(gid, stage)
        }
        synchronized(accepted) { accepted.remove(gid) }
        deleteDraft(entry.avatarPath)
        return Outcome.Created(gid)
    }

    
    fun release(gid: String) {
        val entry = synchronized(accepted) { accepted.remove(gid) } ?: return
        deleteDraft(entry.avatarPath)
    }

    private fun deleteDraft(path: String?) {
        path?.let { runCatching { File(it).takeIf { f -> f.exists() }?.delete() } }
    }

    private fun isCurrent(session: GroupWriteSession): Boolean =
        session.accountId == globalServices.myId && credentials.credentialHash.value == session.creatorLogin
}
