package com.difft.android.chat.setting.sync

import com.difft.android.network.responses.ConversationSetResponseBody
import com.difft.android.websocket.api.messages.NotifyConversation

/**
 * A server conversation-config snapshot as seen by [ConversationSettingsApplier]. A null field
 * means the source does not carry it and the local column is left alone.
 */
data class ServerConfigSnapshot(
    val conversation: String,
    val version: Int,
    val muteStatus: Int?,
    val blockStatus: Int?,
    val confidentialMode: Int?,
    /** Raw server value; the applier normalises it. */
    val messageExpiry: Long?,
    val messageClearAnchor: Long?,
) {
    companion object {
        /** `v1/conversation/get`: every field. */
        fun fromPull(body: ConversationSetResponseBody) = ServerConfigSnapshot(
            conversation = body.conversation,
            version = body.version,
            muteStatus = body.muteStatus,
            blockStatus = body.blockStatus,
            confidentialMode = body.confidentialMode,
            messageExpiry = body.messageExpiry,
            messageClearAnchor = body.messageClearAnchor,
        )

        /** `v1/conversation/set` echo: the expiry fields belong to another API and are never applied from it. */
        fun fromEcho(body: ConversationSetResponseBody) = ServerConfigSnapshot(
            conversation = body.conversation,
            version = body.version,
            muteStatus = body.muteStatus,
            blockStatus = body.blockStatus,
            confidentialMode = body.confidentialMode,
            messageExpiry = null,
            messageClearAnchor = null,
        )

        /** Notify type 4: mute, block and confidential only. */
        fun fromNotify(notify: NotifyConversation) = ServerConfigSnapshot(
            conversation = notify.conversation,
            version = notify.version,
            muteStatus = notify.muteStatus,
            blockStatus = notify.blockStatus,
            confidentialMode = notify.confidentialMode,
            messageExpiry = null,
            messageClearAnchor = null,
        )
    }
}

/** Where a [ServerConfigSnapshot] came from; decides how the managed fields are gated. */
sealed class SnapshotSource {
    /** `v1/conversation/get`. Authoritative: a same-version snapshot may re-apply. */
    data object Pull : SnapshotSource()

    /** `v1/conversation/set` echo of a request that changed a non-managed field. */
    data object Echo : SnapshotSource()

    /**
     * Notify type 4. [serverTimestamp] is when the server accepted the change, which is the other
     * device's operation time for conflict resolution.
     */
    data class Push(
        val serverTimestamp: Long,
        val changeType: Int,
        val operator: String?,
        val operatorDeviceId: Int,
    ) : SnapshotSource()
}

/** Result of [ConversationSettingsApplier.applyFinal]; the job decides its fate from it. */
enum class FinalOutcome {
    /** The managed field was written from the snapshot; the job is done. */
    COMPLETED,

    /** A newer local intent exists; nothing written for the managed field; the job is done. */
    SUPERSEDED,

    /** The snapshot is older than what this job needs; the job must fetch again. */
    NEEDS_RECONCILE,
}
