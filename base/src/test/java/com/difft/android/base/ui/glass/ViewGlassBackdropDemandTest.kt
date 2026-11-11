package com.difft.android.base.ui.glass

import android.content.Context
import android.graphics.RectF
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewGlassBackdropDemandTest {
    private class FrameRequests(context: Context) : View(context) {
        var count = 0
        override fun postInvalidateOnAnimation() { count++ }
    }

    @Test
    fun `first consumer starts capture and empty demand stays idle until another consumer binds`() {
        val viewport = FrameRequests(ApplicationProvider.getApplicationContext())
        val backdrop = ViewGlassBackdrop(viewport, viewport, null)
        val consumer: () -> Unit = {}

        backdrop.invalidate()
        assertEquals("an unused page should not request capture frames", 0, viewport.count)

        backdrop.addListener(consumer)
        assertEquals("unknown consumer area must not schedule a full-viewport capture", 0, viewport.count)
        val region = RectF(0f, 0f, 80f, 48f)
        backdrop.updateRegion(consumer, region)
        assertEquals("the first draw must arrange capture even before content changes", 1, viewport.count)
        backdrop.addListener(consumer)
        backdrop.updateRegion(consumer, region)
        assertEquals("binding the same consumer and region twice is idempotent", 1, viewport.count)

        backdrop.removeListener(consumer)
        backdrop.invalidate()
        assertEquals("no capture work is requested after the last consumer leaves", 1, viewport.count)

        backdrop.addListener(consumer)
        assertEquals(1, viewport.count)
        backdrop.updateRegion(consumer, region)
        assertEquals("returning consumers restart a released source", 2, viewport.count)
        backdrop.close()
        backdrop.addListener(consumer)
        backdrop.updateRegion(consumer, region)
        backdrop.invalidate()
        assertEquals("a disposed page cannot restart capture", 2, viewport.count)
    }
}
