package com.difft.android.network.responses


data class ReadPositionResponse(
    val hasMore: Boolean,
    val readPositions: List<ReadPosition>?
)

data class ReadPosition(
    val maxNotifySequenceId: Long,
    val maxServerTimestamp: Long,
    val readAt: Long,
    val source: String,
    val maxSequenceId: Long,
)