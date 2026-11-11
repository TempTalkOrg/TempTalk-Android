package com.difft.android.base.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test


class DifftTypeScaleScaledTest {

    @Test
    fun `every step is one and a half times its size and line height, still in sp`() {
        val base = DifftTypeScale()
        val scaled = base.scaled(1.5f)

        val steps = listOf(
            base.h1 to scaled.h1, base.h2 to scaled.h2, base.h3 to scaled.h3, base.h4 to scaled.h4,
            base.h5 to scaled.h5, base.h6 to scaled.h6, base.h7 to scaled.h7,
            base.p0 to scaled.p0, base.p1 to scaled.p1, base.p2 to scaled.p2, base.p3 to scaled.p3,
            base.p4 to scaled.p4, base.p5 to scaled.p5, base.footnote to scaled.footnote,
        )
        steps.forEach { (before, after) ->
            assertEquals(before.fontSize.value * 1.5f, after.fontSize.value, 0.001f)
            assertEquals(before.lineHeight.value * 1.5f, after.lineHeight.value, 0.001f)
            assertEquals(before.fontSize.type, after.fontSize.type)
            assertEquals(before.fontWeight, after.fontWeight)
        }
        
        assertEquals(24f, scaled.p2.fontSize.value, 0.001f)
        assertEquals(36f, scaled.p2.lineHeight.value, 0.001f)
    }
}
