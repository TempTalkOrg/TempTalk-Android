package com.difft.android.chat.ui.popup

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.View.MeasureSpec
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.difft.android.base.BaseActivity
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.base.widget.InsetAwareConstraintLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration












@RunWith(RobolectricTestRunner::class)
class PopupChatSheetControllerInsetsTest {

    private companion object {
        const val WINDOW_HEIGHT = 2400
        const val BASE = WINDOW_HEIGHT / 2
        const val STATUS_BAR = 100
        const val NAV_BAR = 60
        const val KB = 900
    }

    private class RecordingListener : InsetAwareConstraintLayout.KeyboardStateListener {
        val events = mutableListOf<String>()
        override fun onKeyboardShown() { events += "shown" }
        override fun onKeyboardHidden() { events += "hidden" }
        override fun onKeyboardAnimationEnded(isKeyboardVisible: Boolean) {
            events += "ended($isKeyboardVisible)"
        }
    }

    private val activity = mockk<BaseActivity>(relaxed = true)

    private lateinit var root: CoordinatorLayout
    private lateinit var sheet: View
    private lateinit var scrim: View
    private lateinit var dragHandle: View
    private lateinit var controller: PopupChatSheetController

    @Before
    fun setUp() {
        mockkObject(WindowSizeClassUtil)
        every { WindowSizeClassUtil.getWindowHeightPx(any()) } returns WINDOW_HEIGHT
        
        every { activity.currentFocus } returns null

        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        root = CoordinatorLayout(host)
        sheet = View(host)
        root.addView(
            sheet,
            CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                CoordinatorLayout.LayoutParams.WRAP_CONTENT
            ).apply { setBehavior(BottomSheetBehavior<View>()) }
        )
        scrim = View(host)
        root.addView(scrim)
        dragHandle = View(host)
        root.addView(dragHandle)
        host.setContentView(root)

        controller = PopupChatSheetController(
            activity = activity,
            views = PopupSheetViews(
                coordinatorRoot = root,
                bottomSheet = sheet,
                scrim = scrim,
                dragHandle = dragHandle,
            ),
        )
        controller.setup()
        idle()
    }

    @After
    fun tearDown() {
        clearMocks(activity)
        unmockkObject(WindowSizeClassUtil)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun settleAnimation() =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))

    private fun layoutRoot(height: Int) {
        root.measure(
            MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, height)
    }

    private fun dispatchInsets(imeVisible: Boolean, imeHeight: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAV_BAR))
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_BAR, 0, 0))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeHeight))
            .setVisible(WindowInsetsCompat.Type.ime(), imeVisible)
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
    }

    private fun behavior() = BottomSheetBehavior.from(sheet)

    
    @Test
    fun `G10 a visible IME through the real insets listener lifts the sheet`() {
        layoutRoot(WINDOW_HEIGHT)

        dispatchInsets(imeVisible = true, imeHeight = KB)

        assertEquals(BASE + KB, sheet.layoutParams.height)
        assertEquals(KB, sheet.bottomClearance())
        assertEquals(BASE + KB, behavior().peekHeight)
    }

    





    @Test
    fun `G10 an IME reported visible with zero height takes the resting branch`() {
        layoutRoot(WINDOW_HEIGHT)

        dispatchInsets(imeVisible = true, imeHeight = 0)

        assertEquals(BASE, sheet.layoutParams.height)
        assertEquals(NAV_BAR, sheet.bottomClearance())
    }

    







    @Test
    fun `G10c an IME visible with zero height dispatches keyboard shown while geometry rests`() {
        layoutRoot(WINDOW_HEIGHT)
        val listener = RecordingListener()
        controller.addKeyboardStateListener(listener)

        dispatchInsets(imeVisible = true, imeHeight = 0)
        idle()

        assertEquals(listOf("shown", "ended(true)"), listener.events)
        assertEquals("geometry must keep the height fold", BASE, sheet.layoutParams.height)
        assertEquals(NAV_BAR, sheet.bottomClearance())
    }

    





    @Test
    fun `G11 the clamp follows the current root height rather than the first pass`() {
        
        
        root.layout(0, 0, 1080, 0)
        assertEquals(0, root.height)
        dispatchInsets(imeVisible = false, imeHeight = 0)

        layoutRoot(1600)
        dispatchInsets(imeVisible = true, imeHeight = KB)

        
        assertEquals(1600 - STATUS_BAR, sheet.layoutParams.height)
    }

    





    @Test
    fun `S4 maximize with no keyboard leaves the padding alone`() {
        layoutRoot(WINDOW_HEIGHT)
        dispatchInsets(imeVisible = true, imeHeight = KB)
        dispatchInsets(imeVisible = false, imeHeight = 0)
        assertEquals("the lift must still be held", BASE + KB, sheet.layoutParams.height)
        assertEquals(NAV_BAR, sheet.bottomClearance())

        var onEndCount = 0
        controller.maximize { onEndCount++ }
        settleAnimation()

        assertEquals(WINDOW_HEIGHT - STATUS_BAR, sheet.layoutParams.height)
        assertEquals("padding must not be ramped out without a keyboard", NAV_BAR, sheet.bottomClearance())
        assertEquals(1, onEndCount)
    }

    
    @Test
    fun `S4b maximize with the keyboard visible animates its padding out`() {
        layoutRoot(WINDOW_HEIGHT)
        dispatchInsets(imeVisible = true, imeHeight = KB)
        assertEquals(KB, sheet.bottomClearance())

        controller.maximize { }
        settleAnimation()

        assertEquals(WINDOW_HEIGHT - STATUS_BAR, sheet.layoutParams.height)
        assertEquals(0, sheet.bottomClearance())
    }

    
    @Test
    fun `a second maximize call is ignored`() {
        layoutRoot(WINDOW_HEIGHT)
        dispatchInsets(imeVisible = false, imeHeight = 0)

        var onEndCount = 0
        controller.maximize { onEndCount++ }
        controller.maximize { onEndCount++ }
        settleAnimation()

        assertEquals(1, onEndCount)
    }

    
    @Test
    fun `a detail-pane hand-off runs once without growing the sheet`() {
        layoutRoot(WINDOW_HEIGHT)
        dispatchInsets(imeVisible = false, imeHeight = 0)
        val restingHeight = sheet.layoutParams.height

        var onEndCount = 0
        controller.handOffToDetailPane { onEndCount++ }
        controller.handOffToDetailPane { onEndCount++ }
        controller.maximize { onEndCount++ }
        settleAnimation()

        assertEquals(1, onEndCount)
        assertEquals(restingHeight, sheet.layoutParams.height)
    }

    
    @Test
    fun `insets arriving during the maximize animation do not move the sheet`() {
        layoutRoot(WINDOW_HEIGHT)
        dispatchInsets(imeVisible = true, imeHeight = KB)

        controller.maximize { }
        settleAnimation()
        val settledHeight = sheet.layoutParams.height

        dispatchInsets(imeVisible = false, imeHeight = 0)

        assertEquals(settledHeight, sheet.layoutParams.height)
    }
}
