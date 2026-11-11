package com.difft.android.call.core

import com.difft.android.call.layout.CallLayoutController
import com.difft.android.call.layout.CallMember
import com.difft.android.call.layout.ContentMode
import com.difft.android.call.layout.Insets
import com.difft.android.call.layout.LayoutMode
import com.difft.android.call.layout.Rect
import com.difft.android.call.layout.ScreenShare
import com.difft.android.call.layout.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test


class ShareSizeTest {

    @Test
    fun `frames replace the publication's size and follow an in-place resize`() {
        val size = ShareSize()
        assertEquals(ScreenShare("s", 16.0, 9.0), size.onPublished(ScreenShare("s", 16.0, 9.0)))
        assertEquals(ScreenShare("s", 1080.0, 2400.0), size.onFrame("s", 1080, 2400))
        assertNull("unchanged", size.onFrame("s", 1080, 2400))
        assertNull("another layer of the same shape", size.onFrame("s", 540, 1200))
        assertEquals(ScreenShare("s", 2400.0, 1080.0), size.onFrame("s", 2400, 1080))
        
        assertEquals(ScreenShare("s", 2400.0, 1080.0), size.onPublished(ScreenShare("s", 1920.0, 1080.0)))
    }

    @Test
    fun `a new sharer or the end of the share starts over`() {
        val size = ShareSize()
        size.onPublished(ScreenShare("s", 16.0, 9.0))
        size.onFrame("s", 1080, 2400)
        assertEquals(ScreenShare("t", 16.0, 9.0), size.onPublished(ScreenShare("t", 16.0, 9.0)))
        assertNull("a late frame of the old sharer", size.onFrame("s", 1080, 2400))
        assertNull(size.onPublished(null))
        assertNull("no share, no size", size.onFrame("t", 100, 100))
    }

    private fun controller() = CallLayoutController(now = { 0.0 }).apply {
        setWindow(Size(411.0, 923.0), Insets(top = 48.0, bottom = 24.0), null)
        setContent(Rect(12.0, 104.0, 387.0, 643.0), Rect(12.0, 56.0, 387.0, 835.0))
        setParticipants(listOf(CallMember("me", isLocal = true, cameraOn = false), CallMember("s", isLocal = false, cameraOn = false)))
    }

    @Test
    fun `a share frame size reaches the controller and the picture-in-picture aspect`() {
        val layout = controller()
        val inputs = CallLayoutInputs(layout) { 0L }
        inputs.onShare(ScreenShare("s", 16.0, 9.0))
        assertEquals(LayoutMode.SHARE, layout.state.value.plan?.mode)
        assertEquals(16.0 / 9.0, layout.lastInput?.share?.aspect!!, 1e-9)

        inputs.onShareFrameSize("s", 1080, 2400)
        assertEquals(1080.0 / 2400.0, layout.lastInput?.share?.aspect!!, 1e-9)
        inputs.onLayoutState(layout.state.value)
        assertEquals(1080f / 2400f, inputs.pipContentAspect.value!!, 1e-6f)
    }

    @Test
    fun `a double tap's zoom drives the share tile's content mode`() {
        val layout = controller()
        layout.setShare(ScreenShare("s", 16.0, 9.0))
        assertEquals(ContentMode.FIT, layout.state.value.plan?.tiles?.single()?.contentMode)
        layout.toggleShareZoom()
        val tile = layout.state.value.plan?.tiles?.single()
        assertEquals(ContentMode.FILL, tile?.contentMode)
        assertEquals("a filling share reaches past the window", true, tile!!.rect.w > 411.0 || tile.rect.h > 923.0)
    }
}
