package util.concurrent

import android.os.AsyncTask
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.difft.android.base.concurrent.AppExecutors
import java.util.concurrent.Executor


















object SimpleTask {

    
    fun interface BackgroundTask<E> {
        fun run(): E
    }

    
    fun interface ForegroundTask<E> {
        fun run(result: E)
    }

    




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

    



    @JvmStatic
    fun <E> run(backgroundTask: BackgroundTask<E>, foregroundTask: ForegroundTask<E>) {
        run(AppExecutors.Default, backgroundTask, foregroundTask)
    }

    



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
