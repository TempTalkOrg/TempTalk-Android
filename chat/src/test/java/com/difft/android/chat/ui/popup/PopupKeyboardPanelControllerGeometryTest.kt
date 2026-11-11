package com.difft.android.chat.ui.popup

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.difft.android.base.BaseActivity
import com.difft.android.base.widget.InsetAwareConstraintLayout
import com.difft.android.chat.ui.CHAT_PANEL_ANIM_DURATION_MS
import com.google.android.material.bottomsheet.BottomSheetBehavior
import io.mockk.clearMocks
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration










@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PopupKeyboardPanelControllerGeometryTest {

    private companion object {
        const val PARENT_WIDTH = 1080
        const val PARENT_HEIGHT = 2400

        






        const val BASE = 1200
        const val NAV_BAR = 60
        const val KB = 900
        const val PANEL = KB - NAV_BAR
        const val PANEL_LIFT = PANEL + NAV_BAR
    }

    
    private class CountingView(context: Context) : View(context) {
        var layoutParamWrites = 0
        var paddingWrites = 0

        override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
            layoutParamWrites++
            super.setLayoutParams(params)
        }

        override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
            paddingWrites++
            super.setPadding(left, top, right, bottom)
        }

        fun resetCounters() {
            layoutParamWrites = 0
            paddingWrites = 0
        }
    }

    private class Recorder : InsetAwareConstraintLayout.KeyboardStateListener {
        val events = mutableListOf<String>()
        override fun onKeyboardShown() {
            events += "shown"
        }

        override fun onKeyboardHidden() {
            events += "hidden"
        }

        override fun onKeyboardAnimationEnded(isKeyboardVisible: Boolean) {
            events += "ended=$isKeyboardVisible"
        }
    }

    private val hostActivity = mockk<BaseActivity>(relaxed = true)

    private lateinit var root: CoordinatorLayout
    private lateinit var sheet: CountingView
    private lateinit var behavior: BottomSheetBehavior<View>
    private lateinit var controller: PopupKeyboardPanelController

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        root = CoordinatorLayout(activity)
        sheet = CountingView(activity)
        behavior = BottomSheetBehavior<View>().apply {
            isFitToContents = true
            isHideable = true
            isDraggable = true
            skipCollapsed = true
            peekHeight = BASE
        }
        val params = CoordinatorLayout.LayoutParams(
            CoordinatorLayout.LayoutParams.MATCH_PARENT,
            BASE
        ).apply { setBehavior(behavior) }
        root.addView(sheet, params)
        activity.setContentView(root)
        layoutRoot()
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        idle()

        
        sheet.setPadding(0, 0, 0, NAV_BAR)

        controller = PopupKeyboardPanelController(hostActivity, root)
        controller.attach(sheet, behavior, BASE)
        sheet.resetCounters()
    }

    @After
    fun tearDown() {
        clearMocks(hostActivity)
    }

    private fun layoutRoot() {
        root.measure(
            MeasureSpec.makeMeasureSpec(PARENT_WIDTH, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(PARENT_HEIGHT, MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, PARENT_WIDTH, PARENT_HEIGHT)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun idleFor(millis: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    
    private fun settleAnimation() = idleFor(400)

    private fun insets(imeVisible: Boolean, imeHeight: Int, maxHeight: Int = 0) =
        controller.onWindowInsets(
            navigationBarPx = NAV_BAR,
            imeHeightPx = imeHeight,
            imeVisible = imeVisible,
            maxHeightPx = maxHeight,
        )

    
    private fun restingInsets() = insets(imeVisible = false, imeHeight = 0)

    private fun sheetHeight() = sheet.layoutParams.height

    



    private fun forceState(state: Int) = ReflectionHelpers.setField(behavior, "state", state)

    
    private fun heightAnimator(): ValueAnimator? =
        ReflectionHelpers.getField(controller, "heightAnimator")

    
    @Test
    fun `G1 a visible IME lifts the sheet and consumes the same pixels as padding`() {
        insets(imeVisible = true, imeHeight = KB)

        assertEquals(BASE + KB, sheetHeight())
        assertEquals(KB, sheet.bottomClearance())
        assertEquals(BASE + KB, behavior.peekHeight)
    }

    









    @Test
    fun `G2 an open panel lifts the sheet and leaves the padding at the navigation bar`() {
        restingInsets()

        controller.onPanelVisibilityChanged(visible = true, heightPx = PANEL)
        settleAnimation()

        assertEquals(BASE + PANEL_LIFT, sheetHeight())
        assertEquals(NAV_BAR, sheet.bottomClearance())
        assertEquals(
            "the list box must match the keyboard state's",
            BASE,
            sheetHeight() - sheet.bottomClearance() - PANEL
        )
    }

    




    @Test
    fun `G3 the panel lift animates for the shared duration on a decelerating curve`() {
        assertEquals(250L, CHAT_PANEL_ANIM_DURATION_MS)
        restingInsets()

        controller.onPanelVisibilityChanged(visible = true, heightPx = PANEL)

        val animator = requireNotNull(heightAnimator()) { "the panel lift must be animated" }
        assertEquals(CHAT_PANEL_ANIM_DURATION_MS, animator.duration)
        assertTrue(
            "must rise on the same curve as showPanel",
            animator.interpolator is DecelerateInterpolator
        )

        settleAnimation()
        assertEquals(BASE + PANEL_LIFT, sheetHeight())
    }

    





    @Test
    fun `G4 handing the slot from the keyboard to the panel writes padding once and height never`() {
        restingInsets()
        insets(imeVisible = true, imeHeight = KB)
        sheet.resetCounters()

        controller.onPanelVisibilityChanged(visible = true, heightPx = PANEL)
        settleAnimation()

        assertEquals(1, sheet.paddingWrites)
        assertEquals(NAV_BAR, sheet.bottomClearance())
        assertEquals("the sheet must not move during the handoff", 0, sheet.layoutParamWrites)
        assertEquals(BASE + KB, sheetHeight())
    }

    






    @Test
    fun `G5 closing the panel without releasing the freeze still restores IME padding`() {
        restingInsets()
        insets(imeVisible = true, imeHeight = KB)
        controller.onPanelVisibilityChanged(visible = true, heightPx = PANEL)
        settleAnimation()

        controller.onPanelVisibilityChanged(visible = false, heightPx = 0)
        settleAnimation()
        insets(imeVisible = true, imeHeight = KB)

        assertEquals(KB, sheet.bottomClearance())
    }

    
    @Test
    fun `G6 closing the panel holds the lift instead of collapsing the sheet`() {
        restingInsets()
        controller.onPanelVisibilityChanged(visible = true, heightPx = PANEL)
        settleAnimation()

        controller.onPanelVisibilityChanged(visible = false, heightPx = 0)
        settleAnimation()

        assertEquals(BASE + PANEL_LIFT, sheetHeight())
        assertEquals(NAV_BAR, sheet.bottomClearance())
    }

    
    @Test
    fun `G7 a height change while dragging is deferred until the sheet settles`() {
        restingInsets()
        forceState(BottomSheetBehavior.STATE_DRAGGING)
        sheet.resetCounters()

        controller.onPanelVisibilityChanged(visible = true, heightPx = PANEL)
        settleAnimation()

        assertEquals(0, sheet.layoutParamWrites)
        assertEquals(BASE, sheetHeight())

        forceState(BottomSheetBehavior.STATE_EXPANDED)
        controller.onSheetSettled()

        assertEquals(BASE + PANEL_LIFT, sheetHeight())
    }

    





    @Test
    fun `a drag starting mid-animation stops the in-flight lift instead of racing it`() {
        restingInsets()
        controller.onPanelVisibilityChanged(visible = true, heightPx = PANEL)
        assertNotNull("the lift animation must be in flight", heightAnimator())
        val heightWhenGrabbed = sheetHeight()

        forceState(BottomSheetBehavior.STATE_DRAGGING)
        insets(imeVisible = true, imeHeight = KB)

        assertNull(
            "the in-flight lift must be cancelled by the deferral, not left racing the drag",
            heightAnimator()
        )
        sheet.resetCounters()
        settleAnimation()
        assertEquals(
            "the cancelled animator must not keep writing heights during the drag",
            0,
            sheet.layoutParamWrites
        )
        assertEquals(heightWhenGrabbed, sheetHeight())

        forceState(BottomSheetBehavior.STATE_EXPANDED)
        controller.onSheetSettled()

        assertEquals(BASE + KB, sheetHeight())
    }

    



    @Test
    fun `G8 the maximize animation takes exclusive ownership of the sheet geometry`() {
        restingInsets()
        controller.onPanelVisibilityChanged(visible = true, heightPx = PANEL)
        idleFor(60)

        controller.setMaximizeAnimating(true)
        val heightAtGate = sheetHeight()
        val paddingAtGate = sheet.bottomClearance()
        val peekAtGate = behavior.peekHeight
        sheet.resetCounters()

        insets(imeVisible = true, imeHeight = KB)
        controller.onPanelVisibilityChanged(visible = true, heightPx = PANEL)
        settleAnimation()

        assertEquals(0, sheet.layoutParamWrites)
        assertEquals(0, sheet.paddingWrites)
        assertEquals(heightAtGate, sheetHeight())
        assertEquals(paddingAtGate, sheet.bottomClearance())
        assertEquals(peekAtGate, behavior.peekHeight)
    }

    
    @Test
    fun `G9 an identical insets pass performs no writes`() {
        insets(imeVisible = true, imeHeight = KB)
        sheet.resetCounters()

        insets(imeVisible = true, imeHeight = KB)

        assertEquals(0, sheet.paddingWrites)
        assertEquals(0, sheet.layoutParamWrites)
    }

    
    @Test
    fun `the sheet height is clamped to the supplied maximum and the held lift follows it`() {
        insets(imeVisible = true, imeHeight = KB, maxHeight = 1800)
        assertEquals(1800, sheetHeight())

        
        insets(imeVisible = false, imeHeight = 0, maxHeight = 1800)
        assertEquals(1800, sheetHeight())
    }

    




    @Test
    fun `X3 maximize gates geometry but never gates the keyboard edge dispatch`() {
        val recorder = Recorder()
        controller.addKeyboardStateListener(recorder)
        insets(imeVisible = true, imeHeight = KB)
        idle()
        recorder.events.clear()

        controller.setMaximizeAnimating(true)
        sheet.resetCounters()
        insets(imeVisible = false, imeHeight = 0)
        idle()

        assertTrue("the keyboard-hidden edge must still reach listeners", "hidden" in recorder.events)
        assertEquals(0, sheet.layoutParamWrites)
        assertEquals(0, sheet.paddingWrites)
    }

    





    @Test
    fun `G12 the controller is inert after release`() {
        val recorder = Recorder()
        controller.addKeyboardStateListener(recorder)
        restingInsets()
        insets(imeVisible = true, imeHeight = KB)
        controller.onPanelVisibilityChanged(visible = true, heightPx = PANEL)

        controller.release()
        sheet.resetCounters()
        settleAnimation()

        assertEquals(emptyList<String>(), recorder.events)
        assertEquals(0, sheet.layoutParamWrites)
        assertEquals(0, sheet.paddingWrites)

        
        controller.onPanelVisibilityChanged(visible = false, heightPx = 0)
        controller.freezeKeyboardPadding()
        controller.releaseKeyboardPaddingFreeze()
        insets(imeVisible = true, imeHeight = KB)
        settleAnimation()

        assertEquals(0, sheet.layoutParamWrites)
        assertEquals(0, sheet.paddingWrites)
    }
}
