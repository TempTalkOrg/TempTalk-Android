package com.difft.android.network.requests

/**
 * Body of `POST v1/messages/setPriorConversation`: tells the server which conversation the user is
 * currently looking at, so the offline backlog of that conversation is delivered first.
 *
 * Exactly one field is set — [number] for a 1:1 conversation, [gid] for a group. Nulls are omitted
 * by the client's default Gson (serializeNulls = false).
 */
data class SetPriorConversationRequestBody(
    val number: String? = null,
    val gid: String? = null
)
