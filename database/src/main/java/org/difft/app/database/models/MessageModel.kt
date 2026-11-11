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
    var expiresInSeconds: Int = 0 

    @WCDBField
    var notifySequenceId: Long = 0

    @WCDBField
    var sequenceId: Long = 0

    @WCDBField
    var mode: Int = 0 

    @WCDBField
    var atPersons: String? = null

    @WCDBField
    var fromWho: String? = null

    @WCDBIndex
    @WCDBField
    var roomId: String? = null

    



    @WCDBField
    var roomType: Int = 0

    
    @WCDBField
    var quoteDatabaseId: Long? = null

    
    @WCDBField
    var forwardContextDatabaseId: Long? = null

    @WCDBField
    var playStatus: Int = 0 

    @WCDBField
    var readTime: Long = 0 

    @WCDBField
    var receiverIds: String? = null 

    
    @WCDBField
    var criticalAlertType: Int = 0

    



    @WCDBField
    var screenShotJson: String? = null

    
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
        





        const val TYPE_TEXT = 0
        const val TYPE_ATTACHMENT = 1
        const val TYPE_NOTIFY = 2
        const val TYPE_CONFIDENTIAL_PLACEHOLDER = 3 
        const val TYPE_UNSUPPORTED = 100

        








        const val SEND_TYPE_SENDING = 0
        const val SEND_TYPE_SENT = 1
        const val SEND_TYPE_FAILED = 2

        




        const val ARCHIVE_TOMBSTONE_SORT_SENTINEL = 1L
    }
}
