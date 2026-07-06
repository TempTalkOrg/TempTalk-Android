package com.difft.android.chat.invite

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit test for the file-level `Int.toSurfaceRotation()` extension (design §7 T20).
 *
 * Pins the sensor-degrees → [Surface] ROTATION_* quadrant mapping at every boundary (F1 — guards
 * against passing raw degrees to CameraX's setTargetRotation). Robolectric only because
 * [Surface] ROTATION_* constants are framework values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToSurfaceRotationTest {

    @Test
    fun `T20 - sensor degrees map to the correct Surface rotation at every quadrant boundary`() {
        // 315..359 + 0..44 → ROTATION_0 (natural / portrait-up)
        assertEquals(Surface.ROTATION_0, 0.toSurfaceRotation())
        assertEquals(Surface.ROTATION_0, 44.toSurfaceRotation())
        assertEquals(Surface.ROTATION_0, 315.toSurfaceRotation())
        assertEquals(Surface.ROTATION_0, 359.toSurfaceRotation())

        // 45..134 → ROTATION_270
        assertEquals(Surface.ROTATION_270, 45.toSurfaceRotation())
        assertEquals(Surface.ROTATION_270, 134.toSurfaceRotation())

        // 135..224 → ROTATION_180
        assertEquals(Surface.ROTATION_180, 135.toSurfaceRotation())
        assertEquals(Surface.ROTATION_180, 224.toSurfaceRotation())

        // 225..314 → ROTATION_90
        assertEquals(Surface.ROTATION_90, 225.toSurfaceRotation())
        assertEquals(Surface.ROTATION_90, 314.toSurfaceRotation())
    }
}
