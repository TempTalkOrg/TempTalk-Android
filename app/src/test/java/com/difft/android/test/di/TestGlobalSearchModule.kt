package com.difft.android.test.di

import com.difft.android.chat.group.GroupUIData
import com.difft.android.chat.recent.RoomViewData
import com.difft.android.chat.search.SearchChatHistoryViewData
import com.difft.android.search.GlobalSearchModule
import com.difft.android.search.GlobalSearchSource
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import org.difft.app.database.models.ContactorModel

/** Scripted search answers for the global search pages; tests set the lists before typing. */
object FakeGlobalSearchSource : GlobalSearchSource {
    var recent: List<RoomViewData> = emptyList()
    var contacts: List<ContactorModel> = emptyList()
    var groups: List<GroupUIData> = emptyList()
    var history: List<SearchChatHistoryViewData> = emptyList()
    val keys = mutableListOf<String>()

    fun reset() {
        recent = emptyList()
        contacts = emptyList()
        groups = emptyList()
        history = emptyList()
        keys.clear()
    }

    override suspend fun recentChats(key: String): List<RoomViewData> = recent.also { keys += key }
    override suspend fun contacts(key: String): List<ContactorModel> = contacts
    override suspend fun groups(key: String): List<GroupUIData> = groups
    override suspend fun chatHistory(key: String, maxRooms: Int?): List<SearchChatHistoryViewData> = history
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [GlobalSearchModule::class])
object TestGlobalSearchModule {
    @Provides
    fun provideGlobalSearchSource(): GlobalSearchSource = FakeGlobalSearchSource
}
