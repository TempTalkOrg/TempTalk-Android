package com.difft.android.chat.message

import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Mode
import java.io.Serializable

abstract class ChatMessage : Serializable {
    var id: String = ""
    lateinit var authorId: String
    var isMine: Boolean = false

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