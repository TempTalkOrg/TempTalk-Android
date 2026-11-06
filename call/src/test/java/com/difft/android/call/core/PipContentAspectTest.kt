package com.difft.android.call.core

import com.difft.android.call.layout.ControllerState
import com.difft.android.call.layout.LayoutMode
import com.difft.android.call.layout.LayoutPlan
import com.difft.android.call.layout.Orientation
import com.difft.android.call.layout.Posture
import com.difft.android.call.layout.RequestOrientation
import com.difft.android.call.layout.ScreenShare
import com.difft.android.call.layout.WindowClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Picture in picture takes the shape of what it shows (guide §5.3). */
class PipContentAspectTest {

    private fun state(mode: LayoutMode, focus: String?) = ControllerState(
        plan = LayoutPlan(WindowClass.COMPACT, Orientation.PORTRAIT, RequestOrientation.PORTRAIT, mode, Posture.FLAT, emptyList(), null, null, null),
        focus = focus,
    )

    private val sizes = mapOf("a" to FrameSize("a", 720, 1280))

    @Test
    fun `the focus camera's last frame size`() {
        assertEquals(720f / 1280f, pipContentAspect(state(LayoutMode.GRID, "a"), null, waiting = false, sizes::get)!!, 1e-6f)
    }

    @Test
    fun `no frame size, no focus or a waiting 1on1 keeps the default shape`() {
        assertNull(pipContentAspect(state(LayoutMode.GRID, "b"), null, waiting = false, sizes::get))
        assertNull(pipContentAspect(state(LayoutMode.GRID, null), null, waiting = false, sizes::get))
        assertNull(pipContentAspect(state(LayoutMode.SOLO, "a"), null, waiting = true, sizes::get))
        assertNull(pipContentAspect(ControllerState(), null, waiting = false, sizes::get))
    }

    @Test
    fun `a share shows its own shape`() {
        val share = ScreenShare("s", 1920.0, 1080.0)
        assertEquals(1920f / 1080f, pipContentAspect(state(LayoutMode.SHARE, "share"), share, waiting = false, sizes::get)!!, 1e-6f)
        assertNull(pipContentAspect(state(LayoutMode.SHARE, "share"), null, waiting = false, sizes::get))
    }
}
