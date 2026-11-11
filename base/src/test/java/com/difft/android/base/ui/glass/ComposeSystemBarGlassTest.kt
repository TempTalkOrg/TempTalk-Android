package com.difft.android.base.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode


@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ComposeSystemBarGlassTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `strip fills width with inset plus fade and bottom hides for zero inset or IME`() {
        var edge by mutableStateOf(SystemBarEdge.TOP)
        var bars by mutableStateOf(WindowInsets(top = 12.dp, bottom = 20.dp))
        var ime by mutableStateOf(WindowInsets(0))
        val source = RecordingBackdrop()
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                CompositionLocalProvider(LocalGlassBackdrop provides source) {
                    Box(Modifier.size(200.dp)) {
                        SystemBarGlass(edge, Modifier.testTag(STRIP), insets = bars, imeInsets = ime)
                    }
                }
            }
        }
        compose.onNodeWithTag(STRIP).assertWidthIsEqualTo(200.dp).assertHeightIsEqualTo(36.dp)
        compose.runOnIdle {
            val host = compose.activity.findViewById<View>(android.R.id.content)
            val bitmap = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
            try { host.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
            val region = source.regions.values.single()
            with(compose.density) {
                assertEquals(200.dp.toPx(), region.width(), 0.05f)
                assertEquals(36.dp.toPx(), region.height(), 0.05f)
            }
            assertFalse(source.sharp)
        }
        compose.runOnIdle { edge = SystemBarEdge.BOTTOM }
        compose.onNodeWithTag(STRIP).assertHeightIsEqualTo(44.dp)
        compose.runOnIdle { bars = WindowInsets(top = 12.dp) }
        compose.onNodeWithTag(STRIP).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(source.listeners.isEmpty())
            assertTrue(source.regions.isEmpty())
            bars = WindowInsets(top = 12.dp, bottom = 20.dp)
            ime = WindowInsets(bottom = 100.dp)
        }
        compose.onNodeWithTag(STRIP).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(source.listeners.isEmpty())
            edge = SystemBarEdge.TOP
        }
        compose.onNodeWithTag(STRIP).assertHeightIsEqualTo(36.dp)
        compose.runOnIdle {
            assertEquals(1, source.listeners.size)
            edge = SystemBarEdge.BOTTOM
            ime = WindowInsets(0)
        }
        compose.onNodeWithTag(STRIP).assertHeightIsEqualTo(44.dp)
        compose.runOnIdle { assertEquals(1, source.listeners.size) }
    }

    @Test
    fun `local source takes precedence and disabling strip releases borrowed demand`() {
        val ancestor = RecordingBackdrop()
        val local = RecordingBackdrop()
        var enabled by mutableStateOf(true)
        compose.activity.findViewById<View>(android.R.id.content).setTag(R.id.glass_backdrop, ancestor)
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                CompositionLocalProvider(LocalGlassBackdrop provides local) {
                    SystemBarGlass(
                        SystemBarEdge.TOP,
                        Modifier.testTag(STRIP),
                        enabled = enabled,
                        insets = WindowInsets(top = 12.dp),
                    )
                }
            }
        }
        compose.runOnIdle {
            assertEquals(1, local.listeners.size)
            assertTrue(ancestor.listeners.isEmpty())
            enabled = false
        }
        compose.onNodeWithTag(STRIP).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(local.listeners.isEmpty())
            assertFalse(local.closed)
        }
    }

    @Test
    fun `View ancestor source stops receiving demand while the consumer host is hidden`() {
        val source = RecordingBackdrop()
        val host = compose.activity.findViewById<View>(android.R.id.content)
        host.setTag(R.id.glass_backdrop, source)
        var consumerView: View? = null
        var compositions = 0
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                val view = LocalView.current
                SideEffect {
                    consumerView = view
                    compositions++
                }
                SystemBarGlass(SystemBarEdge.TOP, insets = WindowInsets(top = 12.dp))
            }
        }
        var before = 0
        compose.runOnIdle {
            assertEquals(1, source.listeners.size)
            before = compositions
            consumerView!!.visibility = View.GONE
            host.viewTreeObserver.dispatchOnPreDraw()
            assertTrue(source.listeners.isEmpty())
        }
        compose.runOnIdle {
            consumerView!!.visibility = View.VISIBLE
            host.viewTreeObserver.dispatchOnPreDraw()
        }
        compose.runOnIdle {
            assertEquals(1, source.listeners.size)
            assertEquals(before, compositions)
        }
    }

    private class RecordingBackdrop : GlassBackdrop {
        val listeners = mutableSetOf<() -> Unit>()
        val regions = mutableMapOf<() -> Unit, RectF>()
        var sharp = false
        var closed = false
        override fun draw(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float) = false
        override fun addListener(listener: () -> Unit) { listeners += listener }
        override fun removeListener(listener: () -> Unit) { listeners -= listener; regions.remove(listener) }
        override fun updateRegion(listener: () -> Unit, boundsInWindow: RectF?, sharp: Boolean) {
            this.sharp = sharp
            if (boundsInWindow == null) regions.remove(listener) else regions[listener] = RectF(boundsInWindow)
        }
        override fun invalidate() { listeners.toList().forEach { it() } }
        override fun close() { closed = true }
    }

    private companion object {
        const val STRIP = "system_bar_glass"
    }
}
