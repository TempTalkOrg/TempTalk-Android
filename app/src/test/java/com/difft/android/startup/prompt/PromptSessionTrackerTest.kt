package com.difft.android.startup.prompt

import android.app.Activity
import android.app.Application
import androidx.activity.ComponentActivity
import io.mockk.every
import io.mockk.spyk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Configuration-change relaunches (fold, night mode, locale) never end a prompt session; a real stop
 * afterwards still does (#1355 §4.3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PromptSessionTrackerTest {

    private val tracker = PromptSessionTracker()

    private fun activity(changing: Boolean = false): Activity {
        val real = Robolectric.buildActivity(ComponentActivity::class.java).get()
        return spyk(real).also { every { it.isChangingConfigurations } returns changing }
    }

    private fun relaunch(): Activity {
        tracker.onActivityStopped(activity(changing = true))
        val replacement = activity()
        tracker.onActivityStarted(replacement)
        return replacement
    }

    @Test
    fun `first start begins a session and the last real stop ends it`() {
        val a = activity()
        tracker.onActivityStarted(a)
        assertEquals(PromptSession(1, active = true), tracker.session.value)
        tracker.onActivityStopped(a)
        assertEquals(PromptSession(1, active = false), tracker.session.value)
        tracker.onActivityStarted(a)
        assertEquals(PromptSession(2, active = true), tracker.session.value)
    }

    @Test
    fun `a relaunch keeps the session and a real stop after it ends it`() {
        tracker.onActivityStarted(activity())
        relaunch()
        assertEquals(PromptSession(1, active = true), tracker.session.value)

        val again = relaunch()
        assertEquals(PromptSession(1, active = true), tracker.session.value)

        tracker.onActivityStopped(again)
        assertEquals(PromptSession(1, active = false), tracker.session.value)
    }

    @Test
    fun `two activities relaunched together keep the session`() {
        tracker.onActivityStarted(activity())
        tracker.onActivityStarted(activity())
        tracker.onActivityStopped(activity(changing = true))
        tracker.onActivityStopped(activity(changing = true))
        tracker.onActivityStarted(activity())
        tracker.onActivityStarted(activity())
        assertEquals(PromptSession(1, active = true), tracker.session.value)

        tracker.onActivityStopped(activity())
        assertEquals(PromptSession(1, active = true), tracker.session.value)
        tracker.onActivityStopped(activity())
        assertEquals(PromptSession(1, active = false), tracker.session.value)
    }

    @Test
    fun `an activity on top of another does not start a new session`() {
        val list = activity()
        tracker.onActivityStarted(list)
        val settings = activity()
        tracker.onActivityStarted(settings)
        tracker.onActivityStopped(list)
        tracker.onActivityStopped(settings)
        tracker.onActivityStarted(list)
        assertEquals(PromptSession(2, active = true), tracker.session.value)
    }
}
