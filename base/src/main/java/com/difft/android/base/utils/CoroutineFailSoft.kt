package com.difft.android.base.utils

import com.difft.android.base.log.WCDBKeyUnavailableException
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName











val dbKeyFailSoftExceptionHandler = CoroutineExceptionHandler { context, throwable ->
    if (throwable is WCDBKeyUnavailableException) {
        val scopeName = context[CoroutineName]?.name ?: "unnamed-scope"
        L.e { "[DBKeyFailSoft] swallowed WCDBKeyUnavailableException on $scopeName: ${throwable.stackTraceToString()}" }
    } else {
        
        Thread.currentThread().uncaughtExceptionHandler
            ?.uncaughtException(Thread.currentThread(), throwable)
    }
}
