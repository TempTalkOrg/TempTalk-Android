package com.difft.android.base.ui.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingSurfaceCacheTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `light shadows reuse cached geometry until size changes`() = assertCached(false)

    @Test
    fun `dark rims reuse cached geometry until size changes`() = assertCached(true)

    private fun assertCached(dark: Boolean) {
        val shape = CountingShape()
        var extent by mutableStateOf(80.dp)
        var host: View? = null
        compose.setContent {
            DifftTheme(darkTheme = dark, applyWindowBackground = false) {
                val view = LocalView.current
                SideEffect { host = view }
                Box(Modifier.size(extent).floatingSurface(shape))
            }
        }
        var cachedCount = 0
        compose.runOnIdle {
            val view = checkNotNull(host)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                view.draw(canvas)
                cachedCount = shape.outlines
                assertTrue("the real surface must have built its geometry", cachedCount > 0)
                repeat(5) { view.draw(canvas) }
                assertEquals("steady draws must not rebuild skin paths", cachedCount, shape.outlines)
            } finally { bitmap.recycle() }
            extent = 100.dp
        }
        compose.runOnIdle { assertTrue("resizing must rebuild the actual cached shape", shape.outlines > cachedCount) }
    }

    private class CountingShape : Shape {
        var outlines = 0
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
            outlines++
            return CircleShape.createOutline(size, layoutDirection, density)
        }
    }
}
