package com.difft.android.base.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.topchrome.TopChromeGlassDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt


@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ComposeGlassBandTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `band uses existing light and dark tint without a floating rim or shadow`() {
        val source = RecordingBackdrop(ready = true)
        var dark by mutableStateOf(false)
        var view: View? = null
        var tint = 0
        compose.setContent {
            DifftTheme(darkTheme = dark, applyWindowBackground = false) {
                val host = LocalView.current
                val background = DifftTheme.colors.background.toArgb()
                SideEffect { view = host; tint = background }
                CompositionLocalProvider(LocalGlassBackdrop provides source) {
                    Box(Modifier.size(120.dp).background(ComposeColor.Blue)) {
                        Box(Modifier.size(80.dp, 48.dp).glassBand())
                    }
                }
            }
        }
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark = isDark }
            compose.waitForIdle()
            compose.runOnIdle {
                val alpha = if (isDark) TopChromeGlassDrawable.BAND_ALPHA_DARK else TopChromeGlassDrawable.BAND_ALPHA_LIGHT
                val expected = ColorUtils.compositeColors(
                    ColorUtils.setAlphaComponent(tint, (255 * alpha).roundToInt()), Color.RED,
                )
                draw(checkNotNull(view)) { bitmap ->
                    with(compose.density) {
                        assertColor(expected, bitmap.getPixel(1, 1))
                        assertColor(expected, bitmap.getPixel(40.dp.roundToPx(), 24.dp.roundToPx()))
                        assertColor(expected, bitmap.getPixel(80.dp.roundToPx() - 2, 48.dp.roundToPx() - 2))
                        assertEquals(Color.BLUE, bitmap.getPixel(82.dp.roundToPx(), 24.dp.roundToPx()))
                    }
                }
            }
        }
    }

    @Test
    fun `unready source paints an opaque page background and disposal releases its region`() {
        val source = RecordingBackdrop(ready = false)
        var shown by mutableStateOf(true)
        var view: View? = null
        var expected = 0
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                val host = LocalView.current
                val background = DifftTheme.colors.background.toArgb()
                SideEffect { view = host; expected = background }
                CompositionLocalProvider(LocalGlassBackdrop provides source) {
                    Box(Modifier.size(120.dp).background(ComposeColor.Red)) {
                        if (shown) Box(Modifier.size(80.dp, 48.dp).glassBand())
                    }
                }
            }
        }
        compose.runOnIdle {
            draw(checkNotNull(view)) { bitmap -> assertEquals(expected, bitmap.getPixel(1, 1)) }
            val region = source.regions.values.single()
            with(compose.density) {
                assertEquals(80.dp.toPx(), region.width(), 0.05f)
                assertEquals(48.dp.toPx(), region.height(), 0.05f)
            }
            shown = false
        }
        compose.runOnIdle {
            assertTrue(source.listeners.isEmpty())
            assertTrue(source.regions.isEmpty())
            assertFalse(source.closed)
        }
    }

    private fun draw(view: View, assertion: (Bitmap) -> Unit) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            assertion(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertColor(expected: Int, actual: Int) {
        
        for (shift in listOf(0, 8, 16, 24)) {
            assertTrue(abs((expected ushr shift and 255) - (actual ushr shift and 255)) <= 1)
        }
    }

    private class RecordingBackdrop(val ready: Boolean) : GlassBackdrop {
        val listeners = mutableSetOf<() -> Unit>()
        val regions = mutableMapOf<() -> Unit, RectF>()
        var closed = false
        override fun draw(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float): Boolean {
            if (ready) canvas.drawColor(Color.RED)
            return ready
        }
        override fun addListener(listener: () -> Unit) { listeners += listener }
        override fun removeListener(listener: () -> Unit) { listeners -= listener; regions.remove(listener) }
        override fun updateRegion(listener: () -> Unit, boundsInWindow: RectF?, sharp: Boolean) {
            if (boundsInWindow == null) regions.remove(listener) else regions[listener] = RectF(boundsInWindow)
        }
        override fun invalidate() = listeners.toList().forEach { it() }
        override fun close() { closed = true }
    }
}
