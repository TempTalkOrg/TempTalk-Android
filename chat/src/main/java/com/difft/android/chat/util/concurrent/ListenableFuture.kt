package com.difft.android.chat.util.concurrent

import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

/**
 * A [Future] augmented with a listener hook that fires once the value is available.
 * Retained alongside the (Java-for-now) [com.difft.android.chat.util.ViewUtil] animation APIs.
 */
interface ListenableFuture<T> : Future<T> {
    fun addListener(listener: Listener<T>)

    interface Listener<T> {
        fun onSuccess(result: T)
        fun onFailure(e: ExecutionException)
    }
}
