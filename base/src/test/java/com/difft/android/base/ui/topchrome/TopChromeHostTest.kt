package com.difft.android.base.ui.topchrome

import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TopChromeHostTest {

    private val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
    private var statusTop = 24

    
    private val host = object : FrameLayout(activity) {
        override fun getRootWindowInsets(): WindowInsets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.statusBars(), android.graphics.Insets.of(0, statusTop, 0, 0))
            .setVisible(WindowInsets.Type.statusBars(), statusTop > 0).build()
    }

    private fun show() {
        if (host.parent == null) activity.setContentView(host)
        ShadowLooper.idleMainLooper()
    }

    private class Owner(override val topChromeStyle: TopChromeStyle) : TopChromeOwner

    private fun page(): View = object : FrameLayout(activity) {
        override fun getRootWindowInsets(): WindowInsets = host.rootWindowInsets
    }.apply { setPadding(0, 5, 0, 0) }

    @Test
    fun `an opaque page is padded by the live inset over its own padding and follows changes`() {
        val page = page()
        TopChromeHost.adopt(Any(), page)
        host.addView(page)
        show()
        assertEquals(29, page.paddingTop)

        statusTop = 0
        assertTrue("a changed inset cancels the frame while the padding settles", page.viewTreeObserver.dispatchOnPreDraw())
        assertEquals(5, page.paddingTop)
        assertFalse(page.viewTreeObserver.dispatchOnPreDraw())
    }

    @Test
    fun `band and feather owners are never padded by the host`() {
        listOf(TopChromeStyle.Band, TopChromeStyle.Feather).forEach { style ->
            val page = page()
            TopChromeHost.adopt(Owner(style), page)
            host.addView(page)
            show()
            assertEquals(5, page.paddingTop)
            host.removeView(page)
        }
        val opaque = page()
        TopChromeHost.adopt(Owner(TopChromeStyle.Opaque), opaque)
        host.addView(opaque)
        show()
        assertEquals(29, opaque.paddingTop)
    }

    @Test
    fun `the inset spacer takes the live inset as its height`() {
        val spacer = object : View(activity) {
            override fun getRootWindowInsets(): WindowInsets = host.rootWindowInsets
        }
        host.addView(spacer, FrameLayout.LayoutParams(0, 0))
        show()
        installTopInsetSpacer(spacer)
        assertEquals(24, spacer.layoutParams.height)

        statusTop = 0
        spacer.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(0, spacer.layoutParams.height)
    }
}
