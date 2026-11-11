package com.difft.android.chat.widget

import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.chat.R
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config












@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ChatContentSizeTest {

    
    private val sentinelPx = 7

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun activity(): ComponentActivity =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    
    private fun detachedView(): View = View(activity())

    




    private fun viewInContainer(containerDp: Int): View {
        val activity = activity()
        val density = activity.resources.displayMetrics.density
        val container = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val bubble = FrameLayout(activity)
        val row = View(activity)
        
        
        bubble.addView(row, ViewGroup.LayoutParams((100 * density).toInt(), 40))
        container.addView(
            bubble,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        container.measure(
            View.MeasureSpec.makeMeasureSpec((containerDp * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return row
    }

    private fun View.capPx(): Int = resources.getDimensionPixelSize(R.dimen.chat_content_max_width)

    private fun View.widthDp(): Float = chatContentWidthPx() / resources.displayMetrics.density

    

    @Test
    @Config(qualifiers = "w691dp-h716dp")
    fun `width follows the detail pane container, not the window`() {
        
        val row = viewInContainer(containerDp = 338)

        assertEquals(
            "chatContentWidthPx() MUST be the pane container's width. If it tracks the window " +
                "instead, message content is sized against 691dp while it renders in a 338dp " +
                "pane — the over-sizing this accessor exists to prevent.",
            338f,
            row.widthDp(),
            0.5f,
        )
        assertTrue(
            "The pane result must be strictly below the 691dp window width, or the container " +
                "term is not actually being read.",
            row.chatContentWidthPx() < WindowSizeClassUtil.getWindowWidthPx(row.context as ComponentActivity),
        )
    }

    









    private fun viewInStaleRow(listDp: Int, staleRowDp: Int): View {
        val activity = activity()
        val density = activity.resources.displayMetrics.density
        val list = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity) }
        activity.setContentView(list)
        check(list.layoutParams?.width == ViewGroup.LayoutParams.MATCH_PARENT) {
            "setContentView must give the list MATCH_PARENT params or the walk skips it"
        }

        val rowRoot = FrameLayout(activity)
        val bubble = FrameLayout(activity)
        val row = View(activity)
        bubble.addView(row, ViewGroup.LayoutParams((100 * density).toInt(), 40))
        bubble.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        rowRoot.addView(bubble)
        list.addView(
            rowRoot,
            RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        
        rowRoot.measure(
            View.MeasureSpec.makeMeasureSpec((staleRowDp * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        
        list.measure(
            View.MeasureSpec.makeMeasureSpec((listDp * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        check(rowRoot.measuredWidth == (staleRowDp * density).toInt()) {
            "Pre-condition: the row root must still hold the stale ${staleRowDp}dp measurement " +
                "(got ${rowRoot.measuredWidth}px) or this case cannot detect a stale read"
        }
        check(list.measuredWidth == (listDp * density).toInt()) {
            "Pre-condition: the list must hold the fresh ${listDp}dp measurement " +
                "(got ${list.measuredWidth}px)"
        }
        return row
    }

    @Test
    @Config(qualifiers = "w691dp-h716dp")
    fun `a widening resize answers from the message list, not from the mid-measure row root`() {
        
        
        
        val row = viewInStaleRow(listDp = 560, staleRowDp = 378)

        assertEquals(
            "chatContainerWidthPx() MUST answer from the message RecyclerView (560dp), which is " +
                "measured before its children, not from the row root that is still mid-measure " +
                "at the pre-fold 378dp.",
            560f,
            row.chatContainerWidthPx() / row.resources.displayMetrics.density,
            0.5f,
        )
    }

    @Test
    @Config(qualifiers = "w691dp-h716dp")
    fun `a narrowing resize answers from the message list too, so the correction works both ways`() {
        
        
        val row = viewInStaleRow(listDp = 378, staleRowDp = 560)

        assertEquals(
            "A narrowing re-measure must also be tracked: the list is 378dp while the row root " +
                "still reports 560dp.",
            378f,
            row.chatContainerWidthPx() / row.resources.displayMetrics.density,
            0.5f,
        )
    }

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel5)
    fun `list-derived and row-derived widths agree when nothing is stale - phone parity`() {
        
        val row = viewInStaleRow(listDp = 393, staleRowDp = 393)

        assertEquals(
            "A MATCH_PARENT row root in a horizontally unpadded list measures to exactly the " +
                "list's content box, so the two candidate answers are identical in the steady state.",
            393f,
            row.chatContainerWidthPx() / row.resources.displayMetrics.density,
            0.5f,
        )
    }

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel5)
    fun `wrap_content ancestors are skipped so a recycled bind cannot inherit a stale width`() {
        
        
        
        val row = viewInContainer(containerDp = 393)

        assertEquals(
            "The 100dp wrap_content bubble around the row must be skipped in favour of the " +
                "full-width container.",
            393f,
            row.widthDp(),
            0.5f,
        )
    }

    

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel5)
    fun `phone parity - width is the display width and the 560dp ceiling does not fire`() {
        val view = detachedView()

        assertTrue(
            "Pixel5 (${view.widthDp()}dp) must stay strictly below the 560dp ceiling. If the " +
                "ceiling ever drops below a phone window, min() starts clipping message content " +
                "on phones and every committed chat baseline shifts.",
            view.resources.displayMetrics.widthPixels < view.capPx(),
        )
        assertEquals(
            "Phone parity: with no measured container the accessor MUST equal " +
                "displayMetrics.widthPixels, the value ChatMessageContainerView and " +
                "ImageAndVideoMessageView read before this change.",
            view.resources.displayMetrics.widthPixels,
            view.chatContentWidthPx(),
        )
        assertEquals("Pixel5 is a 393dp-wide window.", 393f, view.widthDp(), 1f)
    }

    @Test
    @Config(qualifiers = "w691dp-h716dp")
    fun `full-screen conversation at MEDIUM width is never narrower than a phone`() {
        
        val view = detachedView()

        assertEquals(
            "A full-screen conversation on a 691dp window must get the 560dp ceiling.",
            560f,
            view.widthDp(),
            0.5f,
        )
        assertTrue(
            "A 691dp full-screen window must get MORE content width than a 393dp phone, not " +
                "less. A per-window-size-class cap below 393dp reintroduces the regression.",
            view.widthDp() > 393f,
        )
    }

    @Test
    @Config(qualifiers = "w673dp-h479dp")
    fun `the ceiling does not vary with the dual-pane height gate`() {
        
        
        assertEquals(560f, detachedView().widthDp(), 0.5f)
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun `the container accessor is uncapped so bubble-room decisions are not under-reported`() {
        
        
        
        
        
        val row = viewInContainer(containerDp = 1000)
        val density = row.resources.displayMetrics.density

        assertEquals(
            "chatContainerWidthPx() must report the full container width, uncapped.",
            1000f,
            row.chatContainerWidthPx() / density,
            0.5f,
        )
        assertEquals(
            "chatContentWidthPx() must still apply the 560dp ceiling on the same view.",
            560f,
            row.widthDp(),
            0.5f,
        )
    }

    @Test
    @Config(qualifiers = "w840dp-h480dp")
    fun `the ceiling is band-independent in the tablet band`() {
        assertEquals(
            "chat_content_max_width must resolve to the single 560dp value in every band; a " +
                "narrower pane is handled by the container width, not by a narrower dimen.",
            560f,
            detachedView().widthDp(),
            0.5f,
        )
    }

    

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel5)
    fun `phone parity - height is the usable display height, not the window bounds`() {
        val view = detachedView()

        
        
        assertEquals(
            "Phone parity: chatContentHeightPx() MUST still equal the " +
                "Resources.getSystem().displayMetrics.heightPixels that ImageAndVideoMessageView " +
                "read before this change. That value excludes system decoration; window bounds " +
                "INCLUDE the system bars.",
            Resources.getSystem().displayMetrics.heightPixels,
            view.chatContentHeightPx(),
        )
        
        
        assertEquals(
            "Pixel5's usable display height is 851dp x 2.75 = 2340px.",
            2340,
            view.chatContentHeightPx(),
        )
    }

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel5)
    fun `neither accessor reads the reflective window-bounds helper`() {
        
        
        
        
        
        
        mockkObject(WindowSizeClassUtil)
        every { WindowSizeClassUtil.getWindowWidthPx(any()) } returns sentinelPx
        every { WindowSizeClassUtil.getWindowHeightPx(any()) } returns sentinelPx

        val view = detachedView()

        assertNotEquals(
            "chatContentWidthPx() must not route through WindowSizeClassUtil.getWindowWidthPx.",
            sentinelPx,
            view.chatContentWidthPx(),
        )
        assertNotEquals(
            "chatContentHeightPx() must not route through WindowSizeClassUtil.getWindowHeightPx.",
            sentinelPx,
            view.chatContentHeightPx(),
        )
        assertEquals(view.resources.displayMetrics.widthPixels, view.chatContentWidthPx())
        assertEquals(view.resources.displayMetrics.heightPixels, view.chatContentHeightPx())
    }
}
