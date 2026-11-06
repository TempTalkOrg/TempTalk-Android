package com.difft.android.chat.focus

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.globalServices
import com.difft.android.network.responses.MuteStatus
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.TextMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.RoomModel
import org.difft.app.database.wcdb
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feeds [GroupNotifyPolicy] from the local stores for the WebSocket delivery path: the room's mute
 * state and sub-options, the group notification level, and the focus state. Pushes delivered by FCM
 * were already judged by the server and do not come through here.
 */
@Singleton
class GroupNotifyGate @Inject constructor(
    private val userManager: UserManager,
    private val focusRepository: GroupFocusRepository,
) {
    /**
     * Decision for a new message. [room] is the conversation row (null when it does not exist yet,
     * which reads as not muted). A null [message] is categorised as [GroupNotifyPolicy.Category.OTHER].
     */
    suspend fun decideMessage(forWhat: For, message: Message?, room: RoomModel?): GroupNotifyPolicy.Decision {
        focusRepository.ensureLoaded()
        val category = categoryOf(message)
        return withContext(Dispatchers.IO) { decide(forWhat.id, forWhat is For.Group, category, room) }
    }

    /**
     * Whether a group call invite may ring. With the feature off this is always true: before the focus
     * rules the client had no local check and the server filtered the push.
     */
    suspend fun shouldNotifyGroupCall(gid: String): Boolean {
        focusRepository.ensureLoaded()
        if (!focusRepository.effectiveNow().featureEnabled) return true
        val decision = withContext(Dispatchers.IO) {
            decide(gid, true, GroupNotifyPolicy.Category.GROUP_CALL, readRoom(gid))
        }
        if (!decision.notify) L.i { "[GroupFocus] group call suppressed reason=${decision.reason}" }
        return decision.notify
    }

    private fun decide(roomId: String, isGroup: Boolean, category: GroupNotifyPolicy.Category, room: RoomModel?): GroupNotifyPolicy.Decision {
        val muted = room?.muteStatus == MuteStatus.MUTED.value
        val input = GroupNotifyPolicy.Input(
            gid = roomId.takeIf { isGroup },
            category = category,
            muted = muted,
            muteOptions = GroupNotifyPolicy.MuteOptions(room?.allowMentionMe, room?.allowMentionAll, room?.allowGroupCall),
            level = if (isGroup && !muted) readLevel(roomId) else GroupNotifyPolicy.NotifyLevel.ALL,
            focus = focusRepository.effectiveNow(),
        )
        return GroupNotifyPolicy.decide(input)
    }

    private fun categoryOf(message: Message?): GroupNotifyPolicy.Category {
        val uids = (message as? TextMessage)?.mentions?.mapNotNull { it.uid }.orEmpty()
        return GroupNotifyPolicy.categorize(uids, globalServices.myId)
    }

    /** The self member row's level, or the global one when the group follows it (or has no row yet). */
    private fun readLevel(gid: String): GroupNotifyPolicy.NotifyLevel {
        val self = wcdb.groupMemberContactor.getFirstObject(
            DBGroupMemberContactorModel.gid.eq(gid).and(DBGroupMemberContactorModel.id.eq(globalServices.myId))
        )
        val value = if (self == null || self.useGlobal == true) userManager.getUserData()?.globalNotification else self.notification
        return when (value) {
            GlobalNotificationType.OFF.value -> GroupNotifyPolicy.NotifyLevel.OFF
            GlobalNotificationType.MENTION.value -> GroupNotifyPolicy.NotifyLevel.MENTION
            else -> GroupNotifyPolicy.NotifyLevel.ALL
        }
    }

    private fun readRoom(roomId: String): RoomModel? =
        wcdb.room.getFirstObject(DBRoomModel.roomId.eq(roomId))
}
