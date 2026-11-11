package com.difft.android.chat.translate

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.translate.model.TranslateModelStore
import kotlinx.coroutines.Deferred












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
