package com.difft.android.chat.ui

import com.difft.android.base.utils.sampleAfterFirst
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Debounced "reload this room's read info" signal.
 *
 * Read-receipt updates arrive unthrottled — one per peer receipt — and each one used to re-run the
 * whole message-assembly pipeline, which made this the only unsampled full-window trigger in the
 * conversation screen. Sampling is safe here and only here because the collector re-reads the FULL
 * read-info list from the database on every tick: a dropped signal costs at most the sampling
 * period in latency, never data. The same operator with the same period gates the message-change
 * seam, so both triggers now share one upper bound.
 *
 * Split out of [ChatMessageViewModel] so the operator chain can be driven over an injectable
 * upstream on virtual time. COLD by construction, exactly like the message-change seam: every
 * `collect` rebuilds the sampling state, so a re-collection starts on the leading edge.
 */
internal fun readInfoSignals(upstream: Flow<String>, roomId: String): Flow<Unit> =
    upstream
        .filter { it == roomId }
        .sampleAfterFirst(READ_INFO_SAMPLE_PERIOD_MS)
        .map { }

/** Same period as the message-change seam: both gate the same assembly pass. */
internal const val READ_INFO_SAMPLE_PERIOD_MS = 500L
