package com.difft.android.test.di

import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.auth.AuthModule
import com.difft.android.network.auth.AuthTokenProvider
import com.difft.android.network.di.ChativeHttpClientModule
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import io.mockk.mockk
import javax.inject.Singleton

/**
 * Replaces the real HTTP clients — and the auth binding that depends on them — so a Hilt test
 * never builds an OkHttp stack or reaches the network. Kept in lockstep with
 * [ChativeHttpClientModule] and [AuthModule].
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [ChativeHttpClientModule::class, AuthModule::class]
)
object TestChativeHttpClientModule {

    @Provides
    fun provideAuthProvider(): ChativeHttpClient.AuthProvider =
        object : ChativeHttpClient.AuthProvider {
            override fun provideAuth(): String = "test-auth"
        }

    /** Fake rather than a mock: the three members are trivial and call counts stay readable. */
    @Provides
    @Singleton
    fun provideAuthTokenProvider(): AuthTokenProvider = object : AuthTokenProvider {
        override suspend fun current(): String = "test-token"
        override fun peek(): String = "test-token"
        override suspend fun refreshRejected(rejected: String): String = "test-token"
    }

    @ChativeHttpClientModule.AuthToken
    @Singleton
    @Provides
    fun provideAuthTokenClient(): ChativeHttpClient = mockk(relaxed = true)

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

    @ChativeHttpClientModule.NoHeaderLongTimeout
    @Singleton
    @Provides
    fun provideNoHeaderLongTimeoutClient(): ChativeHttpClient = mockk(relaxed = true)

    @ChativeHttpClientModule.SignalApi
    @Singleton
    @Provides
    fun provideSignalApiClient(): ChativeHttpClient = mockk(relaxed = true)
}
