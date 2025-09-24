package com.difft.android

import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.base.utils.IMessageNotificationUtil
import com.difft.android.chat.common.ConversationManagerImpl
import com.difft.android.network.config.GlobalConfigsManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import org.thoughtcrime.securesms.util.NewMessageEncryptor
import org.thoughtcrime.securesms.websocket.monitor.WebSocketHealthMonitor
import com.difft.android.websocket.api.ConversationManager
import com.difft.android.websocket.api.util.INewMessageContentEncryptor
import com.difft.android.websocket.api.websocket.HealthMonitor
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ChatHiltBindsModule {
    @Binds
    abstract fun bindNewMessageEncryptor(newMessageEncryptor: NewMessageEncryptor): INewMessageContentEncryptor

    //Bind ConversationManagerImpl to ConversationManager
    @Binds
    abstract fun bindConversationManager(conversationManagerImpl: ConversationManagerImpl): ConversationManager

    //Bind ChativeWebSocketHealthMonitor to HealthMonitor
    @Binds
    abstract fun bindWebSocketHealthMonitor(webSocketHealthMonitor: WebSocketHealthMonitor): HealthMonitor

    @Binds
    abstract fun bindMessageNotificationUtil(messageNotificationUtil: MessageNotificationUtil): IMessageNotificationUtil

    @Binds
    @Singleton
    abstract fun bindGlobalConfigsManager(globalConfigsManager: GlobalConfigsManager): IGlobalConfigsManager
}