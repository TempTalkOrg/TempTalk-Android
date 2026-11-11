package com.difft.android.setting.proxy

import android.content.Context
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.proxy.ProxyConfigProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ProxyProbe

@InstallIn(SingletonComponent::class)
@Module
abstract class ProxyE2eProbeBindModule {
    @Binds
    abstract fun bindProbe(impl: ProxyE2eProbeImpl): ProxyE2eProbe
}

@InstallIn(SingletonComponent::class)
@Module
object ProxyE2eProbeProvideModule {
    




    @ProxyProbe
    @Provides
    @Singleton
    fun provideProbeClient(
        @ApplicationContext ctx: Context,
        proxyConfigProvider: ProxyConfigProvider,
    ): ChativeHttpClient = ChativeHttpClient(
        applicationContext = ctx,
        
        
        baseUrl = "https://probe.invalid/",
        authProvider = null,
        useCustomCa = true,                    
        removeHeader = true,
        connectTimeoutSeconds = 8L,
        readWriteTimeoutSeconds = 8L,
        useHttpClientInterceptor = false,      
        proxyConfigProvider = proxyConfigProvider,
    )
}
