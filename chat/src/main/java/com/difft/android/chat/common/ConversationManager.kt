package com.difft.android.chat.common

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.group.GroupUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.websocket.api.ConversationManager
import com.difft.android.websocket.api.PublicKeyUpdateResult
import com.difft.android.websocket.api.messages.GetPublicKeysReq
import com.difft.android.websocket.api.messages.PublicKeyInfo
import difft.android.messageserialization.For
import difft.android.messageserialization.PublicKeyInfoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.members
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.PublicKeyInfoModel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationManagerImpl @Inject constructor(
    @param:ChativeHttpClientModule.Chat
    private val chatHttpClient: ChativeHttpClient,
    private val publicKeyInfoStore: PublicKeyInfoStore,
    private val groupUtil: GroupUtil,
    private val wcdb: WCDB,
) : ConversationManager {

    /**
     * For → uids resolution — SUSPEND variant.
     * - For.Account(peer) → [peer, selfUid]
     * - For.Group(gid)    → current group-member uids (NO self append —
     *   preserves pre-refactor behavior). Delegates to
     *   [GroupUtil.getSingleGroupInfo] which can hit the network if the
     *   local cache is empty. Used by [updatePublicKeyInfoData] /
     *   [getPublicKeyInfos] which run in proper suspend context.
     * Returns emptyList() if group info missing.
     */
    private suspend fun resolveUids(room: For): List<String> = when (room) {
        is For.Account -> listOf(room.id, globalServices.myId)
        is For.Group -> groupUtil.getSingleGroupInfo(room.id)
            ?.members
            ?.mapNotNull { it.id }
            ?: emptyList()
    }

    /**
     * For → uids resolution — LOCAL-ONLY variant.
     * Used EXCLUSIVELY by [hasPublicKeyInfoData] which MUST NOT hit the
     * network (the has-check is a cache-presence probe, not a source-of-truth
     * refresh). Reads the local group row via `wcdb.group.getFirstObject(...)`
     * without ever calling `fetchAndSaveSingleGroupInfo` (the network fallback
     * in `GroupUtil.getSingleGroupInfo`).
     *
     * Rationale: a stale-but-local member list is safe for the has-check
     * because on a cache miss the caller always follows up with
     * [updatePublicKeyInfoData] which DOES run the full suspend resolver
     * with network fallback.
     *
     * - For.Account(peer) → [peer, selfUid]
     * - For.Group(gid)    → local members list, or emptyList() if the
     *   group row is absent locally (treated as cache miss by caller).
     */
    private fun resolveUidsLocalOnly(room: For): List<String> = when (room) {
        is For.Account -> listOf(room.id, globalServices.myId)
        is For.Group -> wcdb.group
            .getFirstObject(DBGroupModel.gid.eq(room.id))
            ?.members
            ?.mapNotNull { it.id }
            ?: emptyList()
    }

    override suspend fun hasPublicKeyInfoData(room: For): Boolean = withContext(Dispatchers.IO) {
        // WCDB reads here (resolveUidsLocalOnly → wcdb.group.getFirstObject and
        // publicKeyInfoStore.hasAllUids → wcdb.publicKeyInfo.getValue) are blocking —
        // move off the default CPU pool to avoid starving it under concurrent sends.
        val uids = resolveUidsLocalOnly(room)  // local only, no network
        if (uids.isEmpty()) {
            L.i { "[ConversationManager] hasPkinfo room=${room.id} uids=0 result=false reason=empty_local_resolution" }
            return@withContext false
        }
        val cached = publicKeyInfoStore.hasAllUids(uids)
        L.i { "[ConversationManager] hasPkinfo room=${room.id} uids=${uids.size} result=$cached" }
        cached
    }

    override suspend fun updatePublicKeyInfoData(room: For): Boolean {
        // Resolve then delegate to the uid-keyed core impl.
        val uids = resolveUids(room)
        return updatePublicKeyInfoData(uids)
    }

    override suspend fun updatePublicKeyInfoData(uids: List<String>): Boolean {
        if (uids.isEmpty()) return true  // vacuous

        val serverKeys: List<PublicKeyInfo>? = try {
            chatHttpClient.httpService
                .getPublicKeys((globalServices.userManager.getUserData()?.microToken ?: ""), GetPublicKeysReq(uids))
                .data
                ?.keys
        } catch (e: Exception) {
            L.e { "[ConversationManager] getPublicKeys failed uids=${uids.size} err=${e.stackTraceToString()}" }
            null
        }

        if (serverKeys.isNullOrEmpty()) {
            L.w { "[ConversationManager] getPublicKeys empty uids=${uids.size}" }
            return false
        }

        val models = serverKeys.map { wire -> wire.toModel() }
        publicKeyInfoStore.upsert(models)
        L.i { "[ConversationManager] update uids=${uids.size} persisted=${models.size}" }
        return true
    }

    /**
     * Resolves For → uids and additionally reports whether the group is confirmed invalid
     * (used to distinguish permanent vs transient failures, issue #970 ②).
     * - [UidResolution.Resolved]: always for For.Account; For.Group fetch succeeded (status==0).
     * - [UidResolution.Invalid]: For.Group `status != 0` (server confirms invalid) → permanent.
     * - [UidResolution.Unresolved]: For.Group `group == null` → transient. `group == null` has **two sources**:
     *   (a) the fetch inside `fetchAndSaveSingleGroupInfo` threw and returned null (weak network/timeout);
     *   (b) the `groupsInProgress` concurrency guard: a second concurrent call for the same gid returns null (skipped).
     *   Both are transient — under concurrency this round is skipped, and next round each job resolves
     *   status!=0 → permanent exit (ARCH-CRIT-1).
     *
     * `status == null` (rare in theory: an old cached group without status) is conservatively treated
     * as Resolved and proceeds to the fetch, not Invalid (only a **definite** status!=0 is permanent).
     */
    private sealed interface UidResolution {
        data class Resolved(val uids: List<String>) : UidResolution
        data object Invalid : UidResolution
        data object Unresolved : UidResolution
    }

    private suspend fun resolveUidsWithStatus(room: For): UidResolution = when (room) {
        is For.Account -> UidResolution.Resolved(listOf(room.id, globalServices.myId))
        is For.Group -> {
            val group = groupUtil.getSingleGroupInfo(room.id)  // local miss falls back to network
            when {
                group == null -> UidResolution.Unresolved          // fetch threw / guard skipped → transient
                (group.status ?: 0) != 0 -> UidResolution.Invalid  // server confirms invalid → permanent
                else -> withContext(Dispatchers.IO) {              // members is a WCDB read
                    UidResolution.Resolved(group.members.mapNotNull { it.id })
                }
            }
        }
    }

    override suspend fun updatePublicKeyInfoDataResult(room: For): PublicKeyUpdateResult {
        // 1) Resolve uids, also obtaining the "is the group confirmed invalid" signal
        when (val resolution = resolveUidsWithStatus(room)) {
            is UidResolution.Invalid -> {
                L.w { "[ConversationManager] updateResult room=${room.id} EntityInvalid (group status!=0)" }
                return PublicKeyUpdateResult.EntityInvalid
            }

            is UidResolution.Unresolved -> {
                L.w { "[ConversationManager] updateResult room=${room.id} Unresolved (group==null, transient)" }
                return PublicKeyUpdateResult.Unresolved
            }

            is UidResolution.Resolved -> {
                val uids = resolution.uids
                if (uids.isEmpty()) {
                    // For.Account is always non-empty; a valid group with 0 members = nobody to send to → ServerEmpty
                    L.w { "[ConversationManager] updateResult room=${room.id} ServerEmpty (resolved 0 uids)" }
                    return PublicKeyUpdateResult.ServerEmpty
                }

                // 2) Fetch keys, distinguishing null (network failure/body error) from empty (server says none)
                val serverKeys: List<PublicKeyInfo>? = try {
                    chatHttpClient.httpService
                        .getPublicKeys((globalServices.userManager.getUserData()?.microToken ?: ""), GetPublicKeysReq(uids))
                        .data
                        ?.keys
                } catch (e: Exception) {
                    L.e { "[ConversationManager] updateResult getPublicKeys failed uids=${uids.size} err=${e.stackTraceToString()}" }
                    return PublicKeyUpdateResult.FetchFailed  // no connection → transient
                }

                return when {
                    serverKeys == null -> {
                        // .data?.keys==null means HTTP body parse error/missing field = no definite answer → conservatively transient
                        L.w { "[ConversationManager] updateResult room=${room.id} FetchFailed (server body null)" }
                        PublicKeyUpdateResult.FetchFailed
                    }

                    serverKeys.isEmpty() -> {
                        // Definite empty array = server says none → ServerEmpty (transient downstream)
                        L.w { "[ConversationManager] updateResult room=${room.id} ServerEmpty uids=${uids.size}" }
                        PublicKeyUpdateResult.ServerEmpty
                    }

                    else -> {
                        publicKeyInfoStore.upsert(serverKeys.map { it.toModel() })
                        L.i { "[ConversationManager] updateResult room=${room.id} Updated uids=${uids.size} persisted=${serverKeys.size}" }
                        PublicKeyUpdateResult.Updated
                    }
                }
            }
        }
    }

    override suspend fun classifyEmptyKeys(room: For): PublicKeyUpdateResult =
        // Reuse the confirming fetch: same path for Group and Account (no special Account case).
        // The caller (createNewOutgoingPushMessage :442) maps it: EntityInvalid → permanent;
        // ServerEmpty/FetchFailed/Unresolved/Updated → transient (ServerEmpty's empty array is
        // ambiguous, so retry conservatively; Updated means a key was re-fetched, retry self-heals).
        // See the PublicKeyUpdateResult KDoc (PR #973 code-review).
        updatePublicKeyInfoDataResult(room)

    override suspend fun getPublicKeyInfos(room: For): List<PublicKeyInfo> {
        val uids = resolveUids(room)
        if (uids.isEmpty()) {
            L.w { "[ConversationManager] getPkinfos room=${room.id} uids=0 (empty resolution)" }
            return emptyList()
        }

        val byUid = publicKeyInfoStore.getForUids(uids)
        // Preserve request-uid order. Missing uids absent (no null placeholders).
        val result = uids.mapNotNull { uid -> byUid[uid]?.toWire() }
        L.i { "[ConversationManager] getPkinfos room=${room.id} requested=${uids.size} found=${result.size}" }
        return result
    }

    override suspend fun updateConversationMemberData(room: For) {
        // UNCHANGED — group-metadata refresh, NOT pkinfo-related.
        if (room is For.Group) {
            groupUtil.fetchAndSaveSingleGroupInfo(room.id, true)
        }
    }

    override suspend fun getPublicKeyInfos(ids: List<String>?): List<PublicKeyInfo>? {
        // UNCHANGED — bypass-cache direct server fetch.
        if (ids.isNullOrEmpty()) return null
        return try {
            chatHttpClient.httpService
                .getPublicKeys((globalServices.userManager.getUserData()?.microToken ?: ""), GetPublicKeysReq(ids))
                .data
                ?.keys
        } catch (e: Exception) {
            L.e { "[ConversationManager] getPublicKeys direct failed ids=${ids.size} err=${e.stackTraceToString()}" }
            null
        }
    }

    // ---- wire ↔ model mapping (private) --------------------------------

    private fun PublicKeyInfo.toModel(): PublicKeyInfoModel =
        PublicKeyInfoModel().also {
            it.uid = this.uid
            it.identityKey = this.identityKey
            it.registrationId = this.registrationId
            it.resetIdentityKeyTime = this.resetIdentityKeyTime
        }

    private fun PublicKeyInfoModel.toWire(): PublicKeyInfo =
        PublicKeyInfo(
            uid = this.uid,
            identityKey = this.identityKey,
            registrationId = this.registrationId,
            resetIdentityKeyTime = this.resetIdentityKeyTime,
        )
}
