package org.difft.app.database

import com.tencent.wcdb.orm.Field
import com.tencent.wcdb.winq.Column
import com.tencent.wcdb.winq.StatementSelect
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.RoomModel

private const val DIRECTORY_IN_CHUNK = 500






fun WCDB.groupMemberCounts(gids: Collection<String>): Map<String, Int> {
    if (gids.isEmpty()) return emptyMap()
    groupMemberContactor 
    val gid = DBGroupMemberContactorModel.gid
    return gids.distinct().chunked(DIRECTORY_IN_CHUNK).flatMap { chunk ->
        db.getAllRowsFromStatement(
            StatementSelect()
                .select(gid, Column.all().count())
                .from("group_member_contactor")
                .where(gid.`in`(chunk))
                .groupBy(gid)
        ).mapNotNull { row -> row[0].text?.let { it to row[1].long.toInt() } }
    }.toMap()
}


fun WCDB.roomMessageExpiries(roomIds: Collection<String>): Map<String, Long> =
    roomColumns(roomIds, DBRoomModel.messageExpiry).associate { it.roomId to (it.messageExpiry ?: 0L) }


fun WCDB.roomMuteAndBlock(roomIds: Collection<String>): List<RoomModel> =
    roomColumns(roomIds, DBRoomModel.muteStatus, DBRoomModel.blockStatus)


private fun WCDB.roomColumns(roomIds: Collection<String>, vararg columns: Field<RoomModel>): List<RoomModel> {
    if (roomIds.isEmpty()) return emptyList()
    return roomIds.distinct().chunked(DIRECTORY_IN_CHUNK).flatMap { chunk ->
        room.prepareSelect().select(DBRoomModel.roomId, *columns).where(DBRoomModel.roomId.`in`(chunk)).allObjects()
    }
}
