
package com.difft.android.websocket.api.messages

import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName


data class TTNotifyMessage(
    val `data`: Data?,
    val notifyTime: Long,
    val notifyType: Int,
    var showContent: String? = "", 
    var operatorName: String? = "", 
    val display: Int? = 0 
) {
    companion object {
        const val NOTIFY_MESSAGE_TYPE_GROUP = 0
        const val NOTIFY_MESSAGE_TYPE_UPDATE_CONTACT = 1
        const val NOTIFY_MESSAGE_TYPE_CONVERSATION_SETTING = 4
        const val NOTIFY_MESSAGE_TYPE_CONVERSATION_SHARE_SETTING = 5 
        const val NOTIFY_MESSAGE_TYPE_ADD_FRIEND = 6

        const val NOTIFY_MESSAGE_TYPE_CALL_END = 17 
        const val NOTIFY_MESSAGE_TYPE_RESET_IDENTITY_KEY = 19 

        const val NOTIFY_MESSAGE_TYPE_CRITICAL_ALERT = 20 

        const val NOTIFY_MESSAGE_TYPE_CRITICAL_ALERT_V2 = 22 
        const val NOTIFY_MESSAGE_TYPE_SECONDARY_DEVICE_KEPT = 23 

        const val NOTIFY_MESSAGE_TYPE_WEAK_CONTACT = 25 
        

        const val NOTIFY_MESSAGE_TYPE_FOCUS = 29 

        
        const val NOTIFY_MESSAGE_TYPE_LOCAL = 10000

        const val NOTIFY_ACTION_TYPE_ADD_FRIEND_REQUEST = 1
        const val NOTIFY_ACTION_TYPE_ADD_FRIEND_ACCEPT = 2 

        
        const val NOTIFY_ACTION_TYPE_ADD_FRIEND_REQUEST_DONE = 10000 
        const val NOTIFY_ACTION_TYPE_ADD_FRIEND_REQUEST_SENT = 10001 
        const val NOTIFY_ACTION_TYPE_BLOCKED_CHATIVE = 10002 
        const val NOTIFY_ACTION_TYPE_OFFLINE = 10003 
        const val NOTIFY_ACTION_TYPE_ACCOUNT_DISABLED = 10004 
        const val NOTIFY_ACTION_TYPE_SCREEN_SHOT = 10005 
        const val NOTIFY_ACTION_TYPE_BLOCKED_WEA = 10006 
        const val NOTIFY_ACTION_TYPE_REMOVE_FRIEND_DELETE_MESSAGES = 10007 
        const val NOTIFY_ACTION_TYPE_ACCOUNT_UNREGISTERED = 10008 
        const val NOTIFY_ACTION_TYPE_DEFAULT_ARCHIVE = 10009 
        const val NOTIFY_ACTION_TYPE_NON_FRIEND_LIMIT = 10010 
        const val NOTIFY_ACTION_TYPE_RESET_IDENTITY_KEY = 10011 
        const val NOTIFY_ACTION_TYPE_MESSAGES_EXPIRED = 10012 
        const val NOTIFY_ACTION_TYPE_FORWARD_NOTICE = 10020 
        const val NOTIFY_ACTION_TYPE_COPY_NOTICE = 10021 
        const val NOTIFY_ACTION_TYPE_TRANSLATE_SETTING_CHANGED = 10022 
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
    val serverTimestamp: Long = 0,
    val alertTitle: String? = null,
    val alertBody: String? = null,
    val showCriticalAlert: Boolean = false,
    val sourceDevice: Int = DEFAULT_DEVICE_ID,
    
    
    
    @SerializedName("uid") var uid: String? = null,            
    @SerializedName("name") var name: String? = null,          
    @SerializedName("avatar") var avatar: String? = null,      
    @SerializedName("expireTime") var expireTime: Long = 0,    
    @SerializedName("reason") var reason: Int = 0,             
    @SerializedName("deleteTime") var deleteTime: Long = 0,    
    
    
    @SerializedName("keptDeviceIds") val keptDeviceIds: List<String>? = null,
    @SerializedName("keptDeviceNames") val keptDeviceNames: List<String>? = null,
    @SerializedName("newDeviceName") val newDeviceName: String? = null,
    @SerializedName("keptTime") val keptTime: Long = 0,
    
    @SerializedName("focus") val focus: JsonElement? = null,
    @SerializedName("trigger") val trigger: String? = null,
    )

data class Group(
    val action: Int,
    val anyoneRemove: Boolean,
    val avatar: String,
    val ext: Boolean,
    val invitationRule: Int,
    val messageExpiry: Int,
    val name: String,
    val publishRule: Int, 
    val rejoin: Boolean,
    val remindCycle: String,
    val linkInviteSwitch: Boolean,
    val privateChat: Boolean,
    val anyoneChangeAutoClear: Boolean,
    val autoClear: Boolean,
    val messageClearAnchor: Long,
    val criticalAlert: Boolean = false,
    val groupCryptoMode: Int? = null,
    val encryptedName: String? = null,
    val encryptedAvatar: String? = null,
    )

data class Member(
    val uid: String?,
    val customUid: String?,
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
    val privateConfigs: PrivateConfigs?,
    val uidSignature: String? = null,
)

data class PublicConfigs(
    val meetingVersion: Int = 0,
    val publicName: String = "",
    val msgEncVersion: Int = 0,
    val publicAccountType: Int? = null,   
)

data class PrivateConfigs(
    val globalNotification: Int?
)

data class OperatorInfo(
    val operatorDeviceId: Int,
    val operatorId: String,
    val operatorName: String
)

data class NotifyConversation(
    val version: Int,
    val remark: String?,
    val remarkAvatar: String?,
    val muteStatus: Int,
    val blockStatus: Int,
    val confidentialMode: Int,
    val conversation: String,
    
    val allowMentionMe: Boolean? = null,
    val allowMentionAll: Boolean? = null,
    val allowGroupCall: Boolean? = null
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
