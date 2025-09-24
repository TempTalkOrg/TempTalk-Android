package com.difft.android

import difft.android.messageserialization.For
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.protobuf.ByteString
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.thoughtcrime.securesms.jobs.RuntimeTypeAdapterFactory
import org.thoughtcrime.securesms.util.ByteUnit
import com.difft.android.websocket.util.ByteStringTypeAdapter
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ChatHiltProvidesModule {
    @Provides
    @Named("message_sender_max_envelope_size")
    fun provideMessageSenderMaxEnvelopeSize(): Long = ByteUnit.KILOBYTES.toBytes(256)


    @Provides
    @Singleton
    fun provideGson(): Gson {
        val valueAdapter = RuntimeTypeAdapterFactory.of(For::class.java)
            .registerSubtype(For.Account::class.java)
            .registerSubtype(For.Group::class.java)
        return GsonBuilder().registerTypeAdapterFactory(valueAdapter)
            .registerTypeAdapter(ByteString::class.java, ByteStringTypeAdapter()).create()
    }
}