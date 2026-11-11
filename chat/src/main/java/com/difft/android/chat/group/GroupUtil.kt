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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first

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
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton


@Suppress("BlockingWcdbInSuspend")
@Singleton
class GroupUtil @Inject constructor(
    private val groupRepo: GroupRepo,
    private val messageStore: MessageStore,
    private val wcdb: WCDB,
    private val userManager: UserManager,
    private val groupCryptoRepo: GroupCryptoRepo,
    private val groupMemberWriter: GroupMemberWriter,
    private val groupExpiryTracker: GroupExpiryTracker,
) {

    private val _singleGroupsUpdate = MutableSharedFlow<GroupModel>(replay = 1, extraBufferCapacity = 64)

    fun emitSingleGroupUpdate(group: GroupModel) {
        _singleGroupsUpdate.tryEmit(group)
        RoomChangeTracker.trackRoom(group.gid ?: "", RoomChangeType.GROUP)
    }

    
    private fun writeClearedDeadline(gid: String): GroupModel? {
        val group = wcdb.group.getFirstObject(DBGroupModel.gid.eq(gid)) ?: return null
        if (group.expireTime == null) return group
        group.expireTime = null
        wcdb.group.updateObject(group, DBGroupModel.expireTime, DBGroupModel.gid.eq(gid))
        decryptGroupFieldsIfNeeded(group)
        emitSingleGroupUpdate(group)
        return group
    }

    
    private fun storedDeadline(gid: String): Long? =
        wcdb.group.prepareSelect().select(DBGroupModel.gid, DBGroupModel.expireTime).where(DBGroupModel.gid.eq(gid))
            .firstObject()?.expireTime

    suspend fun clearExpiry(gid: String, reason: String) = withContext(Dispatchers.IO) {
        try {
            groupExpiryTracker.clearExpiry(
                gid = gid,
                reason = reason,
                load = { wcdb.group.getFirstObject(DBGroupModel.gid.eq(gid)) },
                persist = { group -> wcdb.group.updateObject(group, DBGroupModel.expireTime, DBGroupModel.gid.eq(gid)) },
                emit = ::emitSingleGroupUpdate,
                
                hasDeadline = {
                    wcdb.group.prepareSelect().select(DBGroupModel.expireTime).where(DBGroupModel.gid.eq(gid))
                        .firstObject()?.expireTime != null
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            
            L.e { "[GroupExpiry] clear_failed gid=$gid reason=$reason error=${e.stackTraceToString()}" }
        }
    }

    


















    internal suspend fun cleanupGroupLocally(gid: String, expectedSession: GroupWriteSession? = null) {
        if (gid.isEmpty()) return
        L.i { "[GroupUtil] cleanupGroupLocally gid=$gid" }
        withContext(Dispatchers.IO) {
            check(expectedSession?.isCurrent(userManager) != false) { "group_session_changed" }
            messageStore.removeRoomAndMessages(gid, expectedSession?.let { session -> { session.isCurrent(userManager) } })
            check(expectedSession?.isCurrent(userManager) != false) { "group_session_changed" }
            wcdb.groupMemberContactor.deleteObjects(DBGroupMemberContactorModel.gid.eq(gid))
            wcdb.group.deleteObjects(DBGroupModel.gid.eq(gid))
            groupCryptoRepo.deleteKeys(gid)
        }
        
        
        
        check(expectedSession?.isCurrent(userManager) != false) { "group_session_changed" }
        emitSingleGroupUpdate(GroupModel().apply {
            this.gid = gid
            this.status = 1
        })
    }

    











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

    
    private class RunningSync(val fetchesMembers: Boolean)

    




    private val runningSync = MutableStateFlow<RunningSync?>(null)

    
    private val listDeadlinesInFlight = AtomicBoolean(false)

    
    @Volatile private var lastListFetchAt: Long? = null

    
    @VisibleForTesting
    internal var listClock: () -> Long = SystemClock::elapsedRealtimeNanos

    






    suspend fun syncGroupListIfStale() {
        val last = lastListFetchAt
        if (last != null && listClock() - last < GROUP_LIST_SYNC_INTERVAL_NS) return
        if (!listDeadlinesInFlight.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.IO) {
                val requestStartedAt = groupExpiryTracker.requestStartedAt()
                val fetchStartedAt = listClock()
                val groups = groupRepo.getGroups()
                
                
                groupExpiryTracker.persistResponse(groups, requestStartedAt, clearWritten = { gid -> writeClearedDeadline(gid) }) {
                    writeDeadlines(groups).forEach { gid -> announceStoredGroup(gid) }
                }
                lastListFetchAt = fetchStartedAt
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[GroupUtil] group_list_deadlines_failed error=${e.stackTraceToString()}" }
        } finally {
            listDeadlinesInFlight.set(false)
        }
    }

    
    private fun storedDeadlines(): Map<String?, Long?> =
        wcdb.group.prepareSelect().select(DBGroupModel.gid, DBGroupModel.expireTime).allObjects()
            .associate { it.gid to it.expireTime }

    
    @VisibleForTesting
    internal var writeDeadlines: (List<GroupModel>) -> List<String> = { groups ->
        val stored = storedDeadlines()
        val updates = groups.filter { it.gid in stored && stored[it.gid] != it.expireTime }
        updates.forEach { group -> wcdb.group.updateObject(group, DBGroupModel.expireTime, DBGroupModel.gid.eq(group.gid)) }
        updates.mapNotNull { it.gid }
    }

    
    @VisibleForTesting
    internal var announceStoredGroup: (String) -> Unit = { gid ->
        wcdb.group.getFirstObject(DBGroupModel.gid.eq(gid))?.let { group ->
            decryptGroupFieldsIfNeeded(group)
            emitSingleGroupUpdate(group)
        }
    }

    



    suspend fun refreshAllNow() {
        repeat(REFRESH_ATTEMPTS) {
            val running = runningSync.value
            if (running != null) {
                runningSync.first { it == null }
                if (running.fetchesMembers) return
            } else if (syncAllGroupAndAllGroupMembers(forceFetch = true)) {
                return
            }
        }
    }

    



    suspend fun syncAllGroupAndAllGroupMembers(forceFetch: Boolean): Boolean = coroutineScope {
        val fetches = forceFetch || userManager.getUserData()?.syncedGroupAndMembers == false
        if (!runningSync.compareAndSet(null, RunningSync(fetches))) {
            L.i { "[GroupUtil] syncAllGroupAndAllGroupMembers already in flight, skipping forceFetch=$forceFetch" }
            return@coroutineScope false
        }
        try {
            if (fetches) {
                val requestStartedAt = groupExpiryTracker.requestStartedAt()
                val fetchStartedAt = listClock()
                val groups = groupRepo.getGroups()
                
                
                
                groups.forEach { decryptGroupFieldsIfNeeded(it) }
                groupExpiryTracker.persistResponse(groups, requestStartedAt, clearWritten = { gid -> writeClearedDeadline(gid) }) {
                    val before = storedDeadlines()
                    wcdb.group.deleteObjects()
                    wcdb.group.insertObjects(groups)
                    
                    groups.filter { before[it.gid] != it.expireTime }.forEach { emitSingleGroupUpdate(it) }
                }

                groups.map {
                    async {
                        fetchAndSaveSingleGroupInfo(it.gid ?: "")
                    }
                }.awaitAll()
                userManager.update { this.syncedGroupAndMembers = true }
                lastListFetchAt = fetchStartedAt
                emitGetGroupsStatusUpdate(true, groups.mapNotNull { it.gid })
                L.i { "[GroupUtil] syncAllGroupAndAllGroupMembers success" + groups.size }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emitGetGroupsStatusUpdate(false, emptyList())
            L.e(e) { "[GroupUtil] syncAllGroupAndAllGroupMembers fail:" }
        } finally {
            runningSync.value = null
        }
        true
    }

    private val singleGroupFetches = SingleFlight<String, GroupModel>()

    
    fun fetchMark(): Long = singleGroupFetches.mark()

    





    suspend fun fetchAndSaveSingleGroupInfo(
        groupID: String,
        sendUpdateEvent: Boolean = false,
        expectedSession: GroupWriteSession? = null,
        startedAfter: Long? = null,
    ): GroupModel? {
        var joined = false
        var leaderEmits = false
        val group = singleGroupFetches.run(groupID, startedAfter, tag = sendUpdateEvent,
            onJoin = { leaderTag -> joined = true; leaderEmits = leaderTag == true }) {
            fetchAndSaveSingleGroupInfoNow(groupID, sendUpdateEvent, expectedSession)
        }
        if (joined) {
            L.i { "[GroupUtil] fetch_joined gid=$groupID result=${group != null}" }
            
            if (sendUpdateEvent && !leaderEmits && group != null && group.status == 0) emitSingleGroupUpdate(group)
        }
        return group
    }

    private suspend fun fetchAndSaveSingleGroupInfoNow(
        groupID: String,
        sendUpdateEvent: Boolean,
        expectedSession: GroupWriteSession?,
    ): GroupModel? {
        val commitAllowed: (() -> Boolean)? = expectedSession?.let { session -> { session.isCurrent(userManager) } }
        return try {
            withContext(Dispatchers.IO) {
                requireGroupWriteSession(commitAllowed)
                val requestStartedAt = groupExpiryTracker.requestStartedAt()
                val response = groupRepo
                    .getGroupInfo(groupID)
                requireGroupWriteSession(commitAllowed)
                val groupInfo = response.data
                val group = wcdb.group.getFirstObject(
                    DBGroupModel.gid.eq(groupID)
                ) ?: GroupModel().apply { gid = groupID }
                group.status = response.status
                
                var corrected: GroupModel? = null

                if (response.status == 0) {
                    group.name = groupInfo?.name
                    group.messageExpiry = groupInfo?.messageExpiry
                    group.applyDetailExpiry(groupInfo)
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
                    group.groupCryptoKeyVersion = groupInfo?.groupCryptoKeyVersion

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
                    groupMemberWriter.replaceAllForGroup(groupID, members, commitAllowed)
                    requireGroupWriteSession(commitAllowed)
                    decryptGroupFieldsIfNeeded(group)
                    groupExpiryTracker.persistResponse(
                        listOf(group), requestStartedAt,
                        
                        carriesDeadline = { (groupInfo?.expireTime ?: 0L) > 0L },
                        stored = ::storedDeadline,
                        clearWritten = { gid -> corrected = writeClearedDeadline(gid) },
                    ) {
                        requireGroupWriteSession(commitAllowed)
                        val deadlineBefore = storedDeadline(groupID)
                        wcdb.group.deleteObjects(DBGroupModel.gid.eq(groupID))
                        wcdb.group.insertObject(group)
                        
                        if (sendUpdateEvent || deadlineBefore != group.expireTime) emitSingleGroupUpdate(group)
                    }
                    
                    
                    appScope.launch {
                        runCatching {
                            requireGroupWriteSession(commitAllowed)
                            groupCryptoRepo.verifyAllPendingForGroup(groupID, groupRepo, commitAllowed)
                        }
                            .onFailure { L.e { "[GroupUtil] verify trigger failed gid=$groupID: ${it.stackTraceToString()}" } }
                    }
                    L.i { "[GroupUtil] [Group: $groupID] fetch success, members: ${members.size}" }
                } else {
                    L.i { "[GroupUtil] [Group: $groupID] is invalid" }
                    cleanupGroupLocally(groupID, expectedSession)
                }

                corrected ?: group
            }
        } catch (e: CancellationException) {
            throw e
        } catch (throwable: Throwable) {
            L.e(throwable) { "[GroupUtil] [Group: $groupID] fetchAndSaveSingleGroup fail: ${throwable.message}" }
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

    




    internal fun decryptGroupFieldsIfNeeded(group: GroupModel) {
        if (group.groupCryptoMode == null || group.groupCryptoMode == 0) return
        val rGroupBytes = groupCryptoRepo.getRGroupBytes(group.gid ?: "")
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
                
                
                group.avatar = ""
            }
            L.i { "[GE] Decrypted fields for group ${group.gid}" }
        } catch (e: Exception) {
            L.e { "[GE] Decrypt fields failed for group ${group.gid}: ${e.message}" }
        }
    }

    companion object {
        
        private const val REFRESH_ATTEMPTS = 3

        private val GROUP_LIST_SYNC_INTERVAL_NS = TimeUnit.MINUTES.toNanos(10)

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
                privateChat = group.privateChat ?: false,
                members = group.members
            )
        }
    }
}








fun GroupModel.getDisplayAvatarData(): GroupAvatarData? {
    if ((groupCryptoMode ?: 0) > 0 && encryptedAvatar == null) return null
    return avatar?.getAvatarData()
}

fun String.getAvatarData(): GroupAvatarData? {
    return try {
        globalServices.gson.fromJson(this, GroupAvatarResponse::class.java)?.data?.let {
            val avatarData = String(Base64.decode(it))
            globalServices.gson.fromJson(avatarData, GroupAvatarData::class.java)
        }
    } catch (e: Exception) {
        L.e(e) { "[group] parse avatar data fail: $this ===" }
        null
    }
}
