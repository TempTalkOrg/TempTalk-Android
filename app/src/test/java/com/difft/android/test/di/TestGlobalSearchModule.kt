package com.difft.android.test.di

import com.difft.android.chat.group.GroupUIData
import com.difft.android.chat.search.SearchChatHistoryViewData
import com.difft.android.search.GlobalSearchModule
import com.difft.android.search.GlobalSearchSource
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import org.difft.app.database.models.ContactorModel


object FakeGlobalSearchSource : GlobalSearchSource {
    var contacts: List<ContactorModel> = emptyList()
    var groups: List<GroupUIData> = emptyList()
    var history: List<SearchChatHistoryViewData> = emptyList()
    val keys = mutableListOf<String>()

    fun reset() {
        contacts = emptyList()
        groups = emptyList()
        history = emptyList()
        keys.clear()
    }

    override suspend fun contacts(key: String, limit: Int?): List<ContactorModel> =
        (if (limit == null) contacts else contacts.take(limit)).also { keys += key }

    override suspend fun groups(key: String, limit: Int?): List<GroupUIData> = if (limit == null) groups else groups.take(limit)
    override suspend fun chatHistory(key: String): List<SearchChatHistoryViewData> = history
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [GlobalSearchModule::class])
object TestGlobalSearchModule {
    @Provides
    fun provideGlobalSearchSource(): GlobalSearchSource = FakeGlobalSearchSource
}
