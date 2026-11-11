package com.difft.android.search

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.group.GroupUIData
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.search.SearchChatHistoryViewData
import com.difft.android.chat.search.SearchUtils
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.getContactorFromAllTable
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.loadRoomSearchResultsByKeyword
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.RoomModel
import org.difft.app.database.roomLastActiveTimes
import org.difft.app.database.searchContactsByKeyword
import org.difft.app.database.searchGroupsByKeyword
import org.difft.app.database.searchOneOnOneRoomsByName
import javax.inject.Inject


interface GlobalSearchSource {
    




    suspend fun contacts(key: String, limit: Int? = null): List<ContactorModel>

    
    suspend fun groups(key: String, limit: Int? = null): List<GroupUIData>

    
    suspend fun chatHistory(key: String): List<SearchChatHistoryViewData>
}

class WcdbGlobalSearchSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wcdb: WCDB,
) : GlobalSearchSource {

    override suspend fun contacts(key: String, limit: Int?): List<ContactorModel> = withContext(Dispatchers.IO) {
        try {
            val myId = globalServices.myId
            val matched = wcdb.searchContactsByKeyword(key)
            val matchedIds = matched.mapTo(HashSet()) { it.id }
            val rooms = GlobalSearchRanking.rankRoomsByName(
                wcdb.searchOneOnOneRoomsByName(key).filter { it.roomId != myId && it.roomId !in matchedIds },
                key,
            )
            
            val roomsInReach = if (limit == null) rooms else rooms.take((limit - matched.size).coerceAtLeast(0) + 1)
            val saved = if (GlobalSearchRanking.matchesSavedConversation(key, context.getString(com.difft.android.base.R.string.chat_favorites))) {
                wcdb.getContactorFromAllTable(myId) ?: ContactorModel().apply { id = myId }
            } else {
                null
            }
            GlobalSearchRanking.mergeContactHits(saved, GlobalSearchRanking.rankContacts(matched, key), conversationPeers(roomsInReach), limit)
        } catch (e: Exception) {
            L.w { "[GlobalSearch] contacts_failed ${e.stackTraceToString()}" }
            emptyList()
        }
    }

    
    private fun conversationPeers(rooms: List<RoomModel>): List<ContactorModel> {
        if (rooms.isEmpty()) return emptyList()
        val profiles = wcdb.getContactorsFromAllTable(rooms.map { it.roomId }).associateBy { it.id }
        return rooms.map { room ->
            profiles[room.roomId] ?: ContactorModel().apply {
                id = room.roomId
                name = room.roomName
            }
        }
    }

    override suspend fun groups(key: String, limit: Int?): List<GroupUIData> = withContext(Dispatchers.IO) {
        try {
            val groups = wcdb.searchGroupsByKeyword(key)
            val ranked = GlobalSearchRanking.rankGroups(groups, key, wcdb.roomLastActiveTimes(groups.mapNotNull { it.gid }))
            (if (limit == null) ranked else ranked.take(limit)).map { GroupUtil.convert(it) }
        } catch (e: Exception) {
            L.w { "[GlobalSearch] groups_failed ${e.stackTraceToString()}" }
            emptyList()
        }
    }

    override suspend fun chatHistory(key: String): List<SearchChatHistoryViewData> = withContext(Dispatchers.IO) {
        try {
            SearchUtils.createSearchChatHistoryViewDataList(context, wcdb.loadRoomSearchResultsByKeyword(key))
        } catch (e: Exception) {
            L.w { "[GlobalSearch] chat_history_failed ${e.stackTraceToString()}" }
            emptyList()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface GlobalSearchModule {
    @Binds
    fun bindGlobalSearchSource(impl: WcdbGlobalSearchSource): GlobalSearchSource
}
