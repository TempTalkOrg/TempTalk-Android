package com.difft.android.network.responses

data class PendingMessageResponse(
    val messages: List<PendingMessage>?,
    val more: Boolean
)

data class PendingMessage(
    val type: Int,
    val relay: String?,
    val timestamp: Long,
    val source: String?,
    val sourceDevice: Int,
    val message: String?,
    val content: String?,
    val systemShowTimestamp: Long,
    val sequenceId: Long,
    val notifySequenceId: Long,
    val msgType: Int,
    val conversation: String?,
    val identityKey: String?,
    val peerContext: String?,
)