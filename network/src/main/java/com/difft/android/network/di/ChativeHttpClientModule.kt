package com.difft.android.network.di

import com.difft.android.base.utils.globalServices

import android.content.Context
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.network.ChatService
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

    
    @Qualifier
    @Retention(AnnotationRetention.RUNTIME)
    annotation class NoHeaderLongTimeout

    
    @Qualifier
    @Retention(AnnotationRetention.RUNTIME)
    annotation class AuthToken

    @Provides
    fun provideAuthProvider(): ChativeHttpClient.AuthProvider = object : ChativeHttpClient.AuthProvider {
        override fun provideAuth(): String = (globalServices.userManager.getUserData()?.baseAuth ?: "")
    }

    










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
            service = ChatService.CHAT,
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
            service = ChatService.DEFAULT,
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
            service = ChatService.CHAT,
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
            service = ChatService.CALL,
            proxyConfigProvider = proxyConfigProvider,
            authTokenProvider = authTokenProvider
        )
    }

    




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
            service = ChatService.FILE_SHARING,
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
            service = ChatService.CHAT,
            proxyConfigProvider = proxyConfigProvider
        )
    }

    





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
            service = ChatService.CHAT,
            proxyConfigProvider = proxyConfigProvider
        )
    }

    





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
            serializeNulls = true,  
            service = ChatService.CHAT,
            proxyConfigProvider = proxyConfigProvider,
            authTokenProvider = authTokenProvider
        )
    }

}
