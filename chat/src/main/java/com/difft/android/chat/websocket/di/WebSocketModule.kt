package com.difft.android.chat.websocket.di

import com.difft.android.base.utils.globalServices

import com.difft.android.WebSocketConnectionFactory
import com.difft.android.network.UrlManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.difft.android.websocket.api.websocket.WebSocketKeepAliveSender
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WebSocketModule {

    @Named("chat-data")
    @Singleton
    @Provides
    fun provideUChatDataWebSocketConnection(
        urlManager: UrlManager,
        webSocketConnectionFactory: WebSocketConnectionFactory,
    ) = webSocketConnectionFactory.createWebSocketConnection(
        {
            (globalServices.userManager.getUserData()?.baseAuth ?: "")
        },
        {
            urlManager.getChatWebsocketUrl()
        },
        WebSocketKeepAliveSender()
    )
}
