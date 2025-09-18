package com.difft.android.messageserialization.db.store.di

import difft.android.messageserialization.MessageStore
import com.difft.android.messageserialization.db.store.DBMessageStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class MessageSerializationDbModule {
    @Provides
    @Singleton
    fun bindMessageStore(store: DBMessageStore): MessageStore = store
}