package com.difft.android.chat.group.mvi

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.base.storage.user.CredentialChangeSignal
import com.difft.android.chat.group.GroupWriteSession
import com.difft.android.chat.crypto.GroupMemberBindingSigner
import com.difft.android.chat.crypto.GroupCryptoRepo
import com.difft.android.chat.crypto.GroupKeyDistributor
import com.difft.android.chat.group.GROUP_ROLE_ADMIN
import com.difft.android.chat.group.GROUP_ROLE_MEMBER
import com.difft.android.chat.group.GROUP_ROLE_OWNER
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.jobmanager.QueueAdmission
import com.difft.android.network.group.ChangeGroupSettingsReq
import com.difft.android.network.group.ChangeRolepReq
import com.difft.android.network.group.CreateGroupResp
import com.difft.android.network.group.GroupMemberBinding
import com.difft.android.network.group.GroupRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Named
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** App lifetime owner of accepted membership changes and their independent local follow-ups. */
@Singleton
class GroupMutationRepository @Inject constructor(
    private val groups: GroupRepo,
    private val groupUtil: GroupUtil,
    private val crypto: GroupCryptoRepo,
    private val distributor: GroupKeyDistributor,
    private val store: GroupPageStore,
    private val signer: GroupMemberBindingSigner,
    private val credentials: CredentialChangeSignal,
    @Named("application") private val applicationScope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val pendingByGroup = MutableStateFlow<Map<Triple<String, String, String>, Pending>>(emptyMap())

    fun pendingFor(gid: String, accountId: String): Pending? = credentials.credentialHash.value?.let { pendingByGroup.value[Triple(accountId, it, gid)] }

    private fun remember(outcome: Outcome): Outcome {
        when (outcome) {
            is Outcome.AppliedFollowupFailed -> pendingByGroup.update { it + (Triple(outcome.pending.accountId, outcome.pending.creatorLogin, outcome.pending.gid) to outcome.pending) }
            is Outcome.Applied -> pendingByGroup.update { it - Triple(outcome.pending.accountId, outcome.pending.creatorLogin, outcome.pending.gid) }
            is Outcome.Rejected -> Unit
        }
        return outcome
    }

    sealed interface Change {
        data class Settings(val request: ChangeGroupSettingsReq) : Change
        data class Role(val uid: String, val role: Int) : Change
        data class Members(val ids: List<String>, val add: Boolean) : Change
    }

    data class Pending(
        val gid: String,
        val accountId: String,
        val change: Change,
        val creatorLogin: String,
        val keyJobs: GroupKeyDistributor.PendingMembers?,
        val keyRequired: Boolean,
        val refreshed: Boolean = false,
        /** Fetch-order mark taken when the server accepted the change; the refresh must start after it. */
        val refreshAfter: Long? = null,
    )

    sealed interface Outcome {
        /** [status] is the server status (0 for local rejections); [detail] names rejected members when known. */
        data class Rejected(val reason: String? = null, val status: Int = 0, val detail: String? = null) : Outcome
        data class Applied(val pending: Pending) : Outcome
        data class AppliedFollowupFailed(
            val pending: Pending,
            val refreshPending: Boolean,
            val failedRecipients: Set<String>,
            val stage: String,
        ) : Outcome
    }

    fun submit(gid: String, change: Change): Deferred<Outcome> {
        val account = globalServices.myId
        val loginReady = credentials.isLoaded
        val loadedLogin = if (loginReady) credentials.credentialHash.value else null
        val firstLogin = credentials.firstLoad
        val snapshot = when (change) {
            is Change.Members -> change.copy(ids = change.ids.distinct().toList())
            is Change.Role -> change
            is Change.Settings -> change
        }
        return applicationScope.async(Dispatchers.IO) {
            mutex.withLock {
                val login = if (loginReady) loadedLogin else firstLogin.await()
                if (login == null || pendingFor(gid, account) != null) Outcome.Rejected()
                else remember(apply(gid, GroupWriteSession(account, login), snapshot))
            }
        }
    }

    fun retry(pending: Pending): Deferred<Outcome> = applicationScope.async(Dispatchers.IO) {
        mutex.withLock { remember(finish(pending)) }
    }

    private suspend fun apply(gid: String, session: GroupWriteSession, change: Change): Outcome {
        val account = session.accountId
        try {
            if (!isCurrent(session)) return Outcome.Rejected()
            val group = store.snapshot(gid) ?: return Outcome.Rejected()
            if (group.status != 0) return Outcome.Rejected()
            val self = group.members.firstOrNull { it.uid == account } ?: return Outcome.Rejected()
            val members = group.members.associateBy { it.uid }
            when (change) {
                is Change.Settings -> {
                    if (self.role != GROUP_ROLE_OWNER) return Outcome.Rejected()
                    if (change.request.owner != null &&
                        (change.request.owner == account || change.request.owner !in members)) return Outcome.Rejected()
                }
                is Change.Role -> if (self.role != GROUP_ROLE_OWNER ||
                    members[change.uid]?.role == GROUP_ROLE_OWNER || change.uid !in members
                ) return Outcome.Rejected()
                is Change.Members -> {
                    if (change.ids.isEmpty()) return Outcome.Rejected()
                    if (change.add) {
                        if (group.invitationRule != 2 && self.role > GROUP_ROLE_ADMIN) return Outcome.Rejected()
                        if (change.ids.any { it in members }) return Outcome.Rejected()
                    } else if (change.ids.any { uid ->
                        val target = members[uid]
                        target == null || !(self.role < target.role ||
                            (group.anyoneRemove == true && target.role == GROUP_ROLE_MEMBER && uid != account))
                    }) return Outcome.Rejected()
                }
            }
            val encrypted = group.encrypted
            var keyJobs: GroupKeyDistributor.PendingMembers? = null
            var bindings: List<GroupMemberBinding>? = null
            if (change is Change.Members && change.add && encrypted) {
                // Known encrypted groups must never degrade to unsigned membership requests.
                val key = crypto.getRGroupBytes(gid) ?: return Outcome.Rejected()
                bindings = signer.sign(key, change.ids)
                when (val preparation = distributor.prepareMembers(gid, change.ids, true, session.creatorLogin)) {
                    GroupKeyDistributor.Preparation.KeyUnavailable -> return Outcome.Rejected()
                    GroupKeyDistributor.Preparation.NotRequired -> return Outcome.Rejected()
                    is GroupKeyDistributor.Preparation.Ready -> keyJobs = preparation.pending
                }
            }
            if (!isCurrent(session)) return Outcome.Rejected()
            val response = when (change) {
                is Change.Settings -> groups.changeGroupSettings(gid, change.request)
                is Change.Role -> groups.changeMemberRole(gid, change.uid, ChangeRolepReq(change.role))
                is Change.Members -> if (change.add) groups.addMembersWithBindings(gid, change.ids, bindings)
                    else groups.removeMembers(gid, change.ids)
            }
            if (response.status != 0) {
                val strangers = (response.data as? CreateGroupResp)?.strangers?.mapNotNull { it.name }?.joinToString(", ")
                return Outcome.Rejected(response.reason, response.status, strangers?.takeIf { it.isNotEmpty() })
            }
            L.i { "[GroupMutation] event=accepted action=${change.javaClass.simpleName}" }
            return finish(Pending(gid, account, change, session.creatorLogin, keyJobs, encrypted && change is Change.Members && change.add,
                refreshAfter = groupUtil.fetchMark()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e { "[GroupMutation] event=request_failed ${e.stackTraceToString()}" }
            return Outcome.Rejected()
        }
    }

    private fun isCurrent(session: GroupWriteSession): Boolean =
        session.accountId == globalServices.myId && credentials.credentialHash.value == session.creatorLogin

    private suspend fun finish(pending: Pending): Outcome {
        if (!isCurrent(GroupWriteSession(pending.accountId, pending.creatorLogin))) {
            return Outcome.AppliedFollowupFailed(pending, !pending.refreshed, emptySet(), "account_changed")
        }
        var refreshed = pending.refreshed
        if (!refreshed) {
            try {
                // Only a GET started after acceptance may satisfy the refresh; null is a failed fetch, never subject removal.
                refreshed = groupUtil.fetchAndSaveSingleGroupInfo(pending.gid, true, GroupWriteSession(pending.accountId, pending.creatorLogin),
                    startedAfter = pending.refreshAfter) != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[GroupMutation] event=refresh_pending ${e.stackTraceToString()}" }
            }
        }
        val updated = pending.copy(refreshed = refreshed)
        if (!isCurrent(GroupWriteSession(pending.accountId, pending.creatorLogin))) {
            return Outcome.AppliedFollowupFailed(updated, !refreshed, emptySet(), "account_changed")
        }
        var failed = emptySet<String>()
        var stage = "refresh"
        if (pending.keyRequired) {
            val jobs = pending.keyJobs
            if (jobs == null) {
                return Outcome.AppliedFollowupFailed(updated, !refreshed, emptySet(), "key_unavailable")
            }
            try {
                val admissions = distributor.enqueueMembersPersisted(jobs)
                failed = admissions.filterValues { it !is QueueAdmission.Persisted }.keys
                stage = "key_admission"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = jobs.jobs.keys.filterTo(mutableSetOf()) { jobs.admissions[it] !is QueueAdmission.Persisted }
                stage = "key_admission"
                L.e { "[GroupMutation] event=key_admission_failed ${e.stackTraceToString()}" }
            }
        }
        return if (refreshed && failed.isEmpty()) Outcome.Applied(updated) else {
            L.w { "[GroupMutation] event=followup_pending refresh=$refreshed recipients=${failed.size} stage=$stage" }
            Outcome.AppliedFollowupFailed(updated, !refreshed, failed, stage)
        }
    }
}
