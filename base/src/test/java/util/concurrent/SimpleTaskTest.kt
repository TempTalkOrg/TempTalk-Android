package util.concurrent

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the decoupled [SimpleTask] utility after migration off TTExecutors/ThreadUtil.
 *
 * These tests verify the lifecycle-aware execution contract that callers
 * (notably `ImageEditorFragment`) depend on:
 *  - foregroundTask never runs if lifecycle is destroyed before dispatch
 *  - foregroundTask is dispatched to the main thread
 *  - plain two-arg overload runs background + foreground with no lifecycle guard
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SimpleTaskTest {

    private val mainLooper get() = shadowOf(Looper.getMainLooper())

    /** Minimal LifecycleOwner for tests — avoids the lifecycle-runtime-testing dependency. */
    private class FakeLifecycleOwner(initial: Lifecycle.State) : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { currentState = initial }
        override val lifecycle: Lifecycle get() = registry
        fun moveTo(state: Lifecycle.State) { registry.currentState = state }
    }

    @Test
    fun `run(lifecycle) dispatches background and foreground when lifecycle is valid`() {
        val owner = FakeLifecycleOwner(Lifecycle.State.STARTED)
        val fgLatch = CountDownLatch(1)
        val bgRan = AtomicInteger(0)
        val fgResult = AtomicInteger(-1)

        SimpleTask.run(
            owner.lifecycle,
            { bgRan.incrementAndGet(); 42 },
            { value ->
                fgResult.set(value)
                fgLatch.countDown()
            }
        )

        // Wait for background task (runs on AppExecutors.Default which is Dispatchers.Default).
        // Pump the main looper so the posted foreground callback can execute.
        val started = System.nanoTime()
        while (!fgLatch.await(0, TimeUnit.MILLISECONDS) &&
               (System.nanoTime() - started) < TimeUnit.SECONDS.toNanos(2)
        ) {
            mainLooper.idle()
            Thread.sleep(5)
        }
        assertTrue(fgLatch.count == 0L, "foregroundTask should have fired")
        assertEquals(1, bgRan.get())
        assertEquals(42, fgResult.get())
    }

    @Test
    fun `run(lifecycle) skips dispatch when lifecycle already destroyed`() {
        val owner = FakeLifecycleOwner(Lifecycle.State.INITIALIZED)
        owner.moveTo(Lifecycle.State.CREATED)
        owner.moveTo(Lifecycle.State.DESTROYED)
        val bgRan = AtomicInteger(0)
        val fgRan = AtomicInteger(0)

        SimpleTask.run(
            owner.lifecycle,
            { bgRan.incrementAndGet(); 1 },
            { fgRan.incrementAndGet() }
        )

        // Let any posted tasks try to run.
        mainLooper.idle()
        Thread.sleep(100)
        mainLooper.idle()

        assertEquals(0, bgRan.get(), "background task must not start when lifecycle is destroyed")
        assertEquals(0, fgRan.get())
    }

    @Test
    fun `run(executor) dispatches foreground on main thread`() {
        val executor = Dispatchers.Default.asExecutor()
        val fgLatch = CountDownLatch(1)
        val fgOnMain = java.util.concurrent.atomic.AtomicBoolean(false)

        SimpleTask.run(
            executor,
            { "hello" },
            {
                fgOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                fgLatch.countDown()
            }
        )

        val started = System.nanoTime()
        while (!fgLatch.await(0, TimeUnit.MILLISECONDS) &&
               (System.nanoTime() - started) < TimeUnit.SECONDS.toNanos(2)
        ) {
            mainLooper.idle()
            Thread.sleep(5)
        }
        assertTrue(fgLatch.count == 0L, "foreground task should have fired")
        assertTrue(fgOnMain.get(), "foreground task must execute on main thread")
    }

    @Test
    fun `two-arg run executes both background and foreground without lifecycle`() {
        val fgLatch = CountDownLatch(1)
        val result = AtomicInteger(-1)

        SimpleTask.run(
            { 123 },
            { v ->
                result.set(v)
                fgLatch.countDown()
            }
        )

        val started = System.nanoTime()
        while (!fgLatch.await(0, TimeUnit.MILLISECONDS) &&
               (System.nanoTime() - started) < TimeUnit.SECONDS.toNanos(2)
        ) {
            mainLooper.idle()
            Thread.sleep(5)
        }
        assertEquals(123, result.get())
    }
}
