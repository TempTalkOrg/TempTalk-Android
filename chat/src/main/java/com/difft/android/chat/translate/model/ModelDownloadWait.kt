package com.difft.android.chat.translate.model

import com.difft.android.chat.translate.ModelId
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.selects.select


internal enum class DownloadWaitOutcome { COMPLETED, STUCK, FAILED }














internal suspend fun awaitDownload(
    job: Job,
    model: ModelId,
    state: StateFlow<Map<ModelId, ModelState>>,
): DownloadWaitOutcome = coroutineScope {
    val blocked = async {
        val terminal = state.map { it[model] }
            .dropWhile { it !is ModelState.Downloading }
            .first { it is ModelState.Failed || (it is ModelState.Downloading && it.stuck) }
        if (terminal is ModelState.Failed) DownloadWaitOutcome.FAILED else DownloadWaitOutcome.STUCK
    }
    select {
        job.onJoin { DownloadWaitOutcome.COMPLETED }
        blocked.onAwait { it }
    }.also { blocked.cancel() }
}
