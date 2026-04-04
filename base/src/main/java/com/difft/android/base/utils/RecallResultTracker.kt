package com.difft.android.base.utils

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Tracks recall message results for recall operations.
 * Used to notify when individual recall operations complete (success or failure).
 */
object RecallResultTracker {
    
    private val _recallResults = MutableSharedFlow<RecallResult>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val recallResults: SharedFlow<RecallResult> = _recallResults.asSharedFlow()

    /**
     * Emit a recall result event
     * @param messageId The ID of the message that was recalled
     * @param success Whether the recall was successful
     */
    fun emitResult(messageId: String, success: Boolean) {
        _recallResults.tryEmit(RecallResult(messageId, success))
    }
}

data class RecallResult(
    val messageId: String,
    val success: Boolean
)
