package com.difft.android.chat.recent

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject


interface ChatListSourceProvider {
    fun chatListSource(activity: FragmentActivity): Flow<ChatListSource>
}


class SharedChatListSourceProvider @Inject constructor() : ChatListSourceProvider {
    override fun chatListSource(activity: FragmentActivity): Flow<ChatListSource> =
        ViewModelProvider(activity)[RecentChatViewModel::class.java].chatListSource
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ChatListSourceModule {
    @Binds
    abstract fun bindChatListSourceProvider(provider: SharedChatListSourceProvider): ChatListSourceProvider
}
