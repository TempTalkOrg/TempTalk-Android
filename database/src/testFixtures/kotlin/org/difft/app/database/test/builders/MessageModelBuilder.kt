package org.difft.app.database.test.builders

import org.difft.app.database.models.MessageModel

const val DEFAULT_ROOM_ID: String = "peer"
const val DEFAULT_PEER_ID: String = "peer"

/**
 * A minimally populated [MessageModel] for pagination / hydration tests.
 *
 * [timeStamp] defaults to [systemShowTimestamp] because the two agree for every normal message;
 * pass them apart only when a case needs the failure-anchoring distinction (the window is keyed on
 * `systemShowTimestamp`, `ScrollAction.ToMessage` on `timeStamp`).
 */
fun buildMessageModel(
    id: String,
    systemShowTimestamp: Long,
    timeStamp: Long = systemShowTimestamp,
    roomId: String = DEFAULT_ROOM_ID,
    fromWho: String = DEFAULT_PEER_ID,
    type: Int = MessageModel.TYPE_TEXT,
    messageText: String? = "msg-$id",
): MessageModel = MessageModel().apply {
    this.id = id
    this.systemShowTimestamp = systemShowTimestamp
    this.timeStamp = timeStamp
    this.roomId = roomId
    this.fromWho = fromWho
    this.type = type
    this.messageText = messageText
}

/**
 * [count] messages with strictly increasing timestamps, ids `"m0".."m{count-1}"`.
 *
 * Strictly unique timestamps are a precondition of every pagination case: SQLite does not order
 * ties on `ORDER BY systemShowTimestamp` deterministically, and an in-memory fake cannot reproduce
 * that non-determinism — cases must stay out of that domain.
 */
fun buildMessageSequence(
    count: Int,
    startTs: Long = 1_000L,
    stepMs: Long = 1_000L,
    idPrefix: String = "m",
    roomId: String = DEFAULT_ROOM_ID,
    fromWho: String = DEFAULT_PEER_ID,
): List<MessageModel> = (0 until count).map { index ->
    buildMessageModel(
        id = "$idPrefix$index",
        systemShowTimestamp = startTs + index * stepMs,
        roomId = roomId,
        fromWho = fromWho,
    )
}
