package com.difft.android.chat.group

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.application
import com.difft.android.base.utils.globalServices
import org.difft.app.database.members
import org.difft.app.database.wcdb
import difft.android.messageserialization.MessageStore
import com.difft.android.network.group.GroupAvatarData
import com.difft.android.network.group.GroupAvatarResponse
import com.difft.android.network.group.GroupRepo
import com.google.gson.Gson
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.rx3.awaitSingle
import org.difft.app.database.WCDB
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.GroupMemberContactorModel
import org.difft.app.database.models.GroupModel
import org.thoughtcrime.securesms.util.Base64
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

object GroupUtil {

    private val mSingleGroupUpdateSubject = BehaviorSubject.create<GroupModel>()

    fun emitSingleGroupUpdate(group: GroupModel) {
        mSingleGroupUpdateSubject.onNext(group)
        RoomChangeTracker.trackRoom(group.gid, RoomChangeType.GROUP)
    }

    val singleGroupsUpdate: Observable<GroupModel> = mSingleGroupUpdateSubject


    private val mGetGroupsStatusSubject = PublishSubject.create<Pair<Boolean, List<String>>>()

    private fun emitGetGroupsStatusUpdate(success: Boolean, ids: List<String>) {
        mGetGroupsStatusSubject.onNext(success to ids)
        if (success) {
            ids.forEach {
                RoomChangeTracker.trackRoom(it, RoomChangeType.GROUP)
            }
        }
    }

    val getGroupsStatusUpdate: Observable<Pair<Boolean, List<String>>> = mGetGroupsStatusSubject

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        val groupRepo: GroupRepo
        val messageStore: MessageStore
    }

    suspend fun syncAllGroupAndAllGroupMembers(context: Context, forceFetch: Boolean, syncMembers: Boolean) = coroutineScope {
        try {
            if (forceFetch || globalServices.userManager.getUserData()?.syncedGroupAndMembers == false) {
                val groupRepo = EntryPointAccessors.fromApplication<EntryPoint>(application).groupRepo
                val groups = groupRepo.getGroups().await()
                wcdb.group.deleteObjects()
                wcdb.group.insertObjects(groups)

                if (syncMembers) {
                    groups.map {
                        async {
                            fetchAndSaveSingleGroupInfo(context, it.gid).awaitSingle()
                        }
                    }.awaitAll()
                }

                globalServices.userManager.update {
                    this.syncedGroupAndMembers = true
                }
                emitGetGroupsStatusUpdate(true, groups.map { it.gid })
                L.i { "[GroupUtil] syncAllGroupAndAllGroupMembers success" + groups.size }
            }
        } catch (e: Exception) {
            emitGetGroupsStatusUpdate(false, emptyList())
            e.printStackTrace()
            L.e { "[GroupUtil] syncAllGroupAndAllGroupMembers fail:" + e.stackTraceToString() }
        }
    }

    private val groupUpdateStatus = ConcurrentHashMap<String, Boolean>()

    fun fetchAndSaveSingleGroupInfo(context: Context, groupID: String, sendUpdateEvent: Boolean = false): Observable<Optional<GroupModel>> {
        if (groupUpdateStatus[groupID] != true) {
            groupUpdateStatus[groupID] = true
        } else {
            L.i { "[GroupUtil] [Group: $groupID] update group member from server already in progress, ignore this update event" }
            return Observable.just(Optional.empty())
        }

        return EntryPointAccessors.fromApplication<EntryPoint>(context)
            .groupRepo
            .getGroupInfo(groupID)
            .toObservable()
            .concatMap { response ->
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
                } else {
                    //群失效了，需要删除会话和消息
                    L.i { "[GroupUtil] [Group: $groupID] is invalid" }
                    val messageStore = EntryPointAccessors.fromApplication<EntryPoint>(application).messageStore
                    messageStore.removeRoomAndMessages(groupID)
                }
                wcdb.group.deleteObjects(DBGroupModel.gid.eq(groupID))
                wcdb.group.insertObject(group)
                if (sendUpdateEvent) {
                    emitSingleGroupUpdate(group)
                }
                groupUpdateStatus[groupID] = false
                Observable.just(Optional.ofNullable(group))
            }
            .onErrorResumeNext { throwable ->
                L.e { "[GroupUtil] fetchAndSaveSingleGroup fail:" + throwable.stackTraceToString() }
                groupUpdateStatus[groupID] = false
                Observable.just(Optional.empty())
            }
    }

    fun getSingleGroupInfo(context: Context, gid: String, forceUpdate: Boolean = false): Observable<Optional<GroupModel>> {
        return if (forceUpdate) {
            fetchAndSaveSingleGroupInfo(context, gid)
        } else {
            Observable.defer {
                val group = wcdb.group.getFirstObject(DBGroupModel.gid.eq(gid))?.takeIf { it.members.isNotEmpty() }
                if (group != null) {
                    Observable.just(Optional.of(group))
                } else {
                    fetchAndSaveSingleGroupInfo(context, gid)
                }
            }
                .subscribeOn(Schedulers.io())
        }
    }

    fun getGroupRole(wcdb: WCDB, group: GroupModel, memberId: String): Int {
        return wcdb.groupMemberContactor.getFirstObject(DBGroupMemberContactorModel.gid.eq(group.gid).and(DBGroupMemberContactorModel.id.eq(memberId)))?.groupRole ?: GROUP_ROLE_MEMBER
    }


    fun canSpeak(group: GroupModel, memberId: String): Boolean {
        val groupRole = getGroupRole(wcdb, group, memberId)
        return !(groupRole == GROUP_ROLE_MEMBER && group.publishRule != GroupPublishRole.ALL.rawValue)
    }

    fun convert(group: GroupModel): GroupUIData {
        // Convert the GroupModel to GroupUIData, now including the retrieved members
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

fun String.getAvatarData(): GroupAvatarData? {
    return try {
        Gson().fromJson(this, GroupAvatarResponse::class.java)?.data?.let {
            val avatarData = String(Base64.decode(it))
//            L.d { "[group] avatar json Data : $avatarData" }
            Gson().fromJson(avatarData, GroupAvatarData::class.java)
        }
    } catch (e: Exception) {
        L.e { "[group] parse avatar data fail: $this === ${e.stackTraceToString()}" }
        null
    }
}