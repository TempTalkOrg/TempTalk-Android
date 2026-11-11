package com.difft.android.base.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers





@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeLiquidGlassTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var previousMode: Boolean? = null

    @Before fun enableGlass() {
        previousMode = ReflectionHelpers.getStaticField(GlassMode::class.java, "live")
        ReflectionHelpers.setStaticField(GlassMode::class.java, "live", true)
        GlassMode.libraryEffects = false
    }

    @After fun restoreMode() {
        ReflectionHelpers.setStaticField(GlassMode::class.java, "live", previousMode)
        GlassMode.libraryEffects = true
    }

    @Test
    @Config(sdk = [31, 32])
    fun `API31 and 32 keep ordinary blurred glass and never request sharp optics`() {
        GlassMode.libraryEffects = true
        val source = LiquidSource()
        val host = render(source)
        compose.runOnIdle {
            drawOnce(host())
            assertTrue(source.blurredDraws > 0)
            assertEquals(0, source.sharpDraws)
            assertEquals(false, source.sharp)
        }
    }

    @Test
    fun `without library effects the liquid surface keeps ordinary blurred glass`() {
        val source = LiquidSource()
        var coordinates: LayoutCoordinates? = null
        var base = 0
        val host = render(source, onPlaced = { coordinates = it }, onBase = { base = it })
        compose.runOnIdle {
            val view = host()
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.BLUE)
                view.draw(Canvas(bitmap))
                val bounds = checkNotNull(coordinates).boundsInWindow()
                val location = IntArray(2).also(view::getLocationInWindow)
                val center = bitmap.getPixel(((bounds.left + bounds.right) / 2).toInt() - location[0],
                    ((bounds.top + bounds.bottom) / 2).toInt() - location[1])
                assertEquals(255, android.graphics.Color.alpha(center))
                assertNotEquals("the blurred page shows under the tint", base, center)
                assertTrue(source.blurredDraws > 0)
                assertEquals(0, source.sharpDraws)
                assertEquals(false, source.sharp)
            } finally { bitmap.recycle() }
        }
    }

    private fun render(
        source: GlassBackdrop,
        onPlaced: (LayoutCoordinates) -> Unit = {},
        onBase: (Int) -> Unit = {},
    ): () -> View {
        var host: View? = null
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                val view = LocalView.current
                val color = if (DifftTheme.colors.isDark) DifftTheme.colors.backgroundTertiary else DifftTheme.colors.background
                SideEffect {
                    host = view
                    onBase(color.copy(alpha = 1f).toArgb())
                    view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                }
                CompositionLocalProvider(LocalGlassBackdrop provides source) {
                    Box(Modifier.size(100.dp, 56.dp).onGloballyPositioned(onPlaced)
                        .liquidGlassSurface(RoundedCornerShape(0.dp), GlassLensStyle.Panel,
                            progress = { 0f }, highlight = { 0f }, shadow = { 0f }))
                }
            }
        }
        return { checkNotNull(host) }
    }

    private fun drawOnce(view: View) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try { view.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
    }

    private class LiquidSource : GlassBackdrop {
        var sharp = false
        var blurredDraws = 0
        var sharpDraws = 0
        override fun updateRegion(listener: () -> Unit, boundsInWindow: RectF?, sharp: Boolean) {
            this.sharp = sharp
        }
        override fun draw(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float): Boolean {
            blurredDraws++
            canvas.drawColor(android.graphics.Color.GREEN)
            return true
        }
        override fun drawSharp(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float, bounds: RectF): Boolean {
            sharpDraws++
            canvas.drawColor(android.graphics.Color.MAGENTA)
            return true
        }
        override fun addListener(listener: () -> Unit) = Unit
        override fun removeListener(listener: () -> Unit) = Unit
        override fun invalidate() = Unit
        override fun close() = Unit
    }
}
