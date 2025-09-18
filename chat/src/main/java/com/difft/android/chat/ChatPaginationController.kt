package com.difft.android.chat

import com.difft.android.ChatNormalPaginationControllerFactory
import difft.android.messageserialization.For
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

// care of  ConversationUtils.messagesUpdate.filter { it == forWhat.id }
class ChatPaginationController @AssistedInject constructor(
    @Assisted
    private val forWhat: For,
    private val chatNormalPaginationControllerFactory: ChatNormalPaginationControllerFactory
) : IChatPaginationController by (chatNormalPaginationControllerFactory.create(forWhat))