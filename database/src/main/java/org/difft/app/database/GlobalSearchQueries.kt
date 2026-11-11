package org.difft.app.database

import com.tencent.wcdb.winq.Column
import com.tencent.wcdb.winq.Expression
import com.tencent.wcdb.winq.Order
import com.tencent.wcdb.winq.OrderingTerm
import com.tencent.wcdb.winq.StatementSelect
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.GroupModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.RoomModel








private const val IN_CHUNK = 500

private fun likePattern(keyword: String): String = "%${keyword.uppercase()}%"






private fun searchableMessage(like: String): Expression =
    DBMessageModel.messageText.upper().like(like)
        .and(DBMessageModel.type.`in`(MessageModel.TYPE_TEXT, MessageModel.TYPE_ATTACHMENT))
        .and(DBMessageModel.mode.notEq(1))


fun WCDB.searchContactsByKeyword(keyword: String): List<ContactorModel> {
    val like = likePattern(keyword)
    return contactor.getAllObjects(
        DBContactorModel.remark.upper().like(like)
            .or(DBContactorModel.name.upper().like(like))
            .or(DBContactorModel.publicName.upper().like(like))
            .or(DBContactorModel.email.upper().like(like))
            .or(DBContactorModel.id.upper().like(like))
    )
}


fun WCDB.searchOneOnOneRoomsByName(keyword: String): List<RoomModel> =
    room.getAllObjects(
        DBRoomModel.roomType.notEq(1)
            .and(DBRoomModel.roomId.notEq("server"))
            .and(DBRoomModel.roomName.upper().like(likePattern(keyword)))
    )


fun WCDB.searchGroupsByKeyword(keyword: String): List<GroupModel> {
    val byName = group.getAllObjects(DBGroupModel.name.like("%$keyword%").and(DBGroupModel.status.eq(0)))
    val memberGids = groupMemberContactor.search(keyword).map { it.gid }.filter { it.matches(GID_PATTERN) }.distinct()
        .filter { gid -> byName.none { it.gid == gid } }
    val byMember = memberGids.chunked(IN_CHUNK).flatMap { chunk ->
        group.getAllObjects(DBGroupModel.gid.`in`(*chunk.toTypedArray()).and(DBGroupModel.status.eq(0)))
    }
    return (byName + byMember).distinctBy { it.gid }
}

private val GID_PATTERN = Regex("^[0-9a-fA-F]+$")


fun WCDB.roomLastActiveTimes(roomIds: List<String>): Map<String, Long> =
    roomIds.distinct().chunked(IN_CHUNK).flatMap { chunk ->
        room.getAllObjects(DBRoomModel.roomId.`in`(*chunk.toTypedArray()))
    }.associate { it.roomId to it.lastActiveTime }


data class RoomSearchResult(
    val room: RoomModel,
    val messageCount: Int,
    val latestMessage: MessageModel?,
    val latestTimestamp: Long
)





fun WCDB.loadRoomSearchResultsByKeyword(keyword: String): List<RoomSearchResult> {
    val like = likePattern(keyword)

    
    
    
    val colRoomId = Column("roomId")
    val colMaxTs = DBMessageModel.systemShowTimestamp.max()
    val statement = StatementSelect()
        .select(colRoomId, Column.all().count(), colMaxTs)
        .from("message")
        .where(searchableMessage(like))
        .groupBy(colRoomId)
        .orderBy(OrderingTerm(colMaxTs).order(Order.Desc))
    val roomGroups = db.getAllRowsFromStatement(statement).mapNotNull { row ->
        row[0].text?.let { roomId -> Triple(roomId, row[1].long, row[2].long) }
    }
    if (roomGroups.isEmpty()) return emptyList()
    val roomIds = roomGroups.map { it.first }

    
    val roomMap = roomIds.chunked(IN_CHUNK).flatMap { chunk ->
        room.getAllObjects(DBRoomModel.roomId.`in`(*chunk.toTypedArray()))
    }.associateBy { it.roomId }

    
    val maxTimestamps = roomGroups.associate { it.first to it.third }
    val candidates = roomIds.chunked(IN_CHUNK).flatMap { chunk ->
        message.getAllObjects(
            DBMessageModel.roomId.`in`(*chunk.toTypedArray())
                .and(DBMessageModel.systemShowTimestamp.`in`(*chunk.map { maxTimestamps.getValue(it) }.distinct().toLongArray()))
                .and(searchableMessage(like))
        )
    }
    val latest = latestHitPerRoom(maxTimestamps, candidates)

    return roomGroups.mapNotNull { (roomId, count, latestTimestamp) ->
        val roomModel = roomMap[roomId] ?: return@mapNotNull null
        RoomSearchResult(roomModel, count.toInt(), latest[roomId], latestTimestamp)
    }
}






internal fun latestHitPerRoom(maxTimestamps: Map<String, Long>, candidates: List<MessageModel>): Map<String, MessageModel> =
    candidates.filter { message -> message.roomId?.let { maxTimestamps[it] } == message.systemShowTimestamp }
        .groupBy { it.roomId.orEmpty() }
        .mapValues { (_, messages) -> messages.maxBy { it.timeStamp } }
