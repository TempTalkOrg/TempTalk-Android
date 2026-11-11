package com.difft.android.base.ui.glass

import android.view.View

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ComposeOverlayChromeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `overlay heights pad scrolling content without shrinking its viewport`() {
        var padding: PaddingValues? = null
        var contentBounds: Rect? = null
        compose.setContent {
            OverlayChrome(
                modifier = Modifier.size(200.dp),
                insets = WindowInsets(top = 10, bottom = 20),
                top = { Box(Modifier.height(30.dp)) },
                bottom = { Box(Modifier.height(40.dp)) },
            ) { obstruction ->
                SideEffect { padding = obstruction }
                Box(Modifier.size(200.dp).onGloballyPositioned { contentBounds = it.boundsInRoot() })
            }
        }
        compose.runOnIdle {
            assertNotNull(padding)
            with(compose.density) {
                assertEquals(30.dp.roundToPx() + 10, padding!!.calculateTopPadding().roundToPx())
                assertEquals(40.dp.roundToPx() + 20, padding!!.calculateBottomPadding().roundToPx())
                assertEquals(200.dp.toPx(), contentBounds!!.height, 1f)
            }
        }
    }

    @Test
    fun `glass chrome shares a content-only source without changing measured padding`() {
        var contentView: View? = null
        var topView: View? = null
        var bottomView: View? = null
        var contentSource: GlassBackdrop? = null
        var topSource: GlassBackdrop? = null
        var bottomSource: GlassBackdrop? = null
        var padding: PaddingValues? = null
        var contentBounds: Rect? = null
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                OverlayChrome(
                    modifier = Modifier.size(200.dp),
                    insets = WindowInsets(top = 10, bottom = 20),
                    glass = true,
                    top = {
                        val view = LocalView.current
                        val source = LocalGlassBackdrop.current
                        SideEffect {
                            topView = view
                            topSource = source
                        }
                        Box(Modifier.fillMaxWidth().height(30.dp).glassSurface(RectangleShape))
                    },
                    bottom = {
                        val view = LocalView.current
                        val source = LocalGlassBackdrop.current
                        SideEffect {
                            bottomView = view
                            bottomSource = source
                        }
                        Box(Modifier.fillMaxWidth().height(40.dp).glassSurface(RectangleShape))
                    },
                ) { obstruction ->
                    val view = LocalView.current
                    SideEffect {
                        contentView = view
                        contentSource = view.findGlassBackdrop()
                        padding = obstruction
                    }
                    Box(Modifier.size(200.dp).onGloballyPositioned { contentBounds = it.boundsInRoot() })
                }
            }
        }
        compose.runOnIdle {
            assertNotNull(contentSource)
            assertSame(contentSource, topSource)
            assertSame(contentSource, bottomSource)
            assertNotSame(contentView, topView)
            assertNotSame(contentView, bottomView)
            
            for (chromeView in listOf(topView, bottomView)) {
                var ancestor = chromeView
                while (ancestor != null) {
                    assertNotSame(contentView?.parent, ancestor)
                    ancestor = ancestor.parent as? View
                }
            }
            with(compose.density) {
                assertEquals(30.dp.roundToPx() + 10, padding!!.calculateTopPadding().roundToPx())
                assertEquals(40.dp.roundToPx() + 20, padding!!.calculateBottomPadding().roundToPx())
                assertEquals(200.dp.toPx(), contentBounds!!.height, 1f)
            }
        }
    }

    @Test
    fun `ancestor consumed status inset is not added to top chrome twice`() {
        val insets = WindowInsets(top = 10, bottom = 20)
        var padding: PaddingValues? = null
        compose.setContent {
            Box(Modifier.size(200.dp).windowInsetsPadding(insets.only(WindowInsetsSides.Top))) {
                OverlayChrome(
                    insets = insets,
                    top = { Box(Modifier.height(30.dp)) },
                    bottom = { Box(Modifier.height(40.dp)) },
                ) { obstruction ->
                    SideEffect { padding = obstruction }
                }
            }
        }
        compose.runOnIdle {
            with(compose.density) {
                assertEquals(30.dp.roundToPx(), padding!!.calculateTopPadding().roundToPx())
                assertEquals(40.dp.roundToPx() + 20, padding!!.calculateBottomPadding().roundToPx())
            }
        }
    }
}
