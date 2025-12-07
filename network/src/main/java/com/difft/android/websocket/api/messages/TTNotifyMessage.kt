/*
 * Copyright (C) 2014-2016 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */
package com.difft.android.websocket.api.messages

import com.google.gson.JsonElement

/**
 * Represents a decrypted Signal Service notify message.
 *https://github.com/difftim/server-docs/blob/master/apis/directory_change_notifyMsg.md
 */
data class TTNotifyMessage(
    val `data`: Data?,
    val notifyTime: Long,
    val notifyType: Int,
    var showContent: String? = "", //本地组装，用于显示的消息,
    var operatorName: String? = "", //本地组装，用于显示的消息 For pinned message now
    val display: Int? = 0 //0 no need display, 1 need display to ui
) {
    companion object {
        /**
         * notify type
         */
        const val NOTIFY_MESSAGE_TYPE_GROUP = 0
        const val NOTIFY_MESSAGE_TYPE_UPDATE_CONTACT = 1
        const val NOTIFY_MESSAGE_TYPE_CONVERSATION_SETTING = 4
        const val NOTIFY_MESSAGE_TYPE_CONVERSATION_SHARE_SETTING = 5 //会话共享配置
        const val NOTIFY_MESSAGE_TYPE_ADD_FRIEND = 6

        //        const val NOTIFY_MESSAGE_TYPE_PASSCODE = 15 // passcodeNotify
        const val NOTIFY_MESSAGE_TYPE_CALL_END = 17 // call end notify
        const val NOTIFY_MESSAGE_TYPE_RESET_IDENTITY_KEY = 19 // reset Identity Key

        const val NOTIFY_MESSAGE_TYPE_CRITICAL_ALERT = 20 // critical alert

        //本地创建 type
        const val NOTIFY_MESSAGE_TYPE_LOCAL = 10000

        /**
         * notify action type
         */
        const val NOTIFY_ACTION_TYPE_ADD_FRIEND_REQUEST = 1
        const val NOTIFY_ACTION_TYPE_ADD_FRIEND_ACCEPT = 2 // chative accept others request

        //（本地创建）action
        const val NOTIFY_ACTION_TYPE_ADD_FRIEND_REQUEST_DONE = 10000 //已接受请求 chative
        const val NOTIFY_ACTION_TYPE_ADD_FRIEND_REQUEST_SENT = 10001 //已发送请求（本地创建）chative
        const val NOTIFY_ACTION_TYPE_BLOCKED_CHATIVE = 10002 //被对方block（本地创建） chative
        const val NOTIFY_ACTION_TYPE_OFFLINE = 10003 //对方离线（本地创建）
        const val NOTIFY_ACTION_TYPE_ACCOUNT_DISABLED = 10004 //对方账号禁用（本地创建）
        const val NOTIFY_ACTION_TYPE_SCREEN_SHOT = 10005 //**拍摄了一张截图（本地创建）
        const val NOTIFY_ACTION_TYPE_BLOCKED_WEA = 10006 //跨team无法发消息（本地创建） cc/wea
        const val NOTIFY_ACTION_TYPE_REMOVE_FRIEND_DELETE_MESSAGES = 10007 //删除好友时删除消息（本地创建）
        const val NOTIFY_ACTION_TYPE_ACCOUNT_UNREGISTERED = 10008 //对方账号已注销（本地创建）
        const val NOTIFY_ACTION_TYPE_DEFAULT_ARCHIVE = 10009 //默认归档时间提醒消息（本地创建）
        const val NOTIFY_ACTION_TYPE_NON_FRIEND_LIMIT = 10010 //非好友每天只能发3条消息（本地创建）
        const val NOTIFY_ACTION_TYPE_RESET_IDENTITY_KEY = 10011 //重置identity key（本地创建）
        const val NOTIFY_ACTION_TYPE_MESSAGES_EXPIRED = 10012 //Earlier messages expired（本地创建）
    }
}

