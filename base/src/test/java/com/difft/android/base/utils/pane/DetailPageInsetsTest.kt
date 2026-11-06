package com.difft.android.base.utils.pane

import android.os.Looper
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Bottom insets a detail page adds itself, per container: a shell and the two-pane pane pad the
 * system bars, the one-pane layer pads all but the bottom. With the IME, only the part of the
 * keyboard the container did not pad is added. Checked on the pure value and on the Compose
 * modifier under real window insets.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class DetailPageInsetsTest {

    private companion object {
        const val FRAME_WAIT_MS = 100L
    }

    private val bar = 48
    private val keyboard = 300

    @Test
    fun `bottom inset per container, without and with the keyboard`() {
        // Shell or two-pane: the container pads the bar.
        assertEquals(0, detailPageBottomInset(containerPadsBottom = true, navigationBarBottom = bar, imeBottom = 0, ime = false))
        assertEquals(0, detailPageBottomInset(containerPadsBottom = true, navigationBarBottom = bar, imeBottom = keyboard, ime = false))
        assertEquals(keyboard - bar, detailPageBottomInset(containerPadsBottom = true, navigationBarBottom = bar, imeBottom = keyboard, ime = true))
        assertEquals(0, detailPageBottomInset(containerPadsBottom = true, navigationBarBottom = bar, imeBottom = 0, ime = true))
        // One-pane layer: the page pads the bar, or the keyboard when larger.
        assertEquals(bar, detailPageBottomInset(containerPadsBottom = false, navigationBarBottom = bar, imeBottom = 0, ime = false))
        assertEquals(bar, detailPageBottomInset(containerPadsBottom = false, navigationBarBottom = bar, imeBottom = 0, ime = true))
        assertEquals(keyboard, detailPageBottomInset(containerPadsBottom = false, navigationBarBottom = bar, imeBottom = keyboard, ime = true))
    }

    private fun composeBottomPadding(containerPadsBottom: Boolean, ime: Boolean, imeBottom: Int): Int {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        var outer = 0
        var inner = 0
        val view = ComposeView(activity).apply {
            setContent {
                Box(Modifier.fillMaxSize().onGloballyPositioned { outer = it.size.height }) {
                    Box(Modifier.fillMaxSize().detailPageInsets(containerPadsBottom, ime)) {
                        Box(Modifier.fillMaxSize().onGloballyPositioned { inner = it.size.height })
                    }
                }
            }
        }
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shadowOf(Looper.getMainLooper()).idle()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 60, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bar))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottom))
            .setVisible(WindowInsetsCompat.Type.ime(), imeBottom > 0)
            .build()
        ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets)
        // Frames are scheduled ahead of the paused clock: advance it so the new insets lay out.
        shadowOf(Looper.getMainLooper()).idleFor(FRAME_WAIT_MS, TimeUnit.MILLISECONDS)
        return outer - inner
    }

    @Test
    fun `Compose page under a padded container adds only what the container left`() {
        val results = listOf(
            composeBottomPadding(containerPadsBottom = true, ime = false, imeBottom = 0),
            composeBottomPadding(containerPadsBottom = false, ime = false, imeBottom = 0),
            composeBottomPadding(containerPadsBottom = true, ime = true, imeBottom = keyboard),
            composeBottomPadding(containerPadsBottom = false, ime = true, imeBottom = keyboard),
        )
        assertEquals(listOf(0, bar, keyboard - bar, keyboard), results)
    }
}
