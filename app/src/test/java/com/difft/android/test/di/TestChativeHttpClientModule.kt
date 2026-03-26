package com.difft.android.test.di

import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import io.mockk.mockk
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [ChativeHttpClientModule::class]
)
object TestChativeHttpClientModule {

    @Provides
    fun provideAuthProvider(): ChativeHttpClient.AuthProvider =
        object : ChativeHttpClient.AuthProvider {
            override fun provideAuth(): String = "test-auth"
        }

    @ChativeHttpClientModule.Default
    @Singleton
    @Provides
    fun provideDefaultClient(): ChativeHttpClient = mockk(relaxed = true)

    @ChativeHttpClientModule.Chat
    @Singleton
    @Provides
    fun provideChatClient(): ChativeHttpClient = mockk(relaxed = true)

    @ChativeHttpClientModule.Call
    @Singleton
    @Provides
    fun provideCallClient(): ChativeHttpClient = mockk(relaxed = true)

    @ChativeHttpClientModule.FileShare
    @Singleton
    @Provides
    fun provideFileShareClient(): ChativeHttpClient = mockk(relaxed = true)

    @ChativeHttpClientModule.NoHeader
    @Singleton
    @Provides
    fun provideNoHeaderClient(): ChativeHttpClient = mockk(relaxed = true)
}
