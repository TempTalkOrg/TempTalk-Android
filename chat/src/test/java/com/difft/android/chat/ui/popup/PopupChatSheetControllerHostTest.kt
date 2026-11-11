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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration








@RunWith(RobolectricTestRunner::class)
class PopupChatSheetControllerHostTest {

    private companion object {
        const val WINDOW_HEIGHT = 2400
        const val BASE = WINDOW_HEIGHT / 2
        const val STATUS_BAR = 100
        const val NAV_BAR = 60
        const val KB = 900

        





        const val PANEL = KB - NAV_BAR
        const val PANEL_LIFT = PANEL + NAV_BAR
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
        every { activity.isFinishing } returns false
        every { activity.isDestroyed } returns false

        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        every { activity.resources } returns host.resources
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
        layoutRoot(WINDOW_HEIGHT)
        
        
        
        dispatchInsets(imeVisible = false, imeHeight = 0)
        idle()
    }

    @After
    fun tearDown() {
        clearMocks(activity)
        unmockkObject(WindowSizeClassUtil)
    }

    @Test
    fun `setup caps the sheet at the shared bottom sheet max width`() {
        val expectedPx = sheet.resources.getDimensionPixelSize(com.difft.android.base.R.dimen.bottom_sheet_max_width)

        assertEquals(expectedPx, BottomSheetBehavior.from(sheet).maxWidth)
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
        
        
        
        layoutRoot(WINDOW_HEIGHT)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAV_BAR))
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_BAR, 0, 0))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeHeight))
            .setVisible(WindowInsetsCompat.Type.ime(), imeVisible)
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
    }

    
    private fun contentBox() = sheet.layoutParams.height - sheet.bottomClearance()

    



    @Test
    fun `E1 a panel reported through the host lifts the sheet and grows the content box`() {
        controller.onChatPanelVisibilityChanged(visible = true, panelHeightPx = PANEL)
        settleAnimation()

        assertEquals(BASE + PANEL_LIFT, sheet.layoutParams.height)
        assertEquals("the panel must not consume padding, unlike the IME", NAV_BAR, sheet.bottomClearance())
        assertEquals(
            "the list box must match the keyboard state's, so the list keeps its size",
            BASE,
            contentBox() - PANEL
        )
    }

    





    @Test
    fun `E9 re-asserting an unchanged panel state writes nothing`() {
        controller.onChatPanelVisibilityChanged(visible = true, panelHeightPx = PANEL)
        settleAnimation()
        val height = sheet.layoutParams.height
        val padding = sheet.bottomClearance()

        controller.onChatPanelVisibilityChanged(visible = true, panelHeightPx = PANEL)

        assertEquals("no animation may start from a different value", height, sheet.layoutParams.height)
        assertEquals(padding, sheet.bottomClearance())
        settleAnimation()
        assertEquals(height, sheet.layoutParams.height)
    }

    



    @Test
    fun `E2 a listener registered through the host receives the keyboard-shown handshake`() {
        val listener = RecordingListener()
        controller.addKeyboardStateListener(listener)
        controller.onChatPanelVisibilityChanged(visible = true, panelHeightPx = PANEL)
        settleAnimation()

        dispatchInsets(imeVisible = true, imeHeight = KB)
        idle()

        assertEquals(listOf("shown", "ended(true)"), listener.events)
    }

    




    @Test
    fun `E2 interpolated IME insets still end the animation exactly once`() {
        val listener = RecordingListener()
        controller.addKeyboardStateListener(listener)

        dispatchInsets(imeVisible = true, imeHeight = 300)
        dispatchInsets(imeVisible = true, imeHeight = 600)
        dispatchInsets(imeVisible = true, imeHeight = KB)
        idle()

        assertEquals(listOf("shown", "ended(true)"), listener.events)
    }

    






    @Test
    fun `E4 the panel to keyboard handoff never changes the sheet height`() {
        controller.onChatPanelVisibilityChanged(visible = true, panelHeightPx = PANEL)
        settleAnimation()
        assertEquals(BASE + PANEL_LIFT, sheet.layoutParams.height)

        dispatchInsets(imeVisible = true, imeHeight = KB)
        idle()
        assertEquals(
            "no jump while both are momentarily active",
            BASE + PANEL_LIFT,
            sheet.layoutParams.height
        )
        assertEquals("the panel still owns the slot, so the IME must not pad", NAV_BAR, sheet.bottomClearance())

        
        controller.onChatPanelVisibilityChanged(visible = false, panelHeightPx = 0)
        settleAnimation()

        assertEquals("still no jump after the panel goes", BASE + PANEL_LIFT, sheet.layoutParams.height)
        assertEquals("the IME claims the slot the panel just freed", KB, sheet.bottomClearance())
    }

    

    




    @Test
    fun `E5 closing the panel holds the lift and returns the space to the content box`() {
        controller.onChatPanelVisibilityChanged(visible = true, panelHeightPx = PANEL)
        settleAnimation()

        controller.onChatPanelVisibilityChanged(visible = false, panelHeightPx = 0)
        settleAnimation()

        assertEquals(BASE + PANEL_LIFT, sheet.layoutParams.height)
        assertEquals(NAV_BAR, sheet.bottomClearance())
    }

    





    @Test
    fun `E6 a panel closed without releasing the freeze does not strand the padding`() {
        controller.freezeKeyboardPadding()
        controller.onChatPanelVisibilityChanged(visible = true, panelHeightPx = PANEL)
        settleAnimation()

        
        controller.onChatPanelVisibilityChanged(visible = false, panelHeightPx = 0)
        dispatchInsets(imeVisible = true, imeHeight = KB)
        idle()

        assertEquals("the IME must still be able to claim the padding", KB, sheet.bottomClearance())
        assertEquals("and no dip below the held lift", BASE + KB, sheet.layoutParams.height)
    }

    




    @Test
    fun `freezing through the host writes nothing while the keyboard is up`() {
        dispatchInsets(imeVisible = true, imeHeight = KB)
        idle()
        val height = sheet.layoutParams.height
        val padding = sheet.bottomClearance()

        controller.freezeKeyboardPadding()

        assertEquals(height, sheet.layoutParams.height)
        assertEquals(padding, sheet.bottomClearance())
    }

    

    




    @Test
    fun `R4 a listener removed through the host stops receiving callbacks`() {
        val listener = RecordingListener()
        controller.addKeyboardStateListener(listener)
        controller.removeKeyboardStateListener(listener)

        dispatchInsets(imeVisible = true, imeHeight = KB)
        idle()

        assertTrue("removed listeners must not be called", listener.events.isEmpty())
    }

    




    @Test
    fun `E10 after release nothing is dispatched and late host calls are inert`() {
        val listener = RecordingListener()
        controller.addKeyboardStateListener(listener)
        controller.onChatPanelVisibilityChanged(visible = true, panelHeightPx = PANEL)
        settleAnimation()
        val height = sheet.layoutParams.height
        val padding = sheet.bottomClearance()

        controller.release()

        
        controller.releaseKeyboardPaddingFreeze()
        controller.onChatPanelVisibilityChanged(visible = false, panelHeightPx = 0)
        dispatchInsets(imeVisible = false, imeHeight = 0)
        settleAnimation()

        assertTrue("no callback after release", listener.events.isEmpty())
        assertEquals(height, sheet.layoutParams.height)
        assertEquals(padding, sheet.bottomClearance())
    }
}
