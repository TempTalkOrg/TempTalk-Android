package org.difft.app.database.models;

import com.tencent.wcdb.MultiIndexes;
import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

@WCDBTableCoding(
        multiIndexes = {
                @MultiIndexes(
                        name = "idx_room_timestamp",
                        columns = {"roomId", "systemShowTimestamp"}
                )
        }
)
public class MessageModel {
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    public long databaseId;

    @WCDBField(isUnique = true, isNotNull = true)
    @WCDBIndex(isUnique = true)
    public String id;

    @WCDBField
    public long timeStamp;

    @WCDBField
    public long systemShowTimestamp;

    @WCDBField
    public long receivedTimeStamp;

    @WCDBField
    public String messageText;

    /**
     * Text(0), // type value 0 means the message is a text message
     * Attachment(1), // type value 1 means the message is a file
     * Notify(2) // type value 2 means the message is a notify message,
     */
    @WCDBField
    public int type;

    @WCDBField
    public int sendType;

    @WCDBField
    public int expiresInSeconds; // 0 indicates never expire

    @WCDBField
    public long notifySequenceId;

    @WCDBField
    public long sequenceId;

    @WCDBField
    public int mode;  // 0: normal, 1: CONFIDENTIAL

    @WCDBField
    public String atPersons;

    @WCDBField
    public String readInfo;

    @WCDBField
    public String fromWho;

    @WCDBIndex
    @WCDBField
    public String roomId;

    /**
     * One To One Chat(0),
     * Group(1),
     */
    @WCDBField
    public int roomType;

    @WCDBField
    public Long quoteDatabaseId;

    @WCDBField
    public Long forwardContextDatabaseId;

    @WCDBField
    public Long cardModelDatabaseId;

    @WCDBField
    public int playStatus;//是否已经播放过，默认已播放，1未播放

    @WCDBField
    public long readTime;//消息已读时间(自己)

    @WCDBField
    public String receiverIds;//接收者id集合 数组序列化

    /**
     * Critical Alert 消息类型
     * 0: 普通消息（非 Critical Alert）
     * 1: Critical Alert 消息
     * 预留其他值用于后续扩展
     */
    @WCDBField
    public int criticalAlertType;

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MessageModel that)) return false;
        return timeStamp == that.timeStamp && systemShowTimestamp == that.systemShowTimestamp && receivedTimeStamp == that.receivedTimeStamp && type == that.type && sendType == that.sendType && expiresInSeconds == that.expiresInSeconds && notifySequenceId == that.notifySequenceId && sequenceId == that.sequenceId && mode == that.mode && roomType == that.roomType && playStatus == that.playStatus && readTime == that.readTime && criticalAlertType == that.criticalAlertType && Objects.equals(id, that.id) && Objects.equals(messageText, that.messageText) && Objects.equals(atPersons, that.atPersons) && Objects.equals(readInfo, that.readInfo) && Objects.equals(fromWho, that.fromWho) && Objects.equals(roomId, that.roomId) && Objects.equals(quoteDatabaseId, that.quoteDatabaseId) && Objects.equals(forwardContextDatabaseId, that.forwardContextDatabaseId) && Objects.equals(cardModelDatabaseId, that.cardModelDatabaseId) && Objects.equals(receiverIds, that.receiverIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, timeStamp, systemShowTimestamp, receivedTimeStamp, messageText, type, sendType, expiresInSeconds, notifySequenceId, sequenceId, mode, atPersons, readInfo, fromWho, roomId, roomType, quoteDatabaseId, forwardContextDatabaseId, cardModelDatabaseId, playStatus, readTime, receiverIds, criticalAlertType);
    }
}