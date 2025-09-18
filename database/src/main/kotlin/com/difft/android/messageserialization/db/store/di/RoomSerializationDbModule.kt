package com.difft.android.messageserialization.db.store.di

import difft.android.messageserialization.RoomStore
import com.difft.android.messageserialization.db.store.DBRoomStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class RoomSerializationDbModule {
    @Provides
    @Singleton
    fun bindRoomStore(store: DBRoomStore): RoomStore = store
}