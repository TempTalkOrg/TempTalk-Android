package com.difft.android.call

import android.content.Context
import android.content.Intent
import androidx.constraintlayout.widget.ConstraintLayout
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import difft.android.messageserialization.For
import io.reactivex.rxjava3.core.Observable
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.GroupModel
import java.util.ArrayList
import java.util.Optional

interface LCallToChatController {
    fun joinCall(context: Context, roomId: String, roomName: String?, callerId: String, callType: CallType, conversationId: String?, isNeedAppLock: Boolean, onComplete: (Boolean) -> Unit)

    fun rejectCall(callerId: String, callRole: CallRole?, type: String, roomId: String, conversationId: String?, onComplete: () -> Unit)

    fun cancelCall(callerId: String, callRole: CallRole?, type: String, roomId: String, conversationId: String?, onComplete: () -> Unit)

    fun hangUpCall(callerId: String, callRole: CallRole?, type: String, roomId: String, conversationId: String?, callUidList: List<String>, onComplete: () -> Unit)

    fun syncJoinedMessage(receiverId: String, callRole: CallRole?, callerId: String, type: String, roomId: String, conversationId: String?, mKey: ByteArray?)

    suspend fun getContactorById(context: Context, id: String): Optional<ContactorModel>

    fun getDisplayName(context: Context, id: String): String?

    fun getAvatarByContactor(context:Context, contactor: ContactorModel): ConstraintLayout

    fun createAvatarByNameOrUid(context: Context, name: String?, uid: String): ConstraintLayout

    fun getMySelfUid(): String

    fun getSingleGroupInfo(context: Context, conversationId: String): Optional<GroupModel>

    fun inviteUsersToTheCall(context: Context, roomId: String, roomName: String, e2eeKey: ByteArray?, callType: String, conversationId: String?, excludedIds: ArrayList<String>)

    fun cancelNotificationById(notificationId: Int)

    fun showCallNotification(roomId: String, callName: String, callerId: String, conversationId: String?, callType: CallType, isNeedAppLock: Boolean)

    fun isNotificationShowing(notificationId: Int): Boolean

    fun sendOrCreateCallTextMessage(callActionType: CallActionType, textContent: String, sourceDevice: Int, timestamp: Long, systemShowTime: Long, fromWho: For, forWhat: For, callType: CallType, createCallMsg: Boolean, inviteeLIst: List<String> = emptyList())

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

    fun restoreIncomingCallActivityIfIncoming()

    fun isAppForegrounded(): Boolean

    fun isIncomingCallActivityShowing(): Boolean

    fun isIncomingCallNotifying(roomId: String): Boolean

    fun getContactsUpdateListener(): Observable<List<String>>

    fun getGroupsUpdateListener(): Observable<GroupModel>

    fun startForegroundService(context: Context, intent: Intent)

    fun getIncomingCallRoomId(): String?

    fun dismissCriticalAlertIfActive()

    fun dismissCriticalAlertByConId(conversationId: String)
}