package com.difft.android.base.utils

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.Display
import androidx.core.content.ContextCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

/**
 * [OrientationDisplayWatcher] against Robolectric's DisplayManager: `changeDisplay` is the
 * fold/unfold the window manager never reported to the activity, `addDisplay` is an external
 * monitor that must never decide the activity's orientation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OrientationDisplayWatcherTest {

    private val delivered = mutableListOf<Display>()

    private fun startedWatcher(): Pair<Activity, OrientationDisplayWatcher> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val watcher = OrientationDisplayWatcher(activity) { delivered += it }
        watcher.start()
        idle()
        delivered.clear() // drop the start() snapshot; the cases below assert on later events
        return activity to watcher
    }

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `start replays the host display once`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val watcher = OrientationDisplayWatcher(activity) { delivered += it }

        watcher.start()
        idle()

        assertEquals(
            "onStart must re-evaluate the current display: a fold/unfold while the activity was stopped" +
                " is never observed by the listener, and a letterboxing OS delivers no other callback",
            1,
            delivered.size,
        )
        assertEquals(ContextCompat.getDisplayOrDefault(activity).displayId, delivered.single().displayId)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `a change on the host display is delivered with that display`() {
        val (activity, _) = startedWatcher()
        val hostId = ContextCompat.getDisplayOrDefault(activity).displayId

        ShadowDisplayManager.changeDisplay(hostId, "w859dp-h608dp")
        idle()

        assertEquals("the unfold of the display hosting the activity is the event the watcher exists for", 1, delivered.size)
        assertEquals(hostId, delivered.single().displayId)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `a change on another display is ignored`() {
        startedWatcher()
        val externalId = ShadowDisplayManager.addDisplay("w1920dp-h1080dp")
        idle()
        delivered.clear()

        ShadowDisplayManager.changeDisplay(externalId, "w1280dp-h720dp")
        idle()

        assertEquals(
            "an external monitor (DeX / dock) resizing must not decide the phone activity's orientation",
            0,
            delivered.size,
        )
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `stop unregisters the listener`() {
        val (activity, watcher) = startedWatcher()
        watcher.stop()

        ShadowDisplayManager.changeDisplay(ContextCompat.getDisplayOrDefault(activity).displayId, "w859dp-h608dp")
        idle()

        assertEquals("after onStop nothing may be delivered", 0, delivered.size)
    }
}
