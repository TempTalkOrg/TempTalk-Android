package com.difft.android.chat.invite

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config





@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToSurfaceRotationTest {

    @Test
    fun `T20 - sensor degrees map to the correct Surface rotation at every quadrant boundary`() {
        
        assertEquals(Surface.ROTATION_0, 0.toSurfaceRotation())
        assertEquals(Surface.ROTATION_0, 44.toSurfaceRotation())
        assertEquals(Surface.ROTATION_0, 315.toSurfaceRotation())
        assertEquals(Surface.ROTATION_0, 359.toSurfaceRotation())

        
        assertEquals(Surface.ROTATION_270, 45.toSurfaceRotation())
        assertEquals(Surface.ROTATION_270, 134.toSurfaceRotation())

        
        assertEquals(Surface.ROTATION_180, 135.toSurfaceRotation())
        assertEquals(Surface.ROTATION_180, 224.toSurfaceRotation())

        
        assertEquals(Surface.ROTATION_90, 225.toSurfaceRotation())
        assertEquals(Surface.ROTATION_90, 314.toSurfaceRotation())
    }
}
