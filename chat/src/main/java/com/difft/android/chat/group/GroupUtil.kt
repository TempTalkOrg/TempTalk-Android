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
import org.difft.app.database.models.GroupMemberContactorModel
import org.difft.app.database.models.GroupModel
import com.difft.android.base.utils.Base64
import com.difft.android.base.utils.globalServices
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupUtil @Inject constructor(
    private val groupRepo: GroupRepo,
    private val messageStore: MessageStore,
    private val wcdb: WCDB,
    private val userManager: UserManager
) {

    private val _singleGroupsUpdate = MutableSharedFlow<GroupModel>(replay = 1, extraBufferCapacity = 64)

    fun emitSingleGroupUpdate(group: GroupModel) {
        _singleGroupsUpdate.tryEmit(group)
        RoomChangeTracker.trackRoom(group.gid, RoomChangeType.GROUP)
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

                    val includeRemarkMap = wcdb.groupMemberContactor.getAllObjects(
                        DBGroupMemberContactorModel.gid.eq(groupID)
                            .and(DBGroupMemberContactorModel.remark.notNull())
                            .and(DBGroupMemberContactorModel.remark.notEq(""))
                    ).associateBy({ it.id }, { it.remark })
                    wcdb.groupMemberContactor.deleteObjects(DBGroupMemberContactorModel.gid.eq(groupID))
                    val members = groupInfo?.members?.map { member ->
                        GroupMemberContactorModel().apply {
                            this.gid = groupID
                            this.id = member.uid
                            this.groupRole = member.role
                            this.displayName = member.displayName
                            this.notification = member.notification
                            this.rapidRole = member.rapidRole
                            this.remark = includeRemarkMap[member.uid]
                            this.useGlobal = member.useGlobal
                        }
                    }
                    if (!members.isNullOrEmpty()) {
                        wcdb.groupMemberContactor.insertObjects(members)
                    }
                    wcdb.group.deleteObjects(DBGroupModel.gid.eq(groupID))
                    wcdb.group.insertObject(group)
                    L.i { "[GroupUtil] [Group: $groupID] fetch success, members: ${members?.size ?: 0}" }
                    if (sendUpdateEvent) {
                        emitSingleGroupUpdate(group)
                    }
                } else {
                    // Group is invalid: clear members, remove room/messages, delete group record
                    L.i { "[GroupUtil] [Group: $groupID] is invalid" }
                    messageStore.removeRoomAndMessages(groupID)
                    wcdb.groupMemberContactor.deleteObjects(DBGroupMemberContactorModel.gid.eq(groupID))
                    L.i { "[GroupUtil] [Group: $groupID] group members cleared" }
                    wcdb.group.deleteObjects(DBGroupModel.gid.eq(groupID))
                    // Always emit update so observers (e.g. GroupsFragment) remove the invalid group
                    emitSingleGroupUpdate(group)
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
