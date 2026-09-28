package com.difft.android.websocket.api.messages

object ChatDataMessageFlag {
    const val END_CONVERSATION_PREVIEW_MGS = 1

    /** `PUT /api/v1/queue/empty` — the offline backlog of this connection has been fully pushed. */
    const val END_QUEUE = 2
}