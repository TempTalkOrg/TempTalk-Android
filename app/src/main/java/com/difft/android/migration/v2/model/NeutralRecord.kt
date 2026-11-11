package com.difft.android.migration.v2.model












sealed interface NeutralRecord {

    data class Message(val value: NeutralMessage) : NeutralRecord

    data class Attachment(val value: NeutralAttachment) : NeutralRecord

    data class ConversationLocal(val value: NeutralConversationLocal) : NeutralRecord

    data class GroupKey(val value: NeutralGroupKey) : NeutralRecord

    




    data class Identity(val value: NeutralIdentity) : NeutralRecord

    




    data class Credential(val value: NeutralCredential) : NeutralRecord

    




    data class PendingEnvelope(val value: NeutralPendingEnvelope) : NeutralRecord
}