data class Data(
    var actionType: Int,
    var askID: Int = 0,
    var appId: String? = null,
    var cardId: String? = null,
    var content: String? = null,
    var operatorInfo: OperatorInfo? = null,
    var gid: String? = null,
    var group: Group? = null,
    var groupNotifyDetailedType: Int = 0,
    var groupNotifyType: Int = 0,
    var groupVersion: Int = 0,
    var members: List<Member>? = null,
    var operator: String? = null,
    var operatorDeviceId: Int = 0,
    var ver: Int = 0,
    var version: Int = 0,
    var directoryVersion: Int = 0,
    var conversation: JsonElement? = null,
    var messageExpiry: Int = 0,
    var duration: Int = 0,
    var inviter: String? = null,
    val taskInfos: List<TaskInfo>? = null,
    var changeType: Int = 0,
    var type: String? = null,
    var description: String? = null,
    var creator: String? = null,
    var topicId: String? = null,
    var source: String? = null,
    var conversationId: String? = null,
    var groupPins: List<GroupPin>? = null,
    var concatNumbers: String? = null,
    var endTimestamp: Long = 0,
    val invitorId: String? = null,
    val invitorName: String? = null,
    val inviteeId: String? = null,
    val inviteeName: String? = null,
    val roomId: String? = null,
    val resetIdentityKeyTime: Long = 0,
    val messageClearAnchor: Long = 0,
    val timestamp: Long = 0,
    val alertTitle: String? = null,
    val alertBody: String? = null,
    )

data class Group(
    val action: Int,
    val anyoneRemove: Boolean,
    val avatar: String,
    val ext: Boolean,
    val invitationRule: Int,
    val messageExpiry: Int,
    val name: String,
    val publishRule: Int, //0 - 仅群主可发言 1 - 仅管理员可发言 2 - 任何人可发言
    val rejoin: Boolean,
    val remindCycle: String,
    val linkInviteSwitch: Boolean,
    val privateChat: Boolean,
    val anyoneChangeAutoClear: Boolean,
    val autoClear: Boolean,
    val messageClearAnchor: Long,
    val criticalAlert: Boolean = false,
    )

data class Member(
    val uid: String?,
    val displayName: String?,
    val rapidRole: Int,
    val role: Int,

    val action: Int,
    val avatar: String?,
    val extId: Int,
    val friend: Boolean,
    val name: String?,
    val number: String?,
    val publicConfigs: PublicConfigs?,
    val privateConfigs: PrivateConfigs?
)

data class PublicConfigs(
    val meetingVersion: Int = 0,
    val publicName: String = "",
    val msgEncVersion: Int = 0,
)

data class PrivateConfigs(
    val globalNotification: Int?,
    val saveToPhotos: Boolean
)

data class OperatorInfo(
    val operatorDeviceId: Int,
    val operatorId: String,
    val operatorName: String
)

data class NotifyConversation(
    val version: Int,
    val remark: String?,
    val muteStatus: Int,
    val blockStatus: Int,
    val confidentialMode: Int,
    val conversation: String
)

data class TaskInfo(
    val task: Task?,
    val users: List<User>?
)

data class Task(
    val action: Int,
    val archived: Int,
    val createTime: Long,
    val creator: String?,
    val description: String?,
    val dueTime: Long,
    val gid: String?,
    val name: String?,
    val passThrough: String?,
    val priority: Int,
    val status: Int,
    val tid: String?,
    val uid: String?,
    val updateTime: Long,
    val updater: String?,
    val version: Int
)

data class User(
    val action: Int,
    val role: Int,
    val status: Int,
    val uid: String?
)

data class GroupPin(
    @Deprecated("use GroupNotifyDetailType instead")
    val action: Int,
    val businessId: String?,
    val content: String,
    val conversationId: String,
    val createTime: Long,
    val creator: String,
    val id: String
)