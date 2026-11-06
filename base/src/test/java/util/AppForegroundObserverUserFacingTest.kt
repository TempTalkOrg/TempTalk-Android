package util

import android.app.Activity
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [AppForegroundObserver.hasUserFacingActivity] drives popup vs full-chat routing of notification
 * taps (#1427). Lifecycle callbacks are replayed in the orders observed on device.
 */
class AppForegroundObserverUserFacingTest {

    private val started = mutableListOf<Activity>()

    @After
    fun tearDown() {
        started.toList().forEach { stop(it) }
    }

    private fun activity(inPip: Boolean = false): Activity = mockk {
        every { isInPictureInPictureMode } returns inPip
    }

    private fun start(activity: Activity) {
        started += activity
        AppForegroundObserver.notifyActivityStarted()
    }

    private fun stop(activity: Activity) {
        started -= activity
        AppForegroundObserver.notifyActivityStopped(activity)
    }

    @Test
    fun `nothing started reads as background`() {
        assertFalse(AppForegroundObserver.hasUserFacingActivity())
    }

    @Test
    fun `resumed then paused page still reads as foreground`() {
        val index = activity()
        start(index)
        AppForegroundObserver.notifyActivityResumed(index)
        // Pulling the shade and launching the trampoline pauses the page but does not stop it.

        assertTrue(AppForegroundObserver.hasUserFacingActivity())
    }

    @Test
    fun `page restarted below the trampoline without resume reads as background`() {
        val index = activity()
        start(index)
        AppForegroundObserver.notifyActivityResumed(index)
        stop(index) // Home

        start(index) // HyperOS restarts it before the trampoline's onCreate

        assertFalse(AppForegroundObserver.hasUserFacingActivity())
    }

    @Test
    fun `call PiP alone reads as background even when the launch restarts another page`() {
        val call = activity(inPip = true)
        start(call)
        AppForegroundObserver.notifyActivityResumed(call)
        val index = activity()
        start(index)

        assertFalse(AppForegroundObserver.hasUserFacingActivity())
    }

    @Test
    fun `page that enters PiP after resuming reads as background`() {
        var inPip = false
        val call: Activity = mockk { every { isInPictureInPictureMode } answers { inPip } }
        start(call)
        AppForegroundObserver.notifyActivityResumed(call)
        inPip = true // user leaves the call screen, which shrinks into PiP without stopping

        assertFalse(AppForegroundObserver.hasUserFacingActivity())
    }

    @Test
    fun `resumed page under call PiP reads as foreground`() {
        val call = activity(inPip = true)
        start(call)
        AppForegroundObserver.notifyActivityResumed(call)
        val chat = activity()
        start(chat)
        AppForegroundObserver.notifyActivityResumed(chat)

        assertTrue(AppForegroundObserver.hasUserFacingActivity())
    }

    @Test
    fun `resume after restart reads as foreground again`() {
        val index = activity()
        start(index)
        AppForegroundObserver.notifyActivityResumed(index)
        stop(index)
        start(index)
        AppForegroundObserver.notifyActivityResumed(index)

        assertTrue(AppForegroundObserver.hasUserFacingActivity())
    }
}
