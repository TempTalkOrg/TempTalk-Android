package org.difft.app.database.models

import com.tencent.wcdb.WCDBDefault
import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding
class RoomModel {
    
    
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    @WCDBField(isUnique = true, isNotNull = true)
    @WCDBIndex
    var roomId: String = ""

    



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

    




    @WCDBField
    @WCDBDefault(intValue = 0)
    var focusPin: Int = 0

    



    @WCDBField
    @WCDBDefault(intValue = 0)
    var muteStatus: Int = 0

    




    @WCDBField
    var allowMentionMe: Boolean? = null

    @WCDBField
    var allowMentionAll: Boolean? = null

    @WCDBField
    var allowGroupCall: Boolean? = null

    
    @WCDBField
    @WCDBDefault(intValue = 0)
    var blockStatus: Int = 0

    @WCDBField
    var readPosition: Long = 0

    @WCDBField
    var unreadMessageNum: Int = 0

    





    @WCDBField
    @WCDBDefault(intValue = 0)
    var markUnreadFlag: Int = 0

    
    @WCDBField
    @WCDBDefault(intValue = 0)
    var markUnreadAt: Long = 0

    
    @WCDBField
    var mentionType: Int = 0

    @WCDBField
    var lastActiveTime: Long = 0

    @WCDBField
    var groupMembersNumber: Int = 0

    



    @WCDBField
    @WCDBDefault(intValue = 0)
    var confidentialMode: Int = 0

    




    @WCDBField
    @WCDBDefault(intValue = 0)
    var conversationConfigVersion: Int = 0

    
    @WCDBField
    @WCDBDefault(intValue = 0)
    var criticalAlertType: Int = 0

    





    @WCDBField
    @WCDBDefault(intValue = 0)
    var sendStatus: Int = 0

    






    @WCDBField
    @WCDBDefault(intValue = 0)
    var sendingStatus: Int = 0

    





    @WCDBField
    var saveToPhotos: Int? = null

    




    @WCDBField
    @WCDBDefault(intValue = 0)
    var translateSetting: Int = 0

    





    @WCDBField
    var emptyRoomSince: Long? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RoomModel) return false
        return roomType == other.roomType &&
                muteStatus == other.muteStatus &&
                allowMentionMe == other.allowMentionMe &&
                allowMentionAll == other.allowMentionAll &&
                allowGroupCall == other.allowGroupCall &&
                blockStatus == other.blockStatus &&
                readPosition == other.readPosition &&
                unreadMessageNum == other.unreadMessageNum &&
                markUnreadFlag == other.markUnreadFlag &&
                markUnreadAt == other.markUnreadAt &&
                mentionType == other.mentionType &&
                lastActiveTime == other.lastActiveTime &&
                groupMembersNumber == other.groupMembersNumber &&
                confidentialMode == other.confidentialMode &&
                criticalAlertType == other.criticalAlertType &&
                sendStatus == other.sendStatus &&
                sendingStatus == other.sendingStatus &&
                roomId == other.roomId &&
                roomName == other.roomName &&
                roomAvatarJson == other.roomAvatarJson &&
                lastDisplayContent == other.lastDisplayContent &&
                messageExpiry == other.messageExpiry &&
                messageClearAnchor == other.messageClearAnchor &&
                pinnedTime == other.pinnedTime &&
                saveToPhotos == other.saveToPhotos &&
                translateSetting == other.translateSetting &&
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
        allowMentionMe,
        allowMentionAll,
        allowGroupCall,
        blockStatus,
        readPosition,
        unreadMessageNum,
        markUnreadFlag,
        markUnreadAt,
        mentionType,
        lastActiveTime,
        groupMembersNumber,
        confidentialMode,
        criticalAlertType,
        sendStatus,
        sendingStatus,
        saveToPhotos,
        translateSetting,
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
                ", allowMentionMe=" + allowMentionMe +
                ", allowMentionAll=" + allowMentionAll +
                ", allowGroupCall=" + allowGroupCall +
                ", blockStatus=" + blockStatus +
                ", readPosition=" + readPosition +
                ", unreadMessageNum=" + unreadMessageNum +
                ", markUnreadFlag=" + markUnreadFlag +
                ", markUnreadAt=" + markUnreadAt +
                ", mentionType=" + mentionType +
                ", lastActiveTime=" + lastActiveTime +
                ", groupMembersNumber=" + groupMembersNumber +
                ", confidentialMode=" + confidentialMode +
                ", criticalAlertType=" + criticalAlertType +
                ", sendStatus=" + sendStatus +
                ", sendingStatus=" + sendingStatus +
                ", saveToPhotos=" + saveToPhotos +
                ", translateSetting=" + translateSetting +
                ", emptyRoomSince=" + emptyRoomSince +
                '}'
}
