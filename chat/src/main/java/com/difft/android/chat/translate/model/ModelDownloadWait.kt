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

/** How a wait on an in-flight model download ended. */
internal enum class DownloadWaitOutcome { COMPLETED, STUCK, FAILED }

/**
 * Waits for [job] — the in-flight download of [model] — until the first of: the job completing,
 * or [state] reporting the model `Failed` or `Downloading(stuck = true)`. There is deliberately no
 * wall-clock cap: a download still making progress is not a failure however slow the link, and
 * the no-progress watchdog already bounds one that has silently stalled (a mid-download network
 * loss pauses the system download and resumes it when the network returns).
 * Never cancels [job] itself: the download keeps running, so a later wait joins it again (a
 * stuck one is restarted by the next user retry, not by this function). A [job] that was cancelled
 * by such a restart also joins as [DownloadWaitOutcome.COMPLETED]; the caller checks readiness.
 *
 * States before the first `Downloading` are skipped, so a `Failed` left by a previous attempt is
 * not mistaken for this one's outcome before the new attempt has published its state.
 */
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
