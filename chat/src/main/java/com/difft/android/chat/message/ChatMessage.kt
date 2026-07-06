package com.difft.android.chat.message

import difft.android.messageserialization.For
import difft.android.messageserialization.model.CombinedForwardMode
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Mode
import java.io.Serializable

abstract class ChatMessage : Serializable {
    var id: String = ""
    lateinit var authorId: String
    var isMine: Boolean = false
    /** Source conversation (set in [generateMessageTwo]). Used by derived surfaces
     *  (preview / translate / STT) to emit copy/forward notices. @Transient because
     *  [For] is not Serializable. */
    @Transient
    var forWhat: For? = null

    /** Override author id used by detail-view dispatch (PRD v1.0 §5 / Phase 5).
     *  Null on main-conversation messages; set by `ChatForwardMessageFragment.submitList`
     *  when displaying sub-messages of a combined-forward bubble so that downstream
     *  copy/forward notice dispatch reports the original sub-author. @Transient because
     *  this is authoring metadata, not persisted message state. */
    @Transient
    var sourceAuthorOverride: String? = null

    /** Override combined-forward mode used by detail-view dispatch (PRD v1.0 §5 / Phase 5).
     *  Null on main-conversation messages; set to [CombinedForwardMode.SUB_COMBINED_FORWARD]
     *  by `ChatForwardMessageFragment.submitList` for sub-messages inside a CF bubble.
     *  @Transient because this is authoring metadata, not persisted message state. */
    @Transient
    var sourceMode: CombinedForwardMode? = null

    /**
     * {@link com.difft.android.messageserialization.db.store.model.MessageModel.SendType}
     */
    var sendStatus: Int? = null
    var timeStamp: Long = 0
    var systemShowTimestamp: Long = 0
    var readMaxSId: Long = 0
    var notifySequenceId: Long = 0
    var selectedStatus: Boolean = false //only used in pin message manage page
    var editMode: Boolean = false //only used in pin message manage page
    var mode: Int = 0
    var showName: Boolean = true
    var showTime: Boolean = true
    var showDayTime: Boolean = true
    var showNewMsgDivider: Boolean = false
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChatMessage

        if (id != other.id) return false
        if (authorId != other.authorId) return false
        if (isMine != other.isMine) return false
        if (sendStatus != other.sendStatus) return false
        if (timeStamp != other.timeStamp) return false
        if (systemShowTimestamp != other.systemShowTimestamp) return false
        if (readMaxSId != other.readMaxSId) return false
        if (notifySequenceId != other.notifySequenceId) return false
        if (selectedStatus != other.selectedStatus) return false
        if (editMode != other.editMode) return false
        if (mode != other.mode) return false
        if (showName != other.showName) return false
        if (showTime != other.showTime) return false
        if (showDayTime != other.showDayTime) return false
        if (showNewMsgDivider != other.showNewMsgDivider) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + authorId.hashCode()
        result = 31 * result + isMine.hashCode()
        result = 31 * result + (sendStatus ?: 0)
        result = 31 * result + timeStamp.hashCode()
        result = 31 * result + systemShowTimestamp.hashCode()
        result = 31 * result + readMaxSId.hashCode()
        result = 31 * result + notifySequenceId.hashCode()
        result = 31 * result + selectedStatus.hashCode()
        result = 31 * result + editMode.hashCode()
        result = 31 * result + mode
        result = 31 * result + showName.hashCode()
        result = 31 * result + showTime.hashCode()
        result = 31 * result + showDayTime.hashCode()
        result = 31 * result + showNewMsgDivider.hashCode()
        return result
    }

    override fun toString(): String {
        return "ChatMessage(id='$id', authorId='$authorId', isMine=$isMine, sendStatus=$sendStatus, timeStamp=$timeStamp, systemShowTimestamp=$systemShowTimestamp, readMaxSId=$readMaxSId, notifySequenceId=$notifySequenceId, selectedStatus=$selectedStatus, editMode=$editMode, mode=$mode, showName=$showName, showTime=$showTime, showDayTime=$showDayTime, showNewMsgDivider=$showNewMsgDivider)"
    }


}

fun ChatMessage.isConfidential(): Boolean {
    return this.mode == Mode.CONFIDENTIAL_VALUE
}

fun ChatMessage.isConfidentialPlaceholder(): Boolean {
    return this is ConfidentialPlaceholderChatMessage
}

/** Whether this message renders as a centered notify-style row (no chat bubble). */
fun ChatMessage.isNotifyStyleMessage(): Boolean {
    return this is NotifyChatMessage || (this is TextChatMessage && this.isScreenShotMessage)
}

/**
 * Whether this bubble is a combined-forward (a.k.a. "Chat History") per PRD v1.0 §4.4.
 *
 * Only `TextChatMessage` bubbles whose `forwardContext` carries **more than one** top-level
 * forward qualify. A `forwards.size == 1` bubble is just a single forwarded message rendered
 * in regular UI — NOT a CF.
 *
 * Used by [com.difft.android.chat.message.NoticeAggregator] to derive
 * [difft.android.messageserialization.model.CombinedForwardMode] for the copy/forward
 * notice wire fields per PRD §5.3.
 */
fun ChatMessage.isCombinedForward(): Boolean {
    if (this !is TextChatMessage) return false
    val forwards = forwardContext?.forwards ?: return false
    return forwards.size > 1
}