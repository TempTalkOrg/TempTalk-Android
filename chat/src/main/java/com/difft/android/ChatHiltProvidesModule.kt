package com.difft.android

import difft.android.messageserialization.For
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.protobuf.ByteString
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.difft.android.chat.jobmanager.Data
import com.difft.android.chat.jobmanager.impl.JsonDataSerializer
import com.difft.android.chat.jobs.ReactionSendCoordinator
import com.difft.android.chat.jobs.RuntimeTypeAdapterFactory
import com.difft.android.chat.util.ByteUnit
import com.difft.android.websocket.util.ByteStringTypeAdapter
import com.difft.android.base.utils.dbKeyFailSoftExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    // Lockstep: must match the Data.Serializer set in ApplicationDependencyProvider's
    // JobManager config. Drift here silently disables ReactionSendCoordinator dedupe.
    @Provides
    @Singleton
    fun provideJobDataSerializer(): Data.Serializer = JsonDataSerializer()

    // Process-scoped — ReactionSendCoordinator launches enqueue work here so it survives
    // Fragment/view destruction (the optimistic DB write has already committed by then).
    @Provides
    @Singleton
    @Named(ReactionSendCoordinator.REACTION_COORDINATOR_SCOPE)
    fun provideReactionCoordinatorScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName(ReactionSendCoordinator.REACTION_COORDINATOR_SCOPE) + dbKeyFailSoftExceptionHandler)
}
