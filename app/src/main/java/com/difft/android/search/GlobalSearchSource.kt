package com.difft.android.search

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.group.GroupUIData
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.recent.RoomViewData
import com.difft.android.chat.search.SearchChatHistoryViewData
import com.difft.android.chat.search.SearchUtils
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import difft.android.messageserialization.model.isManualUnreadActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.cache.ContactRemarkCache
import org.difft.app.database.loadRoomSearchResultsByKeyword
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.RoomModel
import org.difft.app.database.search
import org.difft.app.database.searchByNameAndGroupMembers
import javax.inject.Inject

/** The four database searches behind the global search page; each returns an empty list on failure. */
interface GlobalSearchSource {
    suspend fun recentChats(key: String): List<RoomViewData>
    suspend fun contacts(key: String): List<ContactorModel>
    suspend fun groups(key: String): List<GroupUIData>

    /** Conversations with matching messages; [maxRooms] null returns every room (the More page). */
    suspend fun chatHistory(key: String, maxRooms: Int? = null): List<SearchChatHistoryViewData>
}

class WcdbGlobalSearchSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wcdb: WCDB,
) : GlobalSearchSource {

    override suspend fun recentChats(key: String): List<RoomViewData> = withContext(Dispatchers.IO) {
        try {
            val commonQuery = DBRoomModel.roomId.notEq("server")
                .and(DBRoomModel.roomName.notNull())
                .and(DBRoomModel.roomName.notEq(""))

            val baseRooms = if (key.isEmpty()) {
                wcdb.room.getAllObjects(commonQuery)
            } else {
                wcdb.room.getAllObjects(commonQuery.and(DBRoomModel.roomName.upper().like("%${key.uppercase()}%")))
            }

            // The favourites room joins the result when its display name matches and is not already in it.
            val rooms = baseRooms + if (context.getString(com.difft.android.base.R.string.chat_favorites).contains(key, true) &&
                !baseRooms.any { it.roomId == globalServices.myId }
            ) {
                wcdb.room.getFirstObject(DBRoomModel.roomId.eq(globalServices.myId))?.let { listOf(it) } ?: emptyList()
            } else {
                emptyList()
            }

            rooms.sortedWith(compareByDescending<RoomModel> { it.pinnedTime ?: 0L }.thenByDescending { it.lastActiveTime })
                .map { it.toRoomViewData() }
        } catch (e: Exception) {
            L.w { "[GlobalSearch] recent_chats_failed ${e.stackTraceToString()}" }
            emptyList()
        }
    }

    override suspend fun contacts(key: String): List<ContactorModel> = withContext(Dispatchers.IO) {
        try {
            wcdb.contactor.search(key)
        } catch (e: Exception) {
            L.w { "[GlobalSearch] contacts_failed ${e.stackTraceToString()}" }
            emptyList()
        }
    }

    override suspend fun groups(key: String): List<GroupUIData> = withContext(Dispatchers.IO) {
        try {
            wcdb.group.searchByNameAndGroupMembers(key).map { GroupUtil.convert(it) }
        } catch (e: Exception) {
            L.w { "[GlobalSearch] groups_failed ${e.stackTraceToString()}" }
            emptyList()
        }
    }

    override suspend fun chatHistory(key: String, maxRooms: Int?): List<SearchChatHistoryViewData> = withContext(Dispatchers.IO) {
        try {
            val results = if (maxRooms != null) wcdb.loadRoomSearchResultsByKeyword(key, maxRooms = maxRooms)
            else wcdb.loadRoomSearchResultsByKeyword(key)
            SearchUtils.createSearchChatHistoryViewDataList(context, results)
        } catch (e: Exception) {
            L.w { "[GlobalSearch] chat_history_failed ${e.stackTraceToString()}" }
            emptyList()
        }
    }

    private fun RoomModel.toRoomViewData(): RoomViewData {
        val isOneOnOne = roomType != 1
        val remarkAvatarJson = if (isOneOnOne) ContactRemarkCache.getRemarkAvatar(roomId)?.takeIf { it.isNotEmpty() } else null
        return RoomViewData(
            roomId = roomId,
            type = if (isOneOnOne) RoomViewData.Type.OneOnOne else RoomViewData.Type.Group,
            roomName = roomName,
            roomAvatarJson = roomAvatarJson,
            remarkAvatarJson = remarkAvatarJson,
            lastDisplayContent = lastDisplayContent,
            lastActiveTime = lastActiveTime,
            unreadMessageNum = unreadMessageNum,
            isManualUnread = isManualUnreadActive(markUnreadFlag, markUnreadAt, lastActiveTime, readPosition),
            muteStatus = muteStatus,
            pinnedTime = pinnedTime,
            mentionType = mentionType,
            criticalAlertType = criticalAlertType,
            // Search results are rendered by the SAME RecentChatViewHolder as the main
            // conversation list, so omitting this would silently drop the tag here only.
            sendStatus = sendStatus,
            sendingStatus = sendingStatus,
            messageExpiry = messageExpiry,
            groupMembersNumber = groupMembersNumber,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface GlobalSearchModule {
    @Binds
    fun bindGlobalSearchSource(impl: WcdbGlobalSearchSource): GlobalSearchSource
}
