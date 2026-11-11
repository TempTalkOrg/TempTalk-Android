package com.difft.android.base.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Matrix
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.difft.android.base.R
import com.difft.android.base.ui.compose.floatingPill
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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
class ComposeGlassSurfaceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `consumer region follows layer transforms without recomposition and clears on disposal`() {
        val source = RecordingBackdrop()
        var growth by mutableStateOf(1f)
        var show by mutableStateOf(true)
        var coordinates: LayoutCoordinates? = null
        var view: View? = null
        var compositions = 0
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                val host = LocalView.current
                SideEffect { view = host; compositions++ }
                CompositionLocalProvider(LocalGlassBackdrop provides source) {
                    Box(Modifier.padding(64.dp)) {
                        if (show) Box(Modifier.size(48.dp).graphicsLayer {
                            scaleX = growth
                            scaleY = 0.5f
                            rotationZ = 30f
                            translationX = 20f
                        }.onGloballyPositioned { coordinates = it }.glassSurface(CircleShape))
                    }
                }
            }
        }
        var before = 0
        compose.runOnIdle {
            before = compositions
            growth = 1.5f
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val host = checkNotNull(view)
            val bitmap = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
            try { host.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
            val expected = checkNotNull(coordinates).boundsInWindow()
            val actual = source.regions.values.single()
            assertEquals(expected.left, actual.left, 0.05f)
            assertEquals(expected.top, actual.top, 0.05f)
            assertEquals(expected.right, actual.right, 0.05f)
            assertEquals(expected.bottom, actual.bottom, 0.05f)
            val matrix = checkNotNull(source.matrix)
            assertEquals(1f, matrix[Matrix.MSCALE_X], 0.0001f)
            assertEquals(1f, matrix[Matrix.MSCALE_Y], 0.0001f)
            assertEquals(0f, matrix[Matrix.MSKEW_X], 0.0001f)
            assertEquals(0f, matrix[Matrix.MSKEW_Y], 0.0001f)
            assertEquals(before, compositions)
            show = false
        }
        compose.runOnIdle { assertTrue(source.regions.isEmpty()) }
    }

    @Test
    fun `source content and overlay use separate hosts and share only the backdrop`() {
        var contentView: View? = null
        var chromeView: View? = null
        var contentBackdrop: GlassBackdrop? = null
        var chromeBackdrop: GlassBackdrop? = null
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                GlassBackdrop(
                    modifier = Modifier.size(200.dp),
                    content = {
                        val view = LocalView.current
                        SideEffect {
                            contentView = view
                            contentBackdrop = view.findGlassBackdrop()
                        }
                        Box(Modifier.size(200.dp))
                    },
                ) {
                    val view = LocalView.current
                    val source = LocalGlassBackdrop.current
                    SideEffect {
                        chromeView = view
                        chromeBackdrop = source
                    }
                    Box(Modifier.size(48.dp).glassSurface(CircleShape))
                }
            }
        }
        compose.runOnIdle {
            assertNotNull(contentBackdrop)
            assertSame(contentBackdrop, chromeBackdrop)
            assertNotSame(contentView, chromeView)
            
            var ancestor: View? = chromeView
            while (ancestor != null) {
                assertNotSame(contentView?.parent, ancestor)
                ancestor = ancestor.parent as? View
            }
        }
    }

    @Test
    fun `floating pill discovers a View source and releases its listener when removed`() {
        val source = RecordingBackdrop()
        var show by mutableStateOf(true)
        compose.activity.findViewById<View>(android.R.id.content).setTag(R.id.glass_backdrop, source)
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                if (show) Box(Modifier.size(48.dp).floatingPill(glass = true))
            }
        }
        compose.runOnIdle {
            assertEquals(1, source.listeners.size)
            show = false
        }
        compose.runOnIdle {
            assertTrue(source.listeners.isEmpty())
            
            assertFalse(source.closed)
        }
    }

    @Test
    fun `replacing an ancestor source rebinds without recomposing the consumer`() {
        val first = RecordingBackdrop()
        val second = RecordingBackdrop()
        val host = compose.activity.findViewById<View>(android.R.id.content)
        host.setTag(R.id.glass_backdrop, first)
        var consumerView: View? = null
        var compositions = 0
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                val view = LocalView.current
                SideEffect {
                    consumerView = view
                    compositions++
                }
                Box(Modifier.size(48.dp).floatingPill(glass = true))
            }
        }
        var before = 0
        compose.runOnIdle {
            assertEquals(1, first.listeners.size)
            before = compositions
            host.setTag(R.id.glass_backdrop, second)
            host.invalidate()
            
            val view = checkNotNull(consumerView)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
            } finally {
                bitmap.recycle()
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(first.listeners.isEmpty())
            assertEquals(1, second.listeners.size)
            assertEquals(before, compositions)
        }
    }

    @Test
    fun `hidden View consumer drops demand and rebinds when shown without recomposition`() {
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
                Box(Modifier.size(48.dp).floatingPill(glass = true))
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

    @Test
    fun `two source hosts restore independent content state through the page registry`() {
        val restoration = StateRestorationTester(compose)
        val contentStates = mutableMapOf<Int, MutableState<Int>>()
        restoration.setContent {
            DifftTheme(applyWindowBackground = false) {
                Column {
                    repeat(2) { index ->
                        GlassBackdrop(
                            modifier = Modifier.size(100.dp),
                            content = {
                                val value = rememberSaveable { mutableStateOf(index) }
                                SideEffect { contentStates[index] = value }
                                Box(Modifier.size(100.dp))
                            },
                        ) {}
                    }
                }
            }
        }
        lateinit var first: MutableState<Int>
        lateinit var second: MutableState<Int>
        compose.runOnIdle {
            first = contentStates.getValue(0)
            second = contentStates.getValue(1)
            first.value = 17
            second.value = 43
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertNotSame(first, contentStates.getValue(0))
            assertNotSame(second, contentStates.getValue(1))
            assertEquals(17, contentStates.getValue(0).value)
            assertEquals(43, contentStates.getValue(1).value)
        }
    }

    @Test
    fun `CompositionLocal source takes precedence over an ancestor source`() {
        val ancestor = RecordingBackdrop()
        val local = RecordingBackdrop()
        compose.activity.findViewById<View>(android.R.id.content).setTag(R.id.glass_backdrop, ancestor)
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                CompositionLocalProvider(LocalGlassBackdrop provides local) {
                    Box(Modifier.size(48.dp).glassSurface(CircleShape))
                }
            }
        }
        compose.runOnIdle {
            assertEquals(1, local.listeners.size)
            assertTrue(ancestor.listeners.isEmpty())
        }
    }

    private class RecordingBackdrop : GlassBackdrop {
        val listeners = mutableSetOf<() -> Unit>()
        val regions = mutableMapOf<() -> Unit, RectF>()
        var matrix: FloatArray? = null
        var closed = false
        @Suppress("DEPRECATION")
        override fun draw(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float): Boolean {
            matrix = FloatArray(9).also { values -> canvas.matrix.getValues(values) }
            return false
        }
        override fun addListener(listener: () -> Unit) { listeners += listener }
        override fun removeListener(listener: () -> Unit) { listeners -= listener; regions.remove(listener) }
        override fun updateRegion(listener: () -> Unit, boundsInWindow: RectF?, sharp: Boolean) {
            if (boundsInWindow == null) regions.remove(listener) else regions[listener] = RectF(boundsInWindow)
        }
        override fun invalidate() { listeners.toList().forEach { it() } }
        override fun close() { closed = true }
    }
}
