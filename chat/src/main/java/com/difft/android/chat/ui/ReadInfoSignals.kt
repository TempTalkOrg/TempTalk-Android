package com.difft.android.chat.ui

import com.difft.android.base.utils.sampleAfterFirst
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map







internal fun readInfoSignals(upstream: Flow<String>, roomId: String): Flow<Unit> =
    upstream
        .filter { it == roomId }
        .sampleAfterFirst(READ_INFO_SAMPLE_PERIOD_MS)
        .map { }


internal const val READ_INFO_SAMPLE_PERIOD_MS = 500L
