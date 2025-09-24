package com.difft.android.network.di

import android.content.Context
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.UrlManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
object ChativeHttpClientModule {

    @Qualifier
    @Retention(AnnotationRetention.RUNTIME)
    annotation class Default

    @Qualifier
    @Retention(AnnotationRetention.RUNTIME)
    annotation class Chat

    @Qualifier
    @Retention(AnnotationRetention.RUNTIME)
    annotation class Call

    @Qualifier
    @Retention(AnnotationRetention.RUNTIME)
    annotation class FileShare

    @Qualifier
    @Retention(AnnotationRetention.RUNTIME)
    annotation class NoHeader

    @Provides
    fun provideAuthProvider(): ChativeHttpClient.AuthProvider = object : ChativeHttpClient.AuthProvider {
        override fun provideAuth(): String = SecureSharedPrefsUtil.getBasicAuth()
    }

    @Default
    @Provides
    @Singleton
    fun provideDefaultClient(
        @ApplicationContext applicationContext: Context,
        urlManager: UrlManager,
        authTokenProvider: ChativeHttpClient.AuthProvider
    ): ChativeHttpClient {
        return ChativeHttpClient(applicationContext, urlManager.default, authTokenProvider)
    }

    @Chat
    @Provides
    @Singleton
    fun provideChatClient(
        @ApplicationContext applicationContext: Context,
        urlManager: UrlManager,
        authTokenProvider: ChativeHttpClient.AuthProvider
    ): ChativeHttpClient {
        return ChativeHttpClient(applicationContext, urlManager.chat, authTokenProvider)
    }

    @Call
    @Provides
    @Singleton
    fun provideMeetingNewClient(
        @ApplicationContext applicationContext: Context,
        urlManager: UrlManager,
        authTokenProvider: ChativeHttpClient.AuthProvider
    ): ChativeHttpClient {
        return ChativeHttpClient(
            applicationContext,
            urlManager.call,
            authTokenProvider,
            connectTimeoutSeconds = 5,
            readWriteTimeoutSeconds = 5
        )
    }

    @FileShare
    @Provides
    @Singleton
    fun provideFileShareClient(
        @ApplicationContext applicationContext: Context,
        urlManager: UrlManager,
        authTokenProvider: ChativeHttpClient.AuthProvider
    ): ChativeHttpClient {
        return ChativeHttpClient(
            applicationContext,
            urlManager.fileSharing,
            authTokenProvider,
            connectTimeoutSeconds = 30,
            readWriteTimeoutSeconds = 30
        )
    }

    @NoHeader
    @Provides
    @Singleton
    fun provideNoHeaderClient(
        @ApplicationContext applicationContext: Context,
        urlManager: UrlManager,
    ): ChativeHttpClient {
        return ChativeHttpClient(
            applicationContext,
            urlManager.chat,
            null,
            useCustomCa = false,
            removeHeader = true,
            connectTimeoutSeconds = 30,
            readWriteTimeoutSeconds = 30
        )
    }
}