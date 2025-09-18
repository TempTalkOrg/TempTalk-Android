package com.difft.android.network.di

import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.network.config.UserAgentManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.reactivex.rxjava3.core.Single
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ConfigModule {

    @Singleton
    @Named("UserAgent")
    @Provides
    fun provideUserAgent(): Single<String> {
        return Single.fromCallable { UserAgentManager.getUserAgent() }
    }
    @Singleton
    @Named("UserAgent")
    @Provides
    fun provideUserAgentString(): String = UserAgentManager.getUserAgent()

    @Provides
    fun provideHttpService(
        @ChativeHttpClientModule.Chat
        chativeHttpClient: ChativeHttpClient
    ) : HttpService {
        return chativeHttpClient.httpService
    }
}