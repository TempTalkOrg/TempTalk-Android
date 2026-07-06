package com.difft.android.network.di

import com.difft.android.base.utils.globalServices

import android.content.Context
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.UrlManager
import com.difft.android.network.proxy.ProxyConfigProvider
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

    @Qualifier
    @Retention(AnnotationRetention.RUNTIME)
    annotation class SignalApi

    /** No-header client with a long timeout, for large uploads (e.g. debug-log upload). */
    @Qualifier
    @Retention(AnnotationRetention.RUNTIME)
    annotation class NoHeaderLongTimeout

    @Provides
    fun provideAuthProvider(): ChativeHttpClient.AuthProvider = object : ChativeHttpClient.AuthProvider {
        override fun provideAuth(): String = (globalServices.userManager.getUserData()?.baseAuth ?: "")
    }

    @Default
    @Provides
    @Singleton
    fun provideDefaultClient(
        @ApplicationContext applicationContext: Context,
        urlManager: UrlManager,
        authTokenProvider: ChativeHttpClient.AuthProvider,
        proxyConfigProvider: ProxyConfigProvider
    ): ChativeHttpClient {
        return ChativeHttpClient(
            applicationContext,
            urlManager.default,
            authTokenProvider,
            proxyConfigProvider = proxyConfigProvider
        )
    }

    @Chat
    @Provides
    @Singleton
    fun provideChatClient(
        @ApplicationContext applicationContext: Context,
        urlManager: UrlManager,
        authTokenProvider: ChativeHttpClient.AuthProvider,
        proxyConfigProvider: ProxyConfigProvider
    ): ChativeHttpClient {
        return ChativeHttpClient(
            applicationContext,
            urlManager.chat,
            authTokenProvider,
            proxyConfigProvider = proxyConfigProvider
        )
    }

    @Call
    @Provides
    @Singleton
    fun provideMeetingNewClient(
        @ApplicationContext applicationContext: Context,
        urlManager: UrlManager,
        authTokenProvider: ChativeHttpClient.AuthProvider,
        proxyConfigProvider: ProxyConfigProvider
    ): ChativeHttpClient {
        return ChativeHttpClient(
            applicationContext,
            urlManager.call,
            authTokenProvider,
            connectTimeoutSeconds = 5,
            readWriteTimeoutSeconds = 5,
            proxyConfigProvider = proxyConfigProvider
        )
    }

    @FileShare
    @Provides
    @Singleton
    fun provideFileShareClient(
        @ApplicationContext applicationContext: Context,
        urlManager: UrlManager,
        authTokenProvider: ChativeHttpClient.AuthProvider,
        proxyConfigProvider: ProxyConfigProvider
    ): ChativeHttpClient {
        return ChativeHttpClient(
            applicationContext,
            urlManager.fileSharing,
            authTokenProvider,
            connectTimeoutSeconds = 30,
            readWriteTimeoutSeconds = 30,
            proxyConfigProvider = proxyConfigProvider
        )
    }

    @NoHeader
    @Provides
    @Singleton
    fun provideNoHeaderClient(
        @ApplicationContext applicationContext: Context,
        urlManager: UrlManager,
        proxyConfigProvider: ProxyConfigProvider
    ): ChativeHttpClient {
        return ChativeHttpClient(
            applicationContext,
            urlManager.chat,
            null,
            useCustomCa = false,
            removeHeader = true,
            connectTimeoutSeconds = 30,
            readWriteTimeoutSeconds = 30,
            proxyConfigProvider = proxyConfigProvider
        )
    }

    /**
     * No-header client with a 300s read/write timeout (vs the 30s default) for large uploads such
     * as the debug-log upload — a multi-hundred-MB encrypted log can't finish within 30s on a slow
     * network. Separate from [provideNoHeaderClient] so the small avatar/config calls keep the
     * short timeout. Matches FileShareService's OSS-upload window.
     */
    @NoHeaderLongTimeout
    @Provides
    @Singleton
    fun provideNoHeaderLongTimeoutClient(
        @ApplicationContext applicationContext: Context,
        urlManager: UrlManager,
        proxyConfigProvider: ProxyConfigProvider
    ): ChativeHttpClient {
        return ChativeHttpClient(
            applicationContext,
            urlManager.chat,
            null,
            useCustomCa = false,
            removeHeader = true,
            connectTimeoutSeconds = 30,
            readWriteTimeoutSeconds = 300,
            proxyConfigProvider = proxyConfigProvider
        )
    }

    /**
     * Signal API client — no [HttpClientInterceptor], preserving raw HTTP status codes
     * for Repository-layer error mapping (device management, message sending fallback).
     */
    @SignalApi
    @Provides
    @Singleton
    fun provideSignalApiClient(
        @ApplicationContext applicationContext: Context,
        urlManager: UrlManager,
        authTokenProvider: ChativeHttpClient.AuthProvider,
        proxyConfigProvider: ProxyConfigProvider
    ): ChativeHttpClient {
        return ChativeHttpClient(
            applicationContext,
            urlManager.chat,
            authTokenProvider,
            useHttpClientInterceptor = false,
            serializeNulls = true,  // Match Jackson's default null-field serialization
            proxyConfigProvider = proxyConfigProvider
        )
    }

}
