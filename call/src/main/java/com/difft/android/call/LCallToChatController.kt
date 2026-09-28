package com.difft.android.call

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.ui.avatar.AvatarLetterSize
import com.difft.android.call.data.AvatarData
import com.difft.android.call.handler.InviteRequestState
import difft.android.messageserialization.For
import kotlinx.coroutines.flow.Flow
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.GroupModel
import java.util.ArrayList
import java.util.Optional

/**
 * One reusable avatar View for an XML RecyclerView row, which must not host a per-row ComposeView.
 * Create one per ViewHolder, add [view] to the row container exactly once, then call [bind] on
 * every bind — the same View is rebound (as the chat XML rows do with their own `AvatarView`),
 * never re-inflated.
 */
interface AvatarViewBinder {
    /** The avatar View; add it to the row container once, never re-add or replace it. */
    val view: View

    /**
     * Rebinds [view] to [data]. Safe to call repeatedly and on a recycled row; every call
     * re-applies the avatar so a load that was dropped while the row was detached is retried.
     */
    fun bind(data: AvatarData)
}

interface LCallToChatController {
    suspend fun joinCall(context: Context, roomId: String, roomName: String?, callerId: String, callType: CallType, conversationId: String?, isNeedAppLock: Boolean): Boolean

    suspend fun rejectCall(callerId: String, callRole: CallRole?, type: String, roomId: String, conversationId: String?)

    suspend fun cancelCall(callerId: String, callRole: CallRole?, type: String, roomId: String, conversationId: String?)

    suspend fun hangUpCall(callerId: String, callRole: CallRole?, type: String, roomId: String, conversationId: String?, callUidList: List<String>)

    fun syncJoinedMessage(receiverId: String, callRole: CallRole?, callerId: String, type: String, roomId: String, conversationId: String?, mKey: ByteArray?)

    suspend fun getContactorById(context: Context, id: String): Optional<ContactorModel>

    /**
     * Renders [data] with the chat avatar chain (Glide + encrypted-avatar decrypt + letter fallback
     * + remark). Glide gets an explicit target size derived from [size].
     *
     * [letterTextSizeDp] defaults to the size the 48dp list avatar was designed with; pass
     * [AvatarLetterSize.forDiameterDp] to keep that ratio at another diameter.
     */
    @Composable
    fun Avatar(
        data: AvatarData,
        size: Dp,
        modifier: Modifier = Modifier,
        letterTextSizeDp: Int = AvatarLetterSize.DEFAULT_LETTER_TEXT_SIZE_DP,
    )

    /**
     * View counterpart of [Avatar] for XML RecyclerView rows. [avatarSizeDp] is the container
     * diameter; the letter size and the Glide target size are derived from it once, so the
     * request identity does not change between the first load and a rebind.
     */
    fun createAvatarViewBinder(
        context: Context,
        avatarSizeDp: Int = AvatarLetterSize.DEFAULT_AVATAR_SIZE_DP,
    ): AvatarViewBinder

    fun getMySelfUid(): String

    suspend fun getSingleGroupInfo(conversationId: String): GroupModel?

    fun cancelNotificationById(notificationId: Int)

    fun showCallNotification(roomId: String, callName: String, callerId: String, conversationId: String?, callType: CallType, isNeedAppLock: Boolean)

    fun isNotificationShowing(notificationId: Int): Boolean

    fun sendOrCreateCallTextMessage(callActionType: CallActionType, textContent: String, sourceDevice: Int, timestamp: Long, systemShowTime: Long, fromWho: For, forWhat: For, callType: CallType, createCallMsg: Boolean, inviteeLIst: List<String> = emptyList())

    /**
     * 发送截屏通知消息到对应会话
     * @param conversationId 会话ID
     * @param callType 会议类型
     */
    fun sendScreenshotNotification(conversationId: String, callType: CallType)

    /**
     * 创建本地 Critical Alert 文本消息
     * @param systemShowTimestamp 服务端时间戳
     * @param timestamp 消息时间戳
     * @param fromWho 消息发送者
     * @param forWhat 消息所属会话
     * @param sourceDevice 消息所属设备类型
     */
    suspend fun createCriticalAlertMessage(systemShowTimestamp: Long, timestamp: Long, fromWho: For, forWhat: For, sourceDevice: Int)

    fun getLocalPrivateKey(): ByteArray?

    fun getTheirPublicKey(uid: String): String?

    fun restoreIncomingCallScreenIfActive()

    fun isAppForegrounded(): Boolean

    fun isIncomingCallActivityShowing(): Boolean

    fun isIncomingCallNotifying(roomId: String): Boolean

    fun getContactsUpdateListener(): Flow<List<String>>

    fun getGroupsUpdateListener(): Flow<GroupModel>

    fun startForegroundService(context: Context, intent: Intent)

    fun getIncomingCallRoomId(): String?

    fun dismissCriticalAlertIfActive()

    fun dismissCriticalAlert(conversationId: String)

    fun cancelCriticalAlertNotification(conversationId: String? = null)

    suspend fun inviteCall(roomId: String, roomName: String?, callType: String?, mKey: ByteArray?, inviteMembers: ArrayList<String>, conversationId: String?): InviteRequestState

    fun isOfficialAccount(id: String): Boolean

    fun contactorListSortedByPinyin(list : List<ContactorModel>): List<ContactorModel>
}