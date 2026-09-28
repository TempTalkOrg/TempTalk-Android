package com.difft.android.network.di

import com.difft.android.base.utils.globalServices

import android.content.Context
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.UrlManager
import com.difft.android.network.auth.AuthTokenProvider
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

    /**
     * Bounds the refresh a blocked OkHttp worker is waiting on. The other clients set only
     * connect/read/write timeouts, which leaves a stalled call unbounded overall.
     */
    private const val AUTH_TOKEN_CALL_TIMEOUT_SECONDS = 20L

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

    /** Client dedicated to the token refresh — see [provideAuthTokenClient]. */
    @Qualifier
    @Retention(AnnotationRetention.RUNTIME)
    annotation class AuthToken

    @Provides
    fun provideAuthProvider(): ChativeHttpClient.AuthProvider = object : ChativeHttpClient.AuthProvider {
        override fun provideAuth(): String = (globalServices.userManager.getUserData()?.baseAuth ?: "")
    }

    /**
     * Dedicated client for PUT v1/authorize/token. Separate from [Chat] on purpose: the refresh is
     * awaited from inside an OkHttp interceptor on another client, and sharing that client's
     * Dispatcher lets five concurrent callers to the same host starve the refresh they are waiting
     * for (OkHttp maxRequestsPerHost = 5). baseUrl is [UrlManager.chat] so the URL is unchanged.
     *
     * Its one endpoint is `@Auth(EXPLICIT)` — it carries its own Basic header — so no
     * [AuthTokenProvider] is handed to this client at all: that keeps the Dagger graph acyclic and
     * leaves no way for the refresh call to re-enter the provider's non-reentrant refresh mutex
     * from inside its own interceptor. Issue #1231.
     */
    @AuthToken
    @Provides
    @Singleton
    fun provideAuthTokenClient(
        @ApplicationContext applicationContext: Context,
        urlManager: UrlManager,
        proxyConfigProvider: ProxyConfigProvider
    ): ChativeHttpClient {
        return ChativeHttpClient(
            applicationContext,
            urlManager.chat,
            null,
            proxyConfigProvider = proxyConfigProvider,
            callTimeoutSeconds = AUTH_TOKEN_CALL_TIMEOUT_SECONDS
        )
    }

    @Default
    @Provides
    @Singleton
    fun provideDefaultClient(
        @ApplicationContext applicationContext: Context,
        urlManager: UrlManager,
        authProvider: ChativeHttpClient.AuthProvider,
        authTokenProvider: dagger.Lazy<AuthTokenProvider>,
        proxyConfigProvider: ProxyConfigProvider
    ): ChativeHttpClient {
        return ChativeHttpClient(
            applicationContext,
            urlManager.default,
            authProvider,
            proxyConfigProvider = proxyConfigProvider,
            authTokenProvider = authTokenProvider
        )
    }

    @Chat
    @Provides
    @Singleton
    fun provideChatClient(
        @ApplicationContext applicationContext: Context,
        urlManager: UrlManager,
        authProvider: ChativeHttpClient.AuthProvider,
        authTokenProvider: dagger.Lazy<AuthTokenProvider>,
        proxyConfigProvider: ProxyConfigProvider
    ): ChativeHttpClient {
        return ChativeHttpClient(
            applicationContext,
            urlManager.chat,
            authProvider,
            proxyConfigProvider = proxyConfigProvider,
            authTokenProvider = authTokenProvider
        )
    }

    @Call
    @Provides
    @Singleton
    fun provideMeetingNewClient(
        @ApplicationContext applicationContext: Context,
        urlManager: UrlManager,
        authProvider: ChativeHttpClient.AuthProvider,
        authTokenProvider: dagger.Lazy<AuthTokenProvider>,
        proxyConfigProvider: ProxyConfigProvider
    ): ChativeHttpClient {
        return ChativeHttpClient(
            applicationContext,
            urlManager.call,
            authProvider,
            connectTimeoutSeconds = 5,
            readWriteTimeoutSeconds = 5,
            proxyConfigProvider = proxyConfigProvider,
            authTokenProvider = authTokenProvider
        )
    }

    /**
     * Fileshare authenticates on a `token` field in the request body, not on the header, so its
     * endpoints declare `@Auth(NONE)` and the body token is never written to a debug log
     * (issues #1235, #1231).
     */
    @FileShare
    @Provides
    @Singleton
    fun provideFileShareClient(
        @ApplicationContext applicationContext: Context,
        urlManager: UrlManager,
        authProvider: ChativeHttpClient.AuthProvider,
        authTokenProvider: dagger.Lazy<AuthTokenProvider>,
        proxyConfigProvider: ProxyConfigProvider
    ): ChativeHttpClient {
        return ChativeHttpClient(
            applicationContext,
            urlManager.fileSharing,
            authProvider,
            connectTimeoutSeconds = 30,
            readWriteTimeoutSeconds = 30,
            proxyConfigProvider = proxyConfigProvider,
            authTokenProvider = authTokenProvider,
            debugLogBodies = false
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
        authProvider: ChativeHttpClient.AuthProvider,
        authTokenProvider: dagger.Lazy<AuthTokenProvider>,
        proxyConfigProvider: ProxyConfigProvider
    ): ChativeHttpClient {
        return ChativeHttpClient(
            applicationContext,
            urlManager.chat,
            authProvider,
            useHttpClientInterceptor = false,
            serializeNulls = true,  // Match Jackson's default null-field serialization
            proxyConfigProvider = proxyConfigProvider,
            authTokenProvider = authTokenProvider
        )
    }

}
