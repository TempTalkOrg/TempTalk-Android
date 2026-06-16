package org.difft.app.database.models

import com.tencent.wcdb.MultiIndexes
import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

@WCDBTableCoding(
    multiIndexes = [
        MultiIndexes(
            name = "idx_room_timestamp",
            columns = ["roomId", "systemShowTimestamp"]
        )
    ]
)
class MessageModel {
    // Int (not Long): WCDB-KSP generates `== 0` autoincrement guard which does not
    // compile for Long primary keys. Column type stays ColumnType.Integer — no schema
    // change. Local rowid never exceeds 2^31 and is never externally serialized. #901
    @WCDBField(isPrimary = true, isAutoIncrement = true)
    var databaseId: Int = 0

    @WCDBField(isUnique = true, isNotNull = true)
    @WCDBIndex(isUnique = true)
    var id: String = ""

    @WCDBField
    var timeStamp: Long = 0

    @WCDBField
    var systemShowTimestamp: Long = 0

    @WCDBField
    var receivedTimeStamp: Long = 0

    @WCDBField
    var messageText: String? = null

    @WCDBField
    var type: Int = 0

    @WCDBField
    var sendType: Int = 0

    @WCDBField
    var expiresInSeconds: Int = 0 // 0 indicates never expire

    @WCDBField
    var notifySequenceId: Long = 0

    @WCDBField
    var sequenceId: Long = 0

    @WCDBField
    var mode: Int = 0 // 0: normal, 1: CONFIDENTIAL

    @WCDBField
    var atPersons: String? = null

    @WCDBField
    var fromWho: String? = null

    @WCDBIndex
    @WCDBField
    var roomId: String? = null

    /**
     * One To One Chat(0),
     * Group(1),
     */
    @WCDBField
    var roomType: Int = 0

    // Boxed Long, no isNotNull → nullable. WCDB-KSP now generates a NULL guard on read,
    // so an absent/orphaned FK resolves to null instead of 0 — the forwarding-bug root
    // fix. Keep as Long? (FK to QuoteModel.databaseId). #901
    @WCDBField
    var quoteDatabaseId: Long? = null

    // Boxed Long, no isNotNull → nullable. Same NULL-guard root fix as quoteDatabaseId;
    // FK to ForwardContextModel.databaseId. #901
    @WCDBField
    var forwardContextDatabaseId: Long? = null

    @WCDBField
    var playStatus: Int = 0 // 是否已经播放过，默认已播放，1未播放

    @WCDBField
    var readTime: Long = 0 // 消息已读时间(自己)

    @WCDBField
    var receiverIds: String? = null // 接收者id集合 数组序列化

    /**
     * Critical Alert 消息类型
     * 0: 普通消息（非 Critical Alert）
     * 1: Critical Alert 消息
     * 预留其他值用于后续扩展
     */
    @WCDBField
    var criticalAlertType: Int = 0

    /**
     * Screenshot notification JSON data
     * Stores ScreenShot object as JSON string containing RealSource info
     */
    @WCDBField
    var screenShotJson: String? = null

    // Faithful 1:1 port of the original Java equals/hashCode. databaseId is intentionally
    // excluded (matches the original Java implementation). #901
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageModel) return false
        return timeStamp == other.timeStamp &&
                systemShowTimestamp == other.systemShowTimestamp &&
                receivedTimeStamp == other.receivedTimeStamp &&
                type == other.type &&
                sendType == other.sendType &&
                expiresInSeconds == other.expiresInSeconds &&
                notifySequenceId == other.notifySequenceId &&
                sequenceId == other.sequenceId &&
                mode == other.mode &&
                roomType == other.roomType &&
                playStatus == other.playStatus &&
                readTime == other.readTime &&
                criticalAlertType == other.criticalAlertType &&
                id == other.id &&
                messageText == other.messageText &&
                atPersons == other.atPersons &&
                fromWho == other.fromWho &&
                roomId == other.roomId &&
                quoteDatabaseId == other.quoteDatabaseId &&
                forwardContextDatabaseId == other.forwardContextDatabaseId &&
                receiverIds == other.receiverIds &&
                screenShotJson == other.screenShotJson
    }

    override fun hashCode(): Int = Objects.hash(
        id,
        timeStamp,
        systemShowTimestamp,
        receivedTimeStamp,
        messageText,
        type,
        sendType,
        expiresInSeconds,
        notifySequenceId,
        sequenceId,
        mode,
        atPersons,
        fromWho,
        roomId,
        roomType,
        quoteDatabaseId,
        forwardContextDatabaseId,
        playStatus,
        readTime,
        receiverIds,
        criticalAlertType,
        screenShotJson
    )

    companion object {
        /**
         * Text(0), // type value 0 means the message is a text message
         * Attachment(1), // type value 1 means the message is a file
         * Notify(2) // type value 2 means the message is a notify message,
         * Unsupported(100) // type value 100 means the message requires a newer client version
         */
        const val TYPE_TEXT = 0
        const val TYPE_ATTACHMENT = 1
        const val TYPE_NOTIFY = 2
        const val TYPE_CONFIDENTIAL_PLACEHOLDER = 3 // Confidential message read by recipient, awaiting sender confirmation to delete
        const val TYPE_UNSUPPORTED = 100
    }
}
