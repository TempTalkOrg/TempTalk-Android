package com.difft.android

import android.content.Intent
import com.difft.android.chat.ChatNormalPaginationController
import com.difft.android.chat.ChatPaginationController
import com.difft.android.chat.group.GroupChatContentActivity.Companion.groupID
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.chat.ui.ChatActivity.Companion.contactorID
import com.difft.android.chat.ui.ChatActivity.Companion.jumpMessageTimeStamp
import com.difft.android.chat.ui.ChatMessageViewModel
import difft.android.messageserialization.For
import difft.android.messageserialization.model.TextMessage
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobs.PushReadReceiptSendJob
import org.thoughtcrime.securesms.jobs.PushTextSendJob
import org.thoughtcrime.securesms.jobs.TextMessageSender
import com.difft.android.websocket.api.websocket.KeepAliveSender
import com.difft.android.websocket.internal.push.OutgoingPushMessage
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import com.difft.android.websocket.internal.websocket.WebSocketConnection

@AssistedFactory
interface WebSocketConnectionFactory {

    fun createWebSocketConnection(
        @Assisted("auth")
        basicAuth: () -> String,
        @Assisted("urlGetter")
        webSocketUrlGetter: () -> String,
        keepAliveSender: KeepAliveSender,
    ): WebSocketConnection
}

@AssistedFactory
interface PushTextSendJobFactory {
    /**
     * parameters: Job.Parameters? only used in JobManager, when creating a job from the PushTextSendJob's Factory
     */
    fun create(
        parameters: Job.Parameters?,
        textMessage: TextMessage,
        notification: OutgoingPushMessage.Notification? = null,
    ): PushTextSendJob
}

@AssistedFactory
interface TextMessageSenderFactory {
    /**
     * parameters: Job.Parameters? only used in JobManager, when creating a job from the PushTextSendJob's Factory
     */
    fun create(
        textMessage: TextMessage,
        notification: OutgoingPushMessage.Notification? = null,
    ): TextMessageSender
}


@AssistedFactory
interface PushReadReceiptSendJobFactory {
    /**
     * parameters: Job.Parameters? only used in JobManager, when creating a job from the PushReadReceiptSendJob's Factory
     */
    fun create(
        @Assisted
        parameters: Job.Parameters?,
        @Assisted("recipientId")
        recipientId: String,
        @Assisted
        messageSentTimestamps: List<Long>,
        @Assisted
        readPosition: SignalServiceProtos.ReadPosition,
        @Assisted
        mode: SignalServiceProtos.Mode,
        @Assisted("conversationId")
        conversationId: String
    ): PushReadReceiptSendJob
}

@AssistedFactory
interface ChatNormalPaginationControllerFactory {
    fun create(
        forWhat: For,
    ): ChatNormalPaginationController
}

@AssistedFactory
interface ChatPaginationControllerFactory {
    fun create(
        forWhat: For
    ): ChatPaginationController
}

@AssistedFactory
interface ChatMessageViewModelFactory {
    fun create(
        forWhat: For,
        jumpMessageTimeStamp: Long?
    ): ChatMessageViewModel
}

fun ChatMessageViewModelFactory.create(intent: Intent): ChatMessageViewModel {
    val forWhat = if (!intent.groupID.isNullOrEmpty()) {
        For.Group(intent.groupID!!)
    } else {
        For.Account(intent.contactorID!!)
    }
    return create(forWhat, intent.jumpMessageTimeStamp)
}

@AssistedFactory
interface ChatSettingViewModelFactory {
    fun create(
        forWhat: For
    ): ChatSettingViewModel
}