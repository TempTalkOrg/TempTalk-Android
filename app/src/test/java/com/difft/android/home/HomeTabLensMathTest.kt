package com.difft.android.home

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HomeTabLensMathTest {
    @Test
    fun `reused draw holders recompute from inputs without accumulating prior deformation`() {
        val scale = PillScale(0f, 0f)
        assertSame(scale, HomeTabLensMath.scale(8f, 1.2f, 1.3f, scale))
        assertEquals(HomeTabLensMath.scale(8f, 1.2f, 1.3f), scale)
        assertSame(scale, HomeTabLensMath.scale(0f, 1f, 1f, scale))
        assertEquals(PillScale(1f, 1f), scale)
        assertSame(scale, HomeTabPillMath.scale(4f, 0.5f, scale))
        assertEquals(HomeTabPillMath.scale(4f, 0.5f), scale)
        val transform = LensRowTransform(0f, 0f, 0f, 0f)
        assertSame(transform, HomeTabLensMath.rowTransform(60f, 0f, 60f, 54f, 1.4f, 1.4f, transform))
        assertEquals(HomeTabLensMath.rowTransform(60f, 0f, 60f, 54f, 1.4f, 1.4f), transform)
        HomeTabLensMath.rowTransform(0f, 0f, 60f, 54f, 1f, 1f, transform)
        assertEquals(LensRowTransform(1f, 1f, 0f, 0f), transform)
    }

    @Test
    fun `refraction follows only press and surface tint fades from ten percent to clear`() {
        assertEquals(0f, HomeTabLensMath.progress(0f), 0f)
        assertEquals(0.5f, HomeTabLensMath.progress(0.5f), 0f)
        assertEquals(1f, HomeTabLensMath.progress(2f), 0f)
        assertEquals(0f, HomeTabLensMath.progress(Float.NaN), 0f)
        assertEquals(Color.White.copy(alpha = 0.1f), homeTabRestTint(true, 0f))
        assertEquals(Color.Black.copy(alpha = 0.06f), homeTabRestTint(false, 0f))
        assertEquals(0f, homeTabRestTint(true, 1f).alpha, 0f)
        assertEquals(0f, homeTabRestTint(false, 1f).alpha, 0f)
        assertEquals(0.03f, HomeTabLensMath.pressedTintAlpha(1f), 0f)
    }

    @Test
    fun `demo velocity deformation is signed bounded and uses each independent scale spring`() {
        assertEquals(PillScale(1f, 1f), HomeTabLensMath.scale(0f, 1f, 1f))
        val pressed = HomeTabLensMath.scale(0f, 78f / 56f, 78f / 56f)
        assertEquals(1.392857f, pressed.axis, 0.0001f)
        val positive = HomeTabLensMath.scale(1000f, 78f / 56f, 78f / 56f)
        assertEquals(1.741071f, positive.axis, 0.0001f)
        assertEquals(1.114286f, positive.cross, 0.0001f)
        val negative = HomeTabLensMath.scale(-1000f, 78f / 56f, 78f / 56f)
        assertEquals(1.160714f, negative.axis, 0.0001f)
        assertEquals(1.671429f, negative.cross, 0.0001f)
        assertEquals(PillScale(1.2f, 1.3f), HomeTabLensMath.scale(0f, 1.2f, 1.3f))
    }

    @Test
    fun `same signed motion transposes physical scales applied frame and fractional row replay exactly`() {
        for (velocity in listOf(-12f, 0f, 12f)) {
            val deformation = HomeTabLensMath.scale(velocity, axis = 1.19f, cross = 1.37f)
            val horizontal = HomeTabLensFrame()
            val vertical = HomeTabLensFrame()
            val horizontalX = HomeTabLensMath.physicalScaleX(deformation, vertical = false)
            val horizontalY = HomeTabLensMath.physicalScaleY(deformation, vertical = false)
            val verticalX = HomeTabLensMath.physicalScaleX(deformation, vertical = true)
            val verticalY = HomeTabLensMath.physicalScaleY(deformation, vertical = true)
            assertEquals(deformation.axis, horizontalX, 0f)
            assertEquals(deformation.cross, horizontalY, 0f)
            assertEquals(horizontalX, verticalY, 0f)
            assertEquals(horizontalY, verticalX, 0f)
            horizontal.apply(0.625f, horizontalX, horizontalY)
            vertical.apply(0.625f, verticalX, verticalY)
            
            val newer = HomeTabLensMath.scale(-velocity, axis = 1.31f, cross = 1.09f)
            val h = horizontal.resolve(0.75f, HomeTabLensMath.physicalScaleX(newer, false),
                HomeTabLensMath.physicalScaleY(newer, false))
            val v = vertical.resolve(0.75f, HomeTabLensMath.physicalScaleX(newer, true),
                HomeTabLensMath.physicalScaleY(newer, true))
            assertEquals(0.625f, h.index, 0f)
            assertEquals(h.index, v.index, 0f)
            assertEquals(h.scaleX, v.scaleY, 0f)
            assertEquals(h.scaleY, v.scaleX, 0f)
            val offset = HomeTabPillMath.offset(h.index, 60f, HomeTab.COUNT)
            val hPosition = HomeTabLensMath.axisPosition(offset, 60f, 180f, vertical = false, rtl = false)
            val vPosition = HomeTabLensMath.axisPosition(offset, 60f, 180f, vertical = true, rtl = false)
            assertEquals(37.5f, hPosition, 0f)
            assertEquals(hPosition, vPosition, 0f)
            assertEquals(HomeTabLensMath.physicalX(hPosition, false), HomeTabLensMath.physicalY(vPosition, true), 0f)
            assertEquals(0f, HomeTabLensMath.physicalY(hPosition, false), 0f)
            assertEquals(0f, HomeTabLensMath.physicalX(vPosition, true), 0f)
            val hReplay = HomeTabLensMath.rowTransform(HomeTabLensMath.physicalX(hPosition + 4f, false),
                HomeTabLensMath.physicalY(hPosition + 4f, false), 60f, 40f, h.scaleX, h.scaleY)
            val vReplay = HomeTabLensMath.rowTransform(HomeTabLensMath.physicalX(vPosition + 4f, true),
                HomeTabLensMath.physicalY(vPosition + 4f, true), 40f, 60f, v.scaleX, v.scaleY)
            assertEquals(hReplay.scaleX, vReplay.scaleY, 0f)
            assertEquals(hReplay.scaleY, vReplay.scaleX, 0f)
            assertEquals(hReplay.translateX, vReplay.translateY, 0f)
            assertEquals(hReplay.translateY, vReplay.translateX, 0f)
            assertEquals(30f, (hPosition + 4f + 30f) * hReplay.scaleX + hReplay.translateX, 0.0001f)
            assertEquals(30f, (vPosition + 4f + 30f) * vReplay.scaleY + vReplay.translateY, 0.0001f)
        }
    }

    @Test
    fun `panel translation transposes without reversing vertical RTL and its scale stays uniform`() {
        for (displacement in listOf(-90f, 0f, 90f)) {
            val shift = HomeTabPanelMath.offset(displacement, 180f, 4f)
            assertEquals(HomeTabLensMath.physicalX(shift, false), HomeTabLensMath.physicalY(shift, true), 0f)
            assertEquals(0f, HomeTabLensMath.physicalY(shift, false), 0f)
            assertEquals(0f, HomeTabLensMath.physicalX(shift, true), 0f)
            val hScale = HomeTabPanelMath.scale(0.65f, maxOf(180f, 40f), 16f)
            val vScale = HomeTabPanelMath.scale(0.65f, maxOf(40f, 180f), 16f)
            assertEquals(1f + 16f / 180f * 0.65f, hScale, 0f)
            assertEquals(hScale, vScale, 0f)
        }
        val offset = HomeTabPillMath.offset(0.625f, 60f, HomeTab.COUNT)
        assertEquals(82.5f, HomeTabLensMath.axisPosition(offset, 60f, 180f, vertical = false, rtl = true), 0f)
        assertEquals(37.5f, HomeTabLensMath.axisPosition(offset, 60f, 180f, vertical = true, rtl = true), 0f)
        assertEquals(HomeTabLensMath.axisPosition(offset, 60f, 180f, vertical = true, rtl = false),
            HomeTabLensMath.axisPosition(offset, 60f, 180f, vertical = true, rtl = true), 0f)
    }

    @Test
    fun `replay stays on actual fractional clip geometry when draw state advances first`() {
        val frame = HomeTabLensFrame()
        frame.apply(0.625f, 1.3f, 1.2f)
        val current = frame.resolve(0.75f, 1.4f, 1.25f)
        assertEquals(LensFrame(0.625f, 1.3f, 1.2f), current)
        val offset = HomeTabPillMath.offset(current.index, 60f, HomeTab.COUNT)
        assertEquals(37.5f, offset, 0f)
        val ltr = HomeTabLensMath.rowTransform(4f + offset, 0f, 60f, 54f, current.scaleX, current.scaleY)
        assertEquals(30f, (4f + offset + 30f) * ltr.scaleX + ltr.translateX, 0.0001f)
        val rtlStart = HomeTabLensMath.physicalStart(offset, 60f, 180f, true)
        val rtl = HomeTabLensMath.rowTransform(4f + rtlStart, 0f, 60f, 54f, current.scaleX, current.scaleY)
        assertEquals(30f, (4f + rtlStart + 30f) * rtl.scaleX + rtl.translateX, 0.0001f)
        val vertical = HomeTabLensMath.rowTransform(0f, 4f + offset, 40f, 48f, current.scaleY, current.scaleX)
        assertEquals(24f, (4f + offset + 24f) * vertical.scaleY + vertical.translateY, 0.0001f)
        frame.apply(0.75f, 1.4f, 1.25f)
        assertEquals(LensFrame(0.75f, 1.4f, 1.25f), frame.resolve(0.75f, 1.4f, 1.25f))
    }

    @Test
    fun `row input stays anchored around a growing horizontal pill center`() {
        val transform = HomeTabLensMath.rowTransform(60f, 0f, 60f, 54f, 1.4f, 1.4f)
        
        assertEquals(30f, 90f * transform.scaleX + transform.translateX, 0.0001f)
        assertEquals(27f, 27f * transform.scaleY + transform.translateY, 0.0001f)
        
        assertEquals(0f, 48f * transform.scaleX + transform.translateX, 0.0001f)
    }

    @Test
    fun `vertical input uses its physical Y offset and RTL uses the mirrored physical X`() {
        val vertical = HomeTabLensMath.rowTransform(0f, 48f, 40f, 48f, 1.1f, 1.5f)
        assertEquals(20f, 20f * vertical.scaleX + vertical.translateX, 0.0001f)
        assertEquals(24f, 72f * vertical.scaleY + vertical.translateY, 0.0001f)
        assertEquals(120f, HomeTabLensMath.physicalStart(0f, 60f, 180f, true), 0f)
        assertEquals(0f, HomeTabLensMath.physicalStart(120f, 60f, 180f, true), 0f)
        val rtl = HomeTabLensMath.rowTransform(120f, 0f, 60f, 54f, 1f, 1f)
        assertEquals(30f, 150f * rtl.scaleX + rtl.translateX, 0f)
    }

    @Test
    fun `identity and invalid scales remain finite without shifting the row`() {
        val identity = LensRowTransform(1f, 1f, 0f, 0f)
        assertEquals(identity, HomeTabLensMath.rowTransform(0f, 0f, 60f, 54f, 1f, 1f))
        assertEquals(identity, HomeTabLensMath.rowTransform(0f, 0f, 60f, 54f, 0f, Float.NaN))
    }
}
