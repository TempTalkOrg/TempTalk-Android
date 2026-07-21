package com.difft.android.base.utils

import com.difft.android.base.log.WCDBKeyUnavailableException
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName

/**
 * Shared fail-soft [CoroutineExceptionHandler] for background scopes that may touch the
 * encrypted WCDB and can therefore surface a [WCDBKeyUnavailableException].
 *
 * Swallows only [WCDBKeyUnavailableException] (top-level type, no cause-chain walk) — the key is
 * process-lifetime dead and self-heals on the next cold start, so this must not crash. Every
 * swallow is logged. Every other throwable is forwarded to
 * [Thread.uncaughtExceptionHandler] explicitly — a CEH otherwise silently swallows it, which would
 * mask unrelated bugs.
 */
val dbKeyFailSoftExceptionHandler = CoroutineExceptionHandler { context, throwable ->
    if (throwable is WCDBKeyUnavailableException) {
        val scopeName = context[CoroutineName]?.name ?: "unnamed-scope"
        L.e { "[DBKeyFailSoft] swallowed WCDBKeyUnavailableException on $scopeName: ${throwable.stackTraceToString()}" }
    } else {
        // Explicit forward: a CEH otherwise silently swallows, masking unrelated bugs.
        Thread.currentThread().uncaughtExceptionHandler
            ?.uncaughtException(Thread.currentThread(), throwable)
    }
}
