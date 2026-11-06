package com.difft.android.call.ui.screenshare

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import com.difft.android.base.utils.OrientationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [applyScreenShareOrientation] on a phone-sized display, flat and half open (design decision 38). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ScreenShareOrientationTest {

    // A pending unfold release is process-wide state in OrientationPolicy; start clean.
    @Before
    fun clearPendingUnfold() = OrientationPolicy.clearUnfoldPending()

    private fun createActivity(): Activity = Robolectric.buildActivity(Activity::class.java).create().get()

    @Test
    @Config(qualifiers = "w360dp-h880dp")
    fun `a phone lying flat turns landscape for a share`() {
        val activity = createActivity()
        assertNull(applyScreenShareOrientation(activity))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
    }

    @Test
    @Config(qualifiers = "w360dp-h880dp")
    fun `a flip phone standing half open keeps the portrait lock`() {
        val activity = createActivity()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, applyScreenShareOrientation(activity, halfOpen = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
    }

    @Test
    @Config(qualifiers = "w360dp-h880dp")
    fun `a share turned landscape turns back upright once the phone is half open`() {
        val activity = createActivity()
        applyScreenShareOrientation(activity)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, applyScreenShareOrientation(activity, halfOpen = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
        // Laid flat again, the share turns landscape again.
        assertNull(applyScreenShareOrientation(activity, halfOpen = false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
    }
}
