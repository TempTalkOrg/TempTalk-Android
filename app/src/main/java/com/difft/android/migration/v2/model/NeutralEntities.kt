package com.difft.android.migration.v2.model

import com.difft.android.migration.v2.container.AbsentStaysNull














































data class NeutralMessage(
    val stableId: String,
    val conversationRef: String,
    val roomType: Int = 0,
    val sender: String,
    val direction: MessageDirection,
    val body: String?,
    val sentAt: Long,
    val serverAt: Long,
    val receivedAt: Long?,
    val expireTimer: Int,
    val expireStartedAt: Long? = null,
    val contentKind: ContentKind,
    val systemType: String?,
    val systemPayload: String? = null,
    val displayText: String? = null,
    val confidential: Boolean,
    val quote: NeutralQuote? = null,
    val forwardContext: NeutralForwardContext? = null,
    val mentions: List<NeutralMention> = emptyList(),
    val reactions: List<NeutralReaction> = emptyList(),
    val sharedContacts: List<NeutralSharedContact> = emptyList(),
    val attachmentRefs: List<String> = emptyList(),
    val sendState: SendState? = null,
    val sequenceId: Long? = null,
    val notifySequenceId: Long? = null,
    val criticalAlertType: Int? = null,
    val screenShot: NeutralScreenShot? = null,
    val recipients: List<String> = emptyList(),
    val platformExtras: Map<String, String> = emptyMap(),
)










data class NeutralScreenShot(
    val author: String,
    val sentAt: Long,
)





enum class SendState {
    SENT,
    FAILED,
}


enum class MessageDirection {
    INCOMING,
    OUTGOING,
}













enum class AttachmentOwner {
    MESSAGE,
    FORWARD,
}






























data class NeutralAttachment(
    val stableId: String,
    val messageRef: String,
    val contentType: String?,
    val size: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val flags: Int = 0,
    val amplitudes: String? = null,
    val fileName: String?,
    val serverAttachmentId: Long? = null,
    val encryptionKey: String? = null,
    val digestBase64: String? = null,
    val pointerOnly: Boolean = false,
    val owner: AttachmentOwner = AttachmentOwner.MESSAGE,
    val ownerAuthor: String? = null,
    val ownerSentAt: Long? = null,
)


























data class NeutralConversationLocal(
    val conversationRef: String,
    val roomType: Int = 0,
    val pinnedTime: Long? = null,
    val readPosition: NeutralReadPosition? = null,
    val readPositions: List<NeutralReadPosition> = emptyList(),
    val participantReadPositions: List<NeutralParticipantReadPosition> = emptyList(),
)









data class NeutralParticipantReadPosition(
    val uid: String,
    val readPosition: NeutralReadPosition,
)








data class NeutralReadPosition(
    val readAt: Long,
    val maxServerTime: Long,
    val maxNotifySequenceId: Long,
    val maxSequenceId: Long,
)








data class NeutralGroupKey(
    val groupId: String,
    val rGroup: String,
    val keyVersion: Int = 0,
)


































data class NeutralIdentity(
    val aciPublicKey: String,
    val aciPrivateKey: String,
    val oldAciPublicKey: String? = null,
    val oldAciPrivateKey: String? = null,
    val identityKeyGenTime: Long = 0,
    val favKey: String? = null,
    val favKeyId: String? = null,
    val favKeyVersion: Int = 0,
    val proxyShareLink: String? = null,
    val proxyEnabled: Boolean = false,
    val proxyProtectCallIp: Boolean = false,
    @field:AbsentStaysNull val recoverySlotKeks: Map<String, String>? = null,
    @field:AbsentStaysNull val recoveryStaleSlots: List<String>? = null,
)
















data class NeutralCredential(
    val account: String,
    val baseAuth: String,
    val microToken: String,
    val signalingKey: String,
    val email: String? = null,
    val phoneNumber: String? = null,
    val handoffId: String,
)






























data class NeutralPendingEnvelope(
    val messageId: String,
    val originalMessageTimeStamp: Long,
    val messageEnvelopBytes: String,
)
