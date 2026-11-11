package com.difft.android.chat

import com.difft.android.ChatNormalPaginationControllerFactory
import com.difft.android.chat.pagination.WcdbChatMessageWindowSource
import difft.android.messageserialization.For
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.difft.app.database.WCDB








class ChatPaginationController @AssistedInject constructor(
    @Assisted
    private val forWhat: For,
    private val wcdb: WCDB,
    private val chatNormalPaginationControllerFactory: ChatNormalPaginationControllerFactory
) : IChatPaginationController by chatNormalPaginationControllerFactory.create(
    forWhat,
    WcdbChatMessageWindowSource(wcdb, forWhat)
)
