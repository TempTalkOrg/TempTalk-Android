package util.concurrent

import android.os.AsyncTask
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.difft.android.base.concurrent.AppExecutors
import java.util.concurrent.Executor

/**
 * Lifecycle-aware background task helper, similar in spirit to the deprecated [AsyncTask] but
 * lambda-friendly. The foreground callback is guarded by [Lifecycle] state so it will only run
 * while the owner is in a valid (at least `CREATED`) state.
 *
 * Background execution uses [AppExecutors.Default] unless an explicit [Executor] is supplied.
 * Foreground dispatch uses [AppExecutors.mainHandler].
 *
 * Prefer Kotlin coroutines in new Kotlin code:
 * ```
 * lifecycleScope.launch {
 *     val result = withContext(Dispatchers.Default) { backgroundWork() }
 *     foregroundWork(result)
 * }
 * ```
 * This Java-friendly wrapper is kept to avoid touching its remaining Java caller
 * (ImageEditorFragment) during the thread-model migration.
 */
object SimpleTask {

    /** Supplier for the background result. */
    fun interface BackgroundTask<E> {
        fun run(): E
    }

    /** Consumer for the foreground callback, receiving the background result. */
    fun interface ForegroundTask<E> {
        fun run(result: E)
    }

    /**
     * Runs [backgroundTask] on [AppExecutors.Default]. If [lifecycle] is at least `CREATED`
     * both before dispatch and after the background computation, [foregroundTask] is posted
     * to the main thread.
     */
    @JvmStatic
    fun <E> run(
        lifecycle: Lifecycle,
        backgroundTask: BackgroundTask<E>,
        foregroundTask: ForegroundTask<E>,
    ) {
        if (!isValid(lifecycle)) return

        AppExecutors.Default.execute {
            val result = backgroundTask.run()
            if (isValid(lifecycle)) {
                AppExecutors.mainHandler().post {
                    if (isValid(lifecycle)) {
                        foregroundTask.run(result)
                    }
                }
            }
        }
    }

    /**
     * Like [run] but waits for the lifecycle to become at least `CREATED` before kicking off the
     * background work. Useful when the owner may still be in `INITIALIZED`.
     */
    @JvmStatic
    fun <E> runWhenValid(
        lifecycle: Lifecycle,
        backgroundTask: BackgroundTask<E>,
        foregroundTask: ForegroundTask<E>,
    ) {
        lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (!isValid(lifecycle)) return
                lifecycle.removeObserver(this)

                AppExecutors.Default.execute {
                    val result = backgroundTask.run()
                    if (isValid(lifecycle)) {
                        AppExecutors.mainHandler().post {
                            if (isValid(lifecycle)) {
                                foregroundTask.run(result)
                            }
                        }
                    }
                }
            }
        })
    }

    /**
     * Runs [backgroundTask] on [AppExecutors.Default] and forwards the result to [foregroundTask]
     * on the main thread. No lifecycle guard.
     */
    @JvmStatic
    fun <E> run(backgroundTask: BackgroundTask<E>, foregroundTask: ForegroundTask<E>) {
        run(AppExecutors.Default, backgroundTask, foregroundTask)
    }

    /**
     * Runs [backgroundTask] on the given [executor] and forwards the result to [foregroundTask]
     * on the main thread. No lifecycle guard.
     */
    @JvmStatic
    fun <E> run(executor: Executor, backgroundTask: BackgroundTask<E>, foregroundTask: ForegroundTask<E>) {
        executor.execute {
            val result = backgroundTask.run()
            AppExecutors.mainHandler().post { foregroundTask.run(result) }
        }
    }

    private fun isValid(lifecycle: Lifecycle): Boolean =
        lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)
}
