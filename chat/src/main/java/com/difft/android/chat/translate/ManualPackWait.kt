package com.difft.android.chat.translate

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.translate.model.TranslateModelStore
import kotlinx.coroutines.Deferred

/**
 * Runs a manual translation and, when it answers [TranslateResult.ModelNotReady] — typically the
 * detected source language's pack, which only the engine can know is needed — waits for the missing
 * packs and submits exactly once more. The wait ends when the packs land or the store gives up on
 * the download (failed, or stuck with no progress); there is no wall-clock cap. The engine already
 * asked for the download in MANUAL mode, so the wait joins it, or restarts it when it is stuck.
 *
 * At most one extra round per call, so a pack that turns ready but still fails can never loop.
 * Returns the last result; a [TranslateResult.ModelNotReady] means the packs are still not on the
 * device (download failed, stuck, or another pack holds the single download slot).
 */
internal suspend fun submitWaitingForPacks(
    modelStore: TranslateModelStore,
    stillWanted: () -> Boolean,
    submit: () -> Deferred<TranslateResult>,
): TranslateResult {
    val first = submit().await()
    if (first !is TranslateResult.ModelNotReady || !stillWanted()) return first
    L.i { "[Translate] manual waiting for packs missing=${first.missing.joinToString { it.code }}" }
    if (!modelStore.awaitReady(first.missing) || !stillWanted()) return first
    return submit().await()
}
