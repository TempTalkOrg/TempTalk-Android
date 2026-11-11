package com.difft.android.dualpane

import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit





@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PaneMotionTest {

    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private lateinit var root: FrameLayout
    private lateinit var over: View
    private lateinit var under: View

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        val activity = controller!!.get()
        root = FrameLayout(activity)
        under = View(activity)
        over = View(activity)
        root.addView(under, WIDTH, WIDTH)
        root.addView(over, WIDTH, WIDTH)
        activity.addContentView(root, FrameLayout.LayoutParams(WIDTH, WIDTH))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    @Test
    fun `cover starts with the page at the end edge and the covered view under a scrim, and ends at rest`() {
        val cover = PaneMotion.cover(over, listOf(under))
        cover.start()

        assertEquals(WIDTH.toFloat(), over.translationX, 0f)
        assertEquals(0f, under.translationX, 0f)
        assertNotNull("the scrim is on from the first frame", under.foreground)

        settle()
        assertEquals(0f, over.translationX, 0f)
        assertEquals(0f, under.translationX, 0f)
        assertNull(under.foreground)
    }

    @Test
    fun `uncover leaves the page off screen at the end and the uncovered view at rest`() {
        PaneMotion.uncover(over, listOf(under)).start()
        settle()

        assertEquals("a slid-out layer stays off screen until it is taken down", WIDTH.toFloat(), over.translationX, 0f)
        assertEquals(0f, under.translationX, 0f)
        assertNull(under.foreground)
    }

    @Test
    fun `a cancelled cover puts both views back at rest`() {
        val cover = PaneMotion.cover(over, listOf(under))
        cover.start()
        cover.cancel()

        assertEquals(0f, over.translationX, 0f)
        assertEquals(0f, under.translationX, 0f)
        assertNull(under.foreground)
    }

    @Test
    fun `a view with its own foreground keeps it`() {
        val own = android.graphics.drawable.ColorDrawable(1)
        under.foreground = own
        val cover = PaneMotion.cover(over, listOf(under))
        cover.start()
        assertTrue(under.foreground === own)
        settle()
        assertTrue(under.foreground === own)
    }

    @Test
    @Config(qualifiers = "ar-ldrtl")
    fun `right-to-left - the page enters from the left and the covered view drifts right`() {
        val cover = PaneMotion.cover(over, listOf(under))
        cover.start()
        assertEquals(-WIDTH.toFloat(), over.translationX, 0f)
        settle()

        PaneMotion.uncover(over, listOf(under)).start()
        settle()
        assertEquals(-WIDTH.toFloat(), over.translationX, 0f)
    }

    private companion object {
        const val WIDTH = 400
    }
}
