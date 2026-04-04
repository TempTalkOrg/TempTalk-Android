package org.thoughtcrime.securesms.websocket.di

import com.difft.android.WebSocketConnectionFactory
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.network.UrlManager
import com.difft.android.network.config.UserAgentManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.difft.android.websocket.api.websocket.WebSocketKeepAliveSender
import com.difft.android.websocket.internal.configuration.ServiceConfig
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WebSocketModule {

    @Provides
    @Singleton
    fun provideServiceConfig(
        urlManager: UrlManager,
    ): ServiceConfig {
        val headers = mapOf(
            "Authorization" to SecureSharedPrefsUtil.getBasicAuth(),
            "User-Agent" to UserAgentManager.getUserAgent()
        )

        return ServiceConfig(
            url = urlManager.getChatWebsocketUrl(),
            headers = headers
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
