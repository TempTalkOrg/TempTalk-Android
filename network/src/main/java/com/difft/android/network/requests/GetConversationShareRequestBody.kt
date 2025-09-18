package com.difft.android.network.requests


data class GetConversationShareRequestBody(
    val conversations: List<String>?,
    val checkAsk: Boolean
)

data class ConversationShareRequestBody(
    val messageExpiry: Long?
)

data class ConversationShareResponse(
    val conversation: String?,
    val lastOperator: String?,
    val messageExpiry: Long,
    val ver: Int,
    val askedVersion: Int,
    val messageClearAnchor: Long
)

data class GetConversationShareResponse(
    val conversations: List<ConversationShareResponse>?
)