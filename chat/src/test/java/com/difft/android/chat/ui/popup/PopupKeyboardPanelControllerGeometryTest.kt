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

/**
 * G1-G9, G12 (plus the two rows the wiring task added) — [PopupKeyboardPanelController]'s geometry
 * half against a real [CoordinatorLayout] / [BottomSheetBehavior] / [View], no geometry mocks.
 *
 * The sheet view counts its own `setLayoutParams` / `setPadding` calls, because several rows assert
 * that NO intermediate value was ever laid out — a claim a final-value sample cannot make. The
 * activity is mocked only because the controller reads `isFinishing` / `isDestroyed` from it for the
 * dispatch liveness guard; nothing in this file exercises dispatch geometry through it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PopupKeyboardPanelControllerGeometryTest {

    private companion object {
        const val PARENT_WIDTH = 1080
        const val PARENT_HEIGHT = 2400

        /**
         * Design fixtures: resting sheet, nav bar, a real keyboard, and the panel the fragment sizes
         * from the cached keyboard height — which the popup persists as `ime - nav`, hence
         * `PANEL == KB - NAV_BAR`. The panel is content above the navigation-bar padding, so it
         * lifts the sheet by [PANEL_LIFT] == `PANEL + NAV_BAR` == `KB`: that identity is what makes
         * the keyboard and panel states interchangeable with no jump.
         */
        const val BASE = 1200
        const val NAV_BAR = 60
        const val KB = 900
        const val PANEL = KB - NAV_BAR
        const val PANEL_LIFT = PANEL + NAV_BAR
    }

    /** Counts the writes the controller performs so "nothing was laid out" can be asserted. */
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

        // Mirror the pre-attach baseline the sheet controller writes in configureBehavior().
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

    /** Runs the 250 ms panel animation to completion. */
    private fun settleAnimation() = idleFor(400)

    private fun insets(imeVisible: Boolean, imeHeight: Int, maxHeight: Int = 0) =
        controller.onWindowInsets(
            navigationBarPx = NAV_BAR,
            imeHeightPx = imeHeight,
            imeVisible = imeVisible,
            maxHeightPx = maxHeight,
        )

    /** The resting insets pass that always precedes any panel interaction on a real device. */
    private fun restingInsets() = insets(imeVisible = false, imeHeight = 0)

    private fun sheetHeight() = sheet.layoutParams.height

    /**
     * `BottomSheetBehavior.setState` refuses DRAGGING/SETTLING from outside, so the drag rows write
     * the field the controller reads. Only `behavior.state` matters to the deferral branch.
     */
    private fun forceState(state: Int) = ReflectionHelpers.setField(behavior, "state", state)

    /** The controller owns its lift animator privately; the drag rows assert its lifecycle. */
    private fun heightAnimator(): ValueAnimator? =
        ReflectionHelpers.getField(controller, "heightAnimator")

    /** G1 — the keyboard-only path, byte-for-byte the geometry the popup applied before this change. */
    @Test
    fun `G1 a visible IME lifts the sheet and consumes the same pixels as padding`() {
        insets(imeVisible = true, imeHeight = KB)

        assertEquals(BASE + KB, sheetHeight())
        assertEquals(KB, sheet.paddingBottom)
        assertEquals(BASE + KB, behavior.peekHeight)
    }

    /**
     * G2 — Bug ①. The panel lifts the sheet WITHOUT consuming padding, so the space it needs comes
     * from the sheet growing rather than from the message list.
     *
     * The expected height is derived from the list-box invariant: the panel is content stacked above
     * the navigation-bar padding, so the sheet must grow by the panel height AND that bar for the
     * list box (`height - padding - panel`) to land on [BASE] — the same list box G1's keyboard state
     * produces. A lift of only `PANEL` leaves the sheet one navigation bar short, which is the jump
     * QA saw on every keyboard<->panel switch.
     */
    @Test
    fun `G2 an open panel lifts the sheet and leaves the padding at the navigation bar`() {
        restingInsets()

        controller.onPanelVisibilityChanged(visible = true, heightPx = PANEL)
        settleAnimation()

        assertEquals(BASE + PANEL_LIFT, sheetHeight())
        assertEquals(NAV_BAR, sheet.paddingBottom)
        assertEquals(
            "the list box must match the keyboard state's",
            BASE,
            sheetHeight() - sheet.paddingBottom - PANEL
        )
    }

    /**
     * G3 — the sheet must rise on the same curve as the panel's own expansion, or the message list
     * is squeezed for part of the transition. `showPanel` uses 250 ms + decelerate for its own
     * 0→height animation, and the duration constant has a single owner so the two cannot drift.
     */
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

    /**
     * G4 — the keyboard→panel handoff, the transition most at risk of a visible jump. The padding
     * moves to the nav bar in ONE write and the height is not written at all, because
     * `max(ime, panel + nav)` is unchanged. The zero height writes are the strongest form of the
     * no-jump claim: not merely "the same value at the end", but "the sheet was never resized".
     */
    @Test
    fun `G4 handing the slot from the keyboard to the panel writes padding once and height never`() {
        restingInsets()
        insets(imeVisible = true, imeHeight = KB)
        sheet.resetCounters()

        controller.onPanelVisibilityChanged(visible = true, heightPx = PANEL)
        settleAnimation()

        assertEquals(1, sheet.paddingWrites)
        assertEquals(NAV_BAR, sheet.paddingBottom)
        assertEquals("the sheet must not move during the handoff", 0, sheet.layoutParamWrites)
        assertEquals(BASE + KB, sheetHeight())
    }

    /**
     * G5 — anti-leak. The panel is closed without any `releaseKeyboardPaddingFreeze()` call (the
     * group `@`-insert path does exactly that). Because padding is derived from panel visibility
     * instead of a freeze latch, the next keyboard-visible pass still applies the IME padding.
     * Under a latch this would stay stuck at the nav bar and the input row would sit behind the
     * keyboard.
     */
    @Test
    fun `G5 closing the panel without releasing the freeze still restores IME padding`() {
        restingInsets()
        insets(imeVisible = true, imeHeight = KB)
        controller.onPanelVisibilityChanged(visible = true, heightPx = PANEL)
        settleAnimation()

        controller.onPanelVisibilityChanged(visible = false, heightPx = 0)
        settleAnimation()
        insets(imeVisible = true, imeHeight = KB)

        assertEquals(KB, sheet.paddingBottom)
    }

    /** G6 — closing the panel never restores `baseHeight`; the freed space goes to the list. */
    @Test
    fun `G6 closing the panel holds the lift instead of collapsing the sheet`() {
        restingInsets()
        controller.onPanelVisibilityChanged(visible = true, heightPx = PANEL)
        settleAnimation()

        controller.onPanelVisibilityChanged(visible = false, heightPx = 0)
        settleAnimation()

        assertEquals(BASE + PANEL_LIFT, sheetHeight())
        assertEquals(NAV_BAR, sheet.paddingBottom)
    }

    /** G7 — a height write during a drag would reposition the sheet under the user's finger. */
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

    /**
     * A drag that starts WHILE the panel lift is animating. Deferring the new target is not enough
     * on its own: the running animator writes heights directly, so it would keep resizing the sheet
     * mid-gesture and would then overwrite the value replayed by `onSheetSettled()`. The deferral
     * branch therefore cancels it.
     */
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

    /**
     * G8 — while the maximize animator owns the sheet, the controller writes nothing at all, and any
     * lift animation it had started is cancelled so the two animators cannot fight.
     */
    @Test
    fun `G8 the maximize animation takes exclusive ownership of the sheet geometry`() {
        restingInsets()
        controller.onPanelVisibilityChanged(visible = true, heightPx = PANEL)
        idleFor(60)

        controller.setMaximizeAnimating(true)
        val heightAtGate = sheetHeight()
        val paddingAtGate = sheet.paddingBottom
        val peekAtGate = behavior.peekHeight
        sheet.resetCounters()

        insets(imeVisible = true, imeHeight = KB)
        controller.onPanelVisibilityChanged(visible = true, heightPx = PANEL)
        settleAnimation()

        assertEquals(0, sheet.layoutParamWrites)
        assertEquals(0, sheet.paddingWrites)
        assertEquals(heightAtGate, sheetHeight())
        assertEquals(paddingAtGate, sheet.paddingBottom)
        assertEquals(peekAtGate, behavior.peekHeight)
    }

    /** G9 — a repeated identical insets pass must not force a layout; today's code rewrote both. */
    @Test
    fun `G9 an identical insets pass performs no writes`() {
        insets(imeVisible = true, imeHeight = KB)
        sheet.resetCounters()

        insets(imeVisible = true, imeHeight = KB)

        assertEquals(0, sheet.paddingWrites)
        assertEquals(0, sheet.layoutParamWrites)
    }

    /** The clamp keeps the sheet — and therefore its input row — inside the window. */
    @Test
    fun `the sheet height is clamped to the supplied maximum and the held lift follows it`() {
        insets(imeVisible = true, imeHeight = KB, maxHeight = 1800)
        assertEquals(1800, sheetHeight())

        // Idempotence: the clamped lift is fed back in and must not re-inflate.
        insets(imeVisible = false, imeHeight = 0, maxHeight = 1800)
        assertEquals(1800, sheetHeight())
    }

    /**
     * X3's controller half — geometry is gated by the maximize flag, dispatch deliberately is not.
     * Maximize hides the keyboard before raising the flag, so the IME-hidden edge arrives afterwards
     * and gating it would strand the keyboard latch at "shown" for the rest of the session.
     */
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

    /**
     * G12 — `release()` is terminal. It drops the listeners and the pending animation-end runnable,
     * and nulling the sheet makes every later geometry call an inert no-op rather than a crash or a
     * write into a dead window. The applied-state guards are deliberately not reset: a re-attach
     * after release is not a supported state (see [PopupKeyboardPanelController.release]).
     */
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

        // Late calls from a fragment tearing down after the Activity must not throw.
        controller.onPanelVisibilityChanged(visible = false, heightPx = 0)
        controller.freezeKeyboardPadding()
        controller.releaseKeyboardPaddingFreeze()
        insets(imeVisible = true, imeHeight = KB)
        settleAnimation()

        assertEquals(0, sheet.layoutParamWrites)
        assertEquals(0, sheet.paddingWrites)
    }
}
