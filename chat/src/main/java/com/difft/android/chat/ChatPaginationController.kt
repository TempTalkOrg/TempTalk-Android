package com.difft.android.chat

import com.difft.android.ChatNormalPaginationControllerFactory
import com.difft.android.chat.pagination.WcdbChatMessageWindowSource
import difft.android.messageserialization.For
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.difft.app.database.WCDB

// care of  ConversationUtils.messagesUpdate.filter { it == forWhat.id }
/**
 * The single assembly point that knows about WCDB: it adapts the database world to the pagination
 * world by building the room-scoped [WcdbChatMessageWindowSource] the delegate runs on. Keeping
 * that knowledge here is what lets `ChatNormalPaginationController` stay winq-free and unit
 * testable on the host JVM.
 */
class ChatPaginationController @AssistedInject constructor(
    @Assisted
    private val forWhat: For,
    private val wcdb: WCDB,
    private val chatNormalPaginationControllerFactory: ChatNormalPaginationControllerFactory
) : IChatPaginationController by chatNormalPaginationControllerFactory.create(
    forWhat,
    WcdbChatMessageWindowSource(wcdb, forWhat)
)
