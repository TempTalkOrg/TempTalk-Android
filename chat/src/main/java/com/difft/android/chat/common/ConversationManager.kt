package com.difft.android.chat.common

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.group.GroupUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.websocket.api.ConversationManager
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
            ?.map { it.id }
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
            ?.map { it.id }
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
