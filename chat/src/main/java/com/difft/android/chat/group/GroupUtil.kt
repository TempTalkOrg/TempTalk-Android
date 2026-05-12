package com.difft.android.chat.group

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import org.difft.app.database.members
import difft.android.messageserialization.MessageStore
import com.difft.android.network.group.GroupAvatarData
import com.difft.android.network.group.GroupAvatarResponse
import com.difft.android.network.group.GroupRepo
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.GroupMemberContactorModel
import org.difft.app.database.models.GroupModel
import com.difft.android.base.utils.Base64
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.crypto.GroupCrypto
import com.difft.android.chat.crypto.GroupCryptoRepo
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupUtil @Inject constructor(
    private val groupRepo: GroupRepo,
    private val messageStore: MessageStore,
    private val wcdb: WCDB,
    private val userManager: UserManager,
    private val groupCryptoRepo: GroupCryptoRepo,
    private val groupMemberWriter: GroupMemberWriter,
) {

    private val _singleGroupsUpdate = MutableSharedFlow<GroupModel>(replay = 1, extraBufferCapacity = 64)

    fun emitSingleGroupUpdate(group: GroupModel) {
        _singleGroupsUpdate.tryEmit(group)
        RoomChangeTracker.trackRoom(group.gid, RoomChangeType.GROUP)
    }

    /**
     * Local cleanup for "user no longer a member of this group" — kicked out,
     * left, dismissed, destroyed, or fetch returned invalid. Removes room +
     * messages, group members, group row, and crypto keys; then emits a
     * non-zero-status sentinel so UI subscribers drop the group.
     *
     * Idempotent. All DB writes run on Dispatchers.IO so callers do not need
     * to dispatch.
     *
     * ⚠️ Caller contract — this method does NOT verify membership. There is
     * no network round-trip and no local self-check, so a misuse silently
     * destroys data (incl. R_group keys that cannot be re-derived). The caller
     * MUST have independently confirmed that the local user is no longer a
     * member of [gid] before invoking. For notify-driven paths see the contract
     * documented on `GroupUpdater.disableGroup`.
     *
     * Marked `internal` so the contract boundary is enforced at compile time —
     * only the chat module's notify-handler / fetch-invalid paths can invoke it.
     */
    internal suspend fun cleanupGroupLocally(gid: String) {
        if (gid.isEmpty()) return
        L.i { "[GroupUtil] cleanupGroupLocally gid=$gid" }
        withContext(Dispatchers.IO) {
            messageStore.removeRoomAndMessages(gid)
            wcdb.groupMemberContactor.deleteObjects(DBGroupMemberContactorModel.gid.eq(gid))
            wcdb.group.deleteObjects(DBGroupModel.gid.eq(gid))
            groupCryptoRepo.deleteKeys(gid)
        }
        // Sentinel: only gid + non-zero status carry meaning here. UI subscribers
        // (GroupsFragment, GroupChatFragment, GroupInfoActivity, ...) already
        // branch on group.status != 0 to remove the group from their views.
        emitSingleGroupUpdate(GroupModel().apply {
            this.gid = gid
            this.status = 1
        })
    }

    /**
     * Detect drift between RoomModel and GroupModel fields. When drift is detected, trigger
     * RoomChangeTracker.GROUP so WCDBUpdateService syncs GroupModel.name/avatar back to
     * RoomModel.roomName/roomAvatarJson.
     *
     * Fallback for the cold-start race where trackRoom(GROUP) is emitted before
     * WCDBUpdateService subscribes and the event is dropped, leaving Room fields stuck on
     * stale values (typically encrypted-group placeholder "🔒 Group").
     *
     * Intentionally invoked only from group chat entry points (not from the shared emit
     * path) so the extra Room query happens only when a user actually opens a group chat.
     */
    suspend fun reconcileRoomIfDrifted(group: GroupModel) {
        val gid = group.gid ?: return
        val drifted = withContext(Dispatchers.IO) {
            val room = wcdb.room.getFirstObject(DBRoomModel.roomId.eq(gid)) ?: return@withContext false
            room.roomName != group.name || room.roomAvatarJson != group.avatar
        }
        if (drifted) {
            L.i { "[GroupUtil] Room/Group drift detected gid=$gid, fixing via trackRoom" }
            RoomChangeTracker.trackRoom(gid, RoomChangeType.GROUP)
        }
    }

    val singleGroupsUpdate: SharedFlow<GroupModel> = _singleGroupsUpdate.asSharedFlow()


    private val _getGroupsStatusUpdate = MutableSharedFlow<Pair<Boolean, List<String>>>(extraBufferCapacity = 64)

    private fun emitGetGroupsStatusUpdate(success: Boolean, ids: List<String>) {
        _getGroupsStatusUpdate.tryEmit(success to ids)
        if (success) {
            ids.forEach {
                RoomChangeTracker.trackRoom(it, RoomChangeType.GROUP)
            }
        }
    }

    val getGroupsStatusUpdate: SharedFlow<Pair<Boolean, List<String>>> = _getGroupsStatusUpdate.asSharedFlow()

    suspend fun syncAllGroupAndAllGroupMembers(forceFetch: Boolean, syncMembers: Boolean) = coroutineScope {
        try {
            if (forceFetch || userManager.getUserData()?.syncedGroupAndMembers == false) {
                val groups = groupRepo.getGroups()
                // Decrypt before persisting so DB stores real names/avatars, not placeholders.
                // R_group for each group may already be in group_crypto_keys (e.g. after
                // a cover install). For plain groups this is a cheap no-op.
                groups.forEach { decryptGroupFieldsIfNeeded(it) }
                wcdb.group.deleteObjects()
                wcdb.group.insertObjects(groups)

                if (syncMembers) {
                    groups.map {
                        async {
                            fetchAndSaveSingleGroupInfo(it.gid)
                        }
                    }.awaitAll()
                }

                userManager.update {
                    this.syncedGroupAndMembers = true
                }
                emitGetGroupsStatusUpdate(true, groups.map { it.gid })
                L.i { "[GroupUtil] syncAllGroupAndAllGroupMembers success" + groups.size }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emitGetGroupsStatusUpdate(false, emptyList())
            L.e(e) { "[GroupUtil] syncAllGroupAndAllGroupMembers fail:" }
        }
    }

    private val groupsInProgress = ConcurrentHashMap.newKeySet<String>()

    suspend fun fetchAndSaveSingleGroupInfo(groupID: String, sendUpdateEvent: Boolean = false): GroupModel? {
        if (!groupsInProgress.add(groupID)) {
            L.i { "[GroupUtil] [Group: $groupID] fetch already in progress, skipping" }
            return null
        }

        return try {
            withContext(Dispatchers.IO) {
                val response = groupRepo
                    .getGroupInfo(groupID)
                val groupInfo = response.data
                val group = wcdb.group.getFirstObject(
                    DBGroupModel.gid.eq(groupID)
                ) ?: GroupModel().apply { gid = groupID }
                group.status = response.status

                if (response.status == 0) {
                    group.name = groupInfo?.name
                    group.messageExpiry = groupInfo?.messageExpiry
                    group.avatar = groupInfo?.avatar
                    group.invitationRule = groupInfo?.invitationRule
                    group.version = groupInfo?.version
                    group.remindCycle = groupInfo?.remindCycle
                    group.anyoneRemove = groupInfo?.anyoneRemove
                    group.rejoin = groupInfo?.rejoin
                    group.publishRule = groupInfo?.publishRule
                    group.linkInviteSwitch = groupInfo?.linkInviteSwitch
                    group.privateChat = groupInfo?.privateChat ?: false
                    group.criticalAlert = groupInfo?.criticalAlert ?: false
                    group.groupCryptoMode = groupInfo?.groupCryptoMode
                    group.encryptedName = groupInfo?.encryptedName
                    group.encryptedAvatar = groupInfo?.encryptedAvatar

                    val members = groupInfo?.members?.map { member ->
                        GroupMemberContactorModel().apply {
                            this.gid = groupID
                            this.id = member.uid
                            this.groupRole = member.role
                            this.displayName = member.displayName
                            this.notification = member.notification
                            this.rapidRole = member.rapidRole
                            this.useGlobal = member.useGlobal
                            this.uidSignature = member.uidSignature
                        }
                    } ?: emptyList()
                    groupMemberWriter.replaceAllForGroup(groupID, members)
                    decryptGroupFieldsIfNeeded(group)
                    wcdb.group.deleteObjects(DBGroupModel.gid.eq(groupID))
                    wcdb.group.insertObject(group)
                    // Background verify of pending (signatureVerify=null) members.
                    // appScope so the caller's IO isn't held; runCatching isolates the failure.
                    appScope.launch {
                        runCatching { groupCryptoRepo.verifyAllPendingForGroup(groupID, groupRepo) }
                            .onFailure { L.e { "[GroupUtil] verify trigger failed gid=$groupID: ${it.stackTraceToString()}" } }
                    }
                    L.i { "[GroupUtil] [Group: $groupID] fetch success, members: ${members.size}" }
                    if (sendUpdateEvent) {
                        emitSingleGroupUpdate(group)
                    }
                } else {
                    L.i { "[GroupUtil] [Group: $groupID] is invalid" }
                    cleanupGroupLocally(groupID)
                }

                groupsInProgress.remove(groupID)
                group
            }
        } catch (e: CancellationException) {
            groupsInProgress.remove(groupID)
            throw e
        } catch (throwable: Throwable) {
            L.e(throwable) { "[GroupUtil] [Group: $groupID] fetchAndSaveSingleGroup fail: ${throwable.message}" }
            groupsInProgress.remove(groupID)
            null
        }
    }

    suspend fun getSingleGroupInfo(gid: String, forceUpdate: Boolean = false): GroupModel? {
        return if (forceUpdate) {
            fetchAndSaveSingleGroupInfo(gid)
        } else {
            val cached = withContext(Dispatchers.IO) {
                wcdb.group.getFirstObject(DBGroupModel.gid.eq(gid))?.takeIf { it.members.isNotEmpty() }
            }
            cached ?: fetchAndSaveSingleGroupInfo(gid)
        }
    }

    private fun getGroupRole(group: GroupModel, memberId: String): Int {
        return wcdb.groupMemberContactor.getFirstObject(DBGroupMemberContactorModel.gid.eq(group.gid).and(DBGroupMemberContactorModel.id.eq(memberId)))?.groupRole ?: GROUP_ROLE_MEMBER
    }

    fun canSpeak(group: GroupModel, memberId: String): Boolean {
        val groupRole = getGroupRole(group, memberId)
        return !(groupRole == GROUP_ROLE_MEMBER && group.publishRule != GroupPublishRole.ALL.rawValue)
    }

    /**
     * Decrypt encrypted group fields (name/avatar) in memory.
     * Must be called from IO thread, before emitting to UI.
     * Does NOT write back to database — only modifies the in-memory GroupModel.
     */
    internal fun decryptGroupFieldsIfNeeded(group: GroupModel) {
        if (group.groupCryptoMode == null || group.groupCryptoMode == 0) return
        val rGroupBytes = groupCryptoRepo.getRGroupBytes(group.gid)
        if (rGroupBytes == null) {
            L.i { "[GE] No key for encrypted group ${group.gid}, showing placeholder" }
            return
        }
        try {
            val kGroup = GroupCrypto.deriveKGroup(rGroupBytes)
            group.encryptedName?.let { group.name = GroupCrypto.decryptGroupName(kGroup, it) ?: group.name }
            if (group.encryptedAvatar != null) {
                group.avatar = GroupCrypto.decryptGroupAvatar(kGroup, group.encryptedAvatar!!) ?: group.avatar
            } else {
                // No encrypted avatar = no custom avatar. Use empty string instead of null
                // because WCDB's Value(null) may skip the column in updateRow.
                group.avatar = ""
            }
            L.i { "[GE] Decrypted fields for group ${group.gid}" }
        } catch (e: Exception) {
            L.e { "[GE] Decrypt fields failed for group ${group.gid}: ${e.message}" }
        }
    }

    companion object {
        fun convert(group: GroupModel): GroupUIData {
            return GroupUIData(
                gid = group.gid ?: "",
                name = group.name,
                messageExpiry = group.messageExpiry,
                avatar = group.avatar,
                status = group.status,
                invitationRule = group.invitationRule,
                version = group.version,
                remindCycle = group.remindCycle,
                anyoneRemove = group.anyoneRemove,
                rejoin = group.rejoin,
                publishRule = group.publishRule,
                linkInviteSwitch = group.linkInviteSwitch ?: false,
                privateChat = group.privateChat ?: false,
                members = group.members
            )
        }
    }
}

/**
 * Get avatar data for display, respecting encryption state.
 * - Plain group: returns avatar data from [avatar] field (same as before).
 * - Encrypted group: returns avatar data ONLY if [encryptedAvatar] is present
 *   (meaning the group has a custom avatar that was encrypted). If [encryptedAvatar]
 *   is null, the group has no custom avatar — returns null so UI shows default icon.
 */
fun GroupModel.getDisplayAvatarData(): GroupAvatarData? {
    if ((groupCryptoMode ?: 0) > 0 && encryptedAvatar == null) return null
    return avatar?.getAvatarData()
}

fun String.getAvatarData(): GroupAvatarData? {
    return try {
        Gson().fromJson(this, GroupAvatarResponse::class.java)?.data?.let {
            val avatarData = String(Base64.decode(it))
            Gson().fromJson(avatarData, GroupAvatarData::class.java)
        }
    } catch (e: Exception) {
        L.e(e) { "[group] parse avatar data fail: $this ===" }
        null
    }
}
