package com.difft.android.messageserialization.db.store.di

import com.difft.android.messageserialization.db.store.DBPublicKeyInfoStore
import com.difft.android.messageserialization.db.store.DBRoomStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import difft.android.messageserialization.PublicKeyInfoStore
import difft.android.messageserialization.RoomStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class RoomSerializationDbModule {
    @Provides
    @Singleton
    fun bindRoomStore(store: DBRoomStore): RoomStore = store

    @Provides
    @Singleton
    fun bindPublicKeyInfoStore(store: DBPublicKeyInfoStore): PublicKeyInfoStore = store
}