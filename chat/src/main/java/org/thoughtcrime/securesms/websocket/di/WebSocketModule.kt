package org.thoughtcrime.securesms.websocket.di

import com.difft.android.WebSocketConnectionFactory
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.application
import com.difft.android.network.UrlManager
import com.difft.android.network.config.UserAgentManager
import com.difft.android.network.push.SignalServiceTrustStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.difft.android.websocket.api.websocket.WebSocketKeepAliveSender
import com.difft.android.websocket.internal.configuration.SignalCdnUrl
import com.difft.android.websocket.internal.configuration.SignalServiceConfiguration
import com.difft.android.websocket.internal.configuration.SignalServiceUrl
import java.util.Optional
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WebSocketModule {

    @Provides
    @Singleton
    fun chatDataWebSocketConfiguration(
        urlManager: UrlManager,
    ): SignalServiceConfiguration {
        val headers = mapOf(
            "Authorization" to SecureSharedPrefsUtil.getBasicAuth(),
            "User-Agent" to UserAgentManager.getUserAgent()
        )

        val configuration = SignalServiceUrl(
            urlManager.getChatWebsocketUrl(),
            headers,
            SignalServiceTrustStore(application),
            null
        )
        val cdnUrls = listOf(
            SignalCdnUrl(
                "https://www.google.co.uz/cdn",
                configuration.trustStore
            )
        ).toTypedArray()
        val cdnUrlsTwo = listOf(
            SignalCdnUrl(
                "https://www.google.com.ua/cdn",
                configuration.trustStore
            )
        ).toTypedArray()
        return SignalServiceConfiguration(
            arrayOf(configuration),
            mapOf(0 to cdnUrls, 2 to cdnUrlsTwo),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyList(),
            Optional.empty(),
            Optional.empty(),
            ByteArray(0)
        )
    }

    @Named("chat-data")
    @Singleton
    @Provides
    fun provideUChatDataWebSocketConnection(
        urlManager: UrlManager,
        webSocketConnectionFactory: WebSocketConnectionFactory,
    ) = webSocketConnectionFactory.createWebSocketConnection(
        {
            SecureSharedPrefsUtil.getBasicAuth()
        },
        {
            urlManager.getChatWebsocketUrl()
        },
        WebSocketKeepAliveSender()
    )
}