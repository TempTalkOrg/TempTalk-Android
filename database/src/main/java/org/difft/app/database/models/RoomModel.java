package org.difft.app.database.models;

import com.tencent.wcdb.WCDBDefault;
import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

@WCDBTableCoding
public class RoomModel {
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    public long databaseId;

    @WCDBField(isUnique = true, isNotNull = true)
    @WCDBIndex
    public String roomId;

    /**
     * 0: single chat
     * 1: group chat
     */
    @WCDBField
    public int roomType;

    @WCDBField
    public String roomName;

    @WCDBField
    public String roomAvatarJson;

    @WCDBField
    public String lastDisplayContent;

    @WCDBField
    public Long messageExpiry;

    @WCDBField
    public Long messageClearAnchor;

    @WCDBField
    public Long pinnedTime;

    /**
     * MUTED(1),
     * UNMUTED(0);
     */
    @WCDBField
    @WCDBDefault(intValue = 0)
    public int muteStatus;

    @WCDBField
    public String publicKeyInfoJson;

    @WCDBField
    public long readPosition;

    @WCDBField
    public int unreadMessageNum;

    /**
     * const val MENTIONS_TYPE_NONE = -1
     * const val MENTIONS_TYPE_ALL = 1
     * const val MENTIONS_TYPE_ME = 2
     */
    @WCDBField
    public int mentionType;

    @WCDBField
    public long lastActiveTime;

    @WCDBField
    public int groupMembersNumber;

    /**
     * 0: normal mode
     * 1: confidential mode
     */
    @WCDBField
    @WCDBDefault(intValue = 0)
    public int confidentialMode;

    /**
     * Critical Alert 类型（用于会话列表显示）
     * 0: 无未读 Critical Alert
     * 1: 有未读 Critical Alert
     * 预留其他值用于后续扩展
     */
    @WCDBField
    @WCDBDefault(intValue = 0)
    public int criticalAlertType;

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RoomModel roomModel)) return false;
        return roomType == roomModel.roomType && muteStatus == roomModel.muteStatus && readPosition == roomModel.readPosition && unreadMessageNum == roomModel.unreadMessageNum && mentionType == roomModel.mentionType && lastActiveTime == roomModel.lastActiveTime && groupMembersNumber == roomModel.groupMembersNumber && confidentialMode == roomModel.confidentialMode && criticalAlertType == roomModel.criticalAlertType && Objects.equals(roomId, roomModel.roomId) && Objects.equals(roomName, roomModel.roomName) && Objects.equals(roomAvatarJson, roomModel.roomAvatarJson) && Objects.equals(lastDisplayContent, roomModel.lastDisplayContent) && Objects.equals(messageExpiry, roomModel.messageExpiry) && Objects.equals(messageClearAnchor, roomModel.messageClearAnchor) && Objects.equals(pinnedTime, roomModel.pinnedTime) && Objects.equals(publicKeyInfoJson, roomModel.publicKeyInfoJson);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roomId, roomType, roomName, roomAvatarJson, lastDisplayContent, messageExpiry, messageClearAnchor, pinnedTime, muteStatus, publicKeyInfoJson, readPosition, unreadMessageNum, mentionType, lastActiveTime, groupMembersNumber, confidentialMode, criticalAlertType);
    }

    @Override
    public String toString() {
        return "RoomModel{" +
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
                ", publicKeyInfoJson='" + publicKeyInfoJson + '\'' +
                ", readPosition=" + readPosition +
                ", unreadMessageNum=" + unreadMessageNum +
                ", mentionType=" + mentionType +
                ", lastActiveTime=" + lastActiveTime +
                ", groupMembersNumber=" + groupMembersNumber +
                ", confidentialMode=" + confidentialMode +
                ", criticalAlertType=" + criticalAlertType +
                '}';
    }
}