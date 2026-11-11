package com.difft.android.chat.contacts.groupcard.mvi

import com.difft.android.base.utils.globalServices
import com.difft.android.chat.group.GROUP_ROLE_MEMBER
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.group.groupDissolveDaysLeft
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import org.difft.app.database.groupMemberCounts
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.GroupModel
import org.difft.app.database.wcdb
import com.difft.android.chat.group.canInviteGroupMembers

class GroupCardDataSource @Inject constructor(private val groupUtil: GroupUtil) {
    data class Snapshot(
        val group: GroupModel?,
        val memberCount: Int = 0,
        val selfRole: Int = GROUP_ROLE_MEMBER,
        val canSpeak: Boolean = false,
    )

    fun changes(gid: String): Flow<Unit> = merge(
        groupUtil.singleGroupsUpdate.filter { it.gid == gid }.map { Unit },
        groupUtil.getGroupsStatusUpdate.filter { it.first }.map { Unit },
    )

    suspend fun snapshot(gid: String, refresh: Boolean): Snapshot = withContext(Dispatchers.IO) {
        if (gid.isBlank()) return@withContext Snapshot(null)
        
        val fetched = if (refresh) groupUtil.fetchAndSaveSingleGroupInfo(gid, sendUpdateEvent = true) else null
        val group = fetched ?: wcdb.group.getFirstObject(DBGroupModel.gid.eq(gid))
        if (group == null || group.status != 0) return@withContext Snapshot(group)
        groupUtil.decryptGroupFieldsIfNeeded(group)
        
        val self = wcdb.groupMemberContactor.getFirstObject(
            DBGroupMemberContactorModel.gid.eq(gid).and(DBGroupMemberContactorModel.id.eq(globalServices.myId)))
        Snapshot(group, wcdb.groupMemberCounts(listOf(gid))[gid] ?: 0,
            self?.groupRole ?: GROUP_ROLE_MEMBER,
            groupUtil.canSpeak(group, globalServices.myId))
    }
}


internal fun mapGroupCardState(
    groupId: String,
    snapshot: GroupCardDataSource.Snapshot,
    now: Long,
): GroupCardContract.State {
    val group = snapshot.group
    val available = group?.status == 0 && group.gid == groupId
    return GroupCardContract.State(
        groupId = groupId,
        group = group,
        name = group?.name.orEmpty(),
        memberCount = snapshot.memberCount,
        messageExpirySeconds = group?.messageExpiry?.toLong()?.coerceAtLeast(0) ?: 0,
        dissolveDays = group?.expireTime?.takeIf { available && it > 0 }
            ?.let { groupDissolveDaysLeft(it, now) },
        actionsEnabled = available,
        inviteEnabled = available && canInviteGroupMembers(snapshot.selfRole, group?.invitationRule),
        callAllowed = available && snapshot.canSpeak,
        loading = false,
    )
}
