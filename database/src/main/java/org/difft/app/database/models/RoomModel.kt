package org.difft.app.database.models

import com.tencent.wcdb.WCDBDefault
import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding
class RoomModel {
    // Int (not Long): WCDB-KSP generates `== 0` autoincrement guard which does not
    // compile for Long primary keys. Column type stays ColumnType.Integer — no schema
    // change. Local rowid never exceeds 2^31 and is never externally serialized. #901
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    @WCDBField(isUnique = true, isNotNull = true)
    @WCDBIndex
    var roomId: String = ""

    /**
     * 0: single chat
     * 1: group chat
     */
    @WCDBField
    var roomType: Int = 0

    @WCDBField
    var roomName: String? = null

    @WCDBField
    var roomAvatarJson: String? = null

    @WCDBField
    var lastDisplayContent: String? = null

    @WCDBField
    var messageExpiry: Long? = null

    @WCDBField
    var messageClearAnchor: Long? = null

    @WCDBField
    var pinnedTime: Long? = null

    /**
     * MUTED(1),
     * UNMUTED(0);
     */
    @WCDBField
    @WCDBDefault(intValue = 0)
    var muteStatus: Int = 0

    /**
     * 屏蔽状态（仅单聊有效）
     * 0: 未屏蔽
     * 1: 已屏蔽
     */
    @WCDBField
    @WCDBDefault(intValue = 0)
    var blockStatus: Int = 0

    @WCDBField
    var readPosition: Long = 0

    @WCDBField
    var unreadMessageNum: Int = 0

    /**
     * const val MENTIONS_TYPE_NONE = -1
     * const val MENTIONS_TYPE_ALL = 1
     * const val MENTIONS_TYPE_ME = 2
     */
    @WCDBField
    var mentionType: Int = 0

    @WCDBField
    var lastActiveTime: Long = 0

    @WCDBField
    var groupMembersNumber: Int = 0

    /**
     * 0: normal mode
     * 1: confidential mode
     */
    @WCDBField
    @WCDBDefault(intValue = 0)
    var confidentialMode: Int = 0

    /**
     * Critical Alert 类型（用于会话列表显示）
     * 0: 无未读 Critical Alert
     * 1: 有未读 Critical Alert
     * 预留其他值用于后续扩展
     */
    @WCDBField
    @WCDBDefault(intValue = 0)
    var criticalAlertType: Int = 0

    /**
     * Aggregate outgoing-send signal for the conversation list tag.
     * 0: no failure (default) / 1: reserved "sending" / 2: has a failed outgoing message.
     * Values + invariants: [difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE].
     * Derived, purely local state — never synced (a failed message never reached the server).
     */
    @WCDBField
    @WCDBDefault(intValue = 0)
    var sendStatus: Int = 0

    /**
     * Conversation-level save to photos setting
     * null: Follow global setting (default)
     * 0: Disabled (never)
     * 1: Enabled (always)
     */
    @WCDBField
    var saveToPhotos: Int? = null

    /**
     * Timestamp (ms) when the conversation last became empty
     * null: Conversation has content (not empty)
     * non-null: Conversation is empty, value is the time when it became empty
     * Used for empty room timeout cleanup without affecting lastActiveTime sorting
     */
    @WCDBField
    var emptyRoomSince: Long? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RoomModel) return false
        return roomType == other.roomType &&
                muteStatus == other.muteStatus &&
                blockStatus == other.blockStatus &&
                readPosition == other.readPosition &&
                unreadMessageNum == other.unreadMessageNum &&
                mentionType == other.mentionType &&
                lastActiveTime == other.lastActiveTime &&
                groupMembersNumber == other.groupMembersNumber &&
                confidentialMode == other.confidentialMode &&
                criticalAlertType == other.criticalAlertType &&
                sendStatus == other.sendStatus &&
                roomId == other.roomId &&
                roomName == other.roomName &&
                roomAvatarJson == other.roomAvatarJson &&
                lastDisplayContent == other.lastDisplayContent &&
                messageExpiry == other.messageExpiry &&
                messageClearAnchor == other.messageClearAnchor &&
                pinnedTime == other.pinnedTime &&
                saveToPhotos == other.saveToPhotos &&
                emptyRoomSince == other.emptyRoomSince
    }

    override fun hashCode(): Int = Objects.hash(
        roomId,
        roomType,
        roomName,
        roomAvatarJson,
        lastDisplayContent,
        messageExpiry,
        messageClearAnchor,
        pinnedTime,
        muteStatus,
        blockStatus,
        readPosition,
        unreadMessageNum,
        mentionType,
        lastActiveTime,
        groupMembersNumber,
        confidentialMode,
        criticalAlertType,
        sendStatus,
        saveToPhotos,
        emptyRoomSince
    )

    override fun toString(): String =
        "RoomModel{" +
                "databaseId=" + databaseId +
                ", roomId='" + roomId + '\'' +
                ", roomType=" + roomType +
                ", roomName='" + roomName + '\'' +
                ", roomAvatarJson='" + roomAvatarJson + '\'' +
                ", lastDisplayContent='" + lastDisplayContent + '\'' +
                ", messageExpiry=" + messageExpiry +
                ", messageClearAnchor=" + messageClearAnchor +
                ", pinnedTime=" + pinnedTime +
                ", muteStatus=" + muteStatus +
                ", blockStatus=" + blockStatus +
                ", readPosition=" + readPosition +
                ", unreadMessageNum=" + unreadMessageNum +
                ", mentionType=" + mentionType +
                ", lastActiveTime=" + lastActiveTime +
                ", groupMembersNumber=" + groupMembersNumber +
                ", confidentialMode=" + confidentialMode +
                ", criticalAlertType=" + criticalAlertType +
                ", sendStatus=" + sendStatus +
                ", saveToPhotos=" + saveToPhotos +
                ", emptyRoomSince=" + emptyRoomSince +
                '}'
}
