package com.difft.android.chat.setting.sync

import com.difft.android.network.responses.ConversationSetResponseBody
import com.difft.android.websocket.api.messages.NotifyConversation





data class ServerConfigSnapshot(
    val conversation: String,
    val version: Int,
    val muteStatus: Int?,
    val blockStatus: Int?,
    val confidentialMode: Int?,
    
    val messageExpiry: Long?,
    val messageClearAnchor: Long?,
    
    val allowMentionMe: Boolean? = null,
    val allowMentionAll: Boolean? = null,
    val allowGroupCall: Boolean? = null,
) {
    companion object {
        
        fun fromPull(body: ConversationSetResponseBody) = ServerConfigSnapshot(
            conversation = body.conversation,
            version = body.version,
            muteStatus = body.muteStatus,
            blockStatus = body.blockStatus,
            confidentialMode = body.confidentialMode,
            messageExpiry = body.messageExpiry,
            messageClearAnchor = body.messageClearAnchor,
            allowMentionMe = body.allowMentionMe,
            allowMentionAll = body.allowMentionAll,
            allowGroupCall = body.allowGroupCall,
        )

        
        fun fromEcho(body: ConversationSetResponseBody) = ServerConfigSnapshot(
            conversation = body.conversation,
            version = body.version,
            muteStatus = body.muteStatus,
            blockStatus = body.blockStatus,
            confidentialMode = body.confidentialMode,
            messageExpiry = null,
            messageClearAnchor = null,
            allowMentionMe = body.allowMentionMe,
            allowMentionAll = body.allowMentionAll,
            allowGroupCall = body.allowGroupCall,
        )

        
        fun fromNotify(notify: NotifyConversation) = ServerConfigSnapshot(
            conversation = notify.conversation,
            version = notify.version,
            muteStatus = notify.muteStatus,
            blockStatus = notify.blockStatus,
            confidentialMode = notify.confidentialMode,
            messageExpiry = null,
            messageClearAnchor = null,
            allowMentionMe = notify.allowMentionMe,
            allowMentionAll = notify.allowMentionAll,
            allowGroupCall = notify.allowGroupCall,
        )
    }
}


sealed class SnapshotSource {
    
    data object Pull : SnapshotSource()

    
    data object Echo : SnapshotSource()

    



    data class Push(
        val serverTimestamp: Long,
        val changeType: Int,
        val operator: String?,
        val operatorDeviceId: Int,
    ) : SnapshotSource()
}


enum class FinalOutcome {
    
    COMPLETED,

    
    SUPERSEDED,

    
    NEEDS_RECONCILE,
}
