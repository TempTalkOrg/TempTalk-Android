package com.difft.android.migration.v2.model

import com.difft.android.migration.v2.container.AbsentIsEmpty







const val MENTIONS_ALL_UID = "MENTIONS_ALL"












data class NeutralQuote(
    @field:AbsentIsEmpty val author: String,
    val sentAt: Long,
    val textPreview: String?,
    val attachmentsMeta: List<NeutralAttachmentMeta> = emptyList(),
    val referencedMessageNotFound: Boolean? = null,
    val messageMode: Int? = null,
)








data class NeutralAttachmentMeta(
    val stableId: String,
    val contentType: String?,
    val fileName: String?,
)







data class NeutralReaction(
    val emoji: String,
    val fromUid: String,
    val removed: Boolean,
    val sentAt: Long,
)








data class NeutralMention(
    val uid: String,
    val start: Int,
    val length: Int,
    val type: Int? = null,
)









data class NeutralSharedContact(
    val givenName: String? = null,
    val familyName: String? = null,
    val namePrefix: String? = null,
    val nameSuffix: String? = null,
    val middleName: String? = null,
    val displayName: String? = null,
    val phones: List<NeutralSharedContactPhone> = emptyList(),
)


data class NeutralSharedContactPhone(
    val value: String? = null,
    val type: Int = 0,
    val label: String? = null,
)









data class NeutralForward(
    @field:AbsentIsEmpty val author: String,
    val sentAt: Long,
    val body: String?,
    val attachmentsMeta: List<NeutralAttachmentMeta> = emptyList(),
    val nested: List<NeutralForward> = emptyList(),
)











data class NeutralForwardContext(
    val isFromGroup: Boolean = false,
    val roots: List<NeutralForward>,
)

