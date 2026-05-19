package com.difft.android.base.concurrent

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.util.concurrent.Executor

/**
 * Java-friendly facade over Kotlin Coroutines [Dispatchers].
 *
 * Provides [Executor]-shaped access for Java callers that still need executor-style
 * scheduling. Kotlin callers should prefer suspend functions, [kotlinx.coroutines.launch],
 * or [kotlinx.coroutines.flow.Flow] directly.
 *
 * Replaces the legacy `TTExecutors` global pools. Each member here is backed by a shared
 * coroutine dispatcher, so there is no extra thread pool created and lifetime is managed
 * by the coroutines runtime.
 *
 * Usage from Java:
 * ```java
 * AppExecutors.Default.execute(() -> doBackgroundWork());
 * AppExecutors.mainHandler().post(() -> updateUi());
 * ```
 */
object AppExecutors {

    /** CPU-bound work. Backed by [Dispatchers.Default]. */
    @JvmField
    val Default: Executor = Dispatchers.Default.asExecutor()

    /** Blocking IO (file/network). Backed by [Dispatchers.IO]. */
    @JvmField
    val IO: Executor = Dispatchers.IO.asExecutor()

    /** Main / UI thread. Backed by [Dispatchers.Main]. */
    @JvmField
    val Main: Executor = Dispatchers.Main.asExecutor()

    /** Lazy main-looper handler for callers that need [Handler] APIs (postDelayed, removeCallbacks). */
    @JvmStatic
    fun mainHandler(): Handler = MainHandlerHolder.handler

    private object MainHandlerHolder {
        val handler: Handler = Handler(Looper.getMainLooper())
    }
}
