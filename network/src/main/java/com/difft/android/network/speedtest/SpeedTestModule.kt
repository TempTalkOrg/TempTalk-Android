package com.difft.android.network.speedtest

import android.content.Context
import com.difft.android.network.ca.OfficialSSLSocketFactoryCreator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class SpeedTest

/**
 * Provides a standalone OkHttpClient for speed testing.
 *
 * Cannot reuse ChativeHttpClient because it depends on UrlManager,
 * which depends on DomainSpeedTestCoordinator -> DomainSpeedTester,
 * creating a circular dependency.
 */
@InstallIn(SingletonComponent::class)
@Module
object SpeedTestModule {

    @SpeedTest
    @Provides
    @Singleton
    fun provideSpeedTestClient(
        @ApplicationContext context: Context
    ): OkHttpClient {
        val sslCreator = OfficialSSLSocketFactoryCreator(context)
        val connectionSpec = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
            .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
            .build()

        return OkHttpClient.Builder()
            .sslSocketFactory(sslCreator.socketFactory, sslCreator.trustManager)
            .connectionSpecs(listOf(connectionSpec))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
