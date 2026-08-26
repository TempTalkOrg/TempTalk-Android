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

/**
 * G10, G11, S4, S4b — [PopupChatSheetController] driven through its REAL
 * `OnApplyWindowInsetsListener` with real [WindowInsetsCompat] values.
 *
 * The rows above this one exercise [PopupKeyboardPanelController] directly; these exist because the
 * listener is where the wiring can go wrong without the controller noticing: the `imeHeight > 0`
 * fold, the per-pass `maxHeightPx` refresh, and the maximize seam's keyboard source.
 *
 * The Activity is mocked (it is `@AndroidEntryPoint`, and no Hilt test harness exists in this
 * repository); the sheet, scrim, root and [BottomSheetBehavior] are all real.
 */
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
        // maximize() hides the keyboard through the focused view; there is none in this harness.
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

    /** G10 — the rewired listener produces the same three values the old inline branch did. */
    @Test
    fun `G10 a visible IME through the real insets listener lifts the sheet`() {
        layoutRoot(WINDOW_HEIGHT)

        dispatchInsets(imeVisible = true, imeHeight = KB)

        assertEquals(BASE + KB, sheet.layoutParams.height)
        assertEquals(KB, sheet.paddingBottom)
        assertEquals(BASE + KB, behavior().peekHeight)
    }

    /**
     * G10's second half — an IME reported visible with a zero height must keep taking the resting
     * branch for GEOMETRY, exactly as the `isImeVisible && imeHeight > 0` guard did before the
     * rewiring. The fold now lives inside the controller and covers geometry only; the dispatch half
     * of the same frame is the row below.
     */
    @Test
    fun `G10 an IME reported visible with zero height takes the resting branch`() {
        layoutRoot(WINDOW_HEIGHT)

        dispatchInsets(imeVisible = true, imeHeight = 0)

        assertEquals(BASE, sheet.layoutParams.height)
        assertEquals(NAV_BAR, sheet.paddingBottom)
    }

    /**
     * G10c — the raw/folded split. `InsetAwareConstraintLayout` (the full-screen path) latches
     * `onKeyboardShown` on the RAW `isVisible(ime())`, so the popup must too: on the frames where the
     * system reports the IME visible with `bottom == 0` (floating/split keyboards, transient frames)
     * a full-screen chat dismisses its open action panel, and a popup that folded the height into the
     * dispatch would stay silent and leave the panel stacked behind the keyboard. Geometry keeps the
     * fold and stays at rest in the same frame.
     */
    @Test
    fun `G10c an IME visible with zero height dispatches keyboard shown while geometry rests`() {
        layoutRoot(WINDOW_HEIGHT)
        val listener = RecordingListener()
        controller.addKeyboardStateListener(listener)

        dispatchInsets(imeVisible = true, imeHeight = 0)
        idle()

        assertEquals(listOf("shown", "ended(true)"), listener.events)
        assertEquals("geometry must keep the height fold", BASE, sheet.layoutParams.height)
        assertEquals(NAV_BAR, sheet.paddingBottom)
    }

    /**
     * G11 — `maxHeightPx` is recomputed on every pass. The first pass runs before the root is laid
     * out, so a once-only computation would fall back to the full window height and the clamp would
     * never engage again. Here the root is then laid out SHORTER than the window (multi-window), and
     * the clamp must follow the current root, not the value the first pass saw.
     */
    @Test
    fun `G11 the clamp follows the current root height rather than the first pass`() {
        // Pass 1: the root has no height yet, as on the first dispatch of a real popup, so the
        // ceiling falls back to the window height (2400 - 100 = 2300) and nothing is clamped.
        root.layout(0, 0, 1080, 0)
        assertEquals(0, root.height)
        dispatchInsets(imeVisible = false, imeHeight = 0)

        layoutRoot(1600)
        dispatchInsets(imeVisible = true, imeHeight = KB)

        // Unclamped this would be BASE + KB == 2100; the refreshed ceiling is 1600 - STATUS_BAR.
        assertEquals(1600 - STATUS_BAR, sheet.layoutParams.height)
    }

    /**
     * S4 — the maximize seam. The keyboard was shown earlier and is now gone, so the sheet is still
     * held above `baseHeight`. The old `currentHeight > baseHeight` inference read that as "keyboard
     * visible" and animated the navigation-bar padding away for no reason; the real IME state says
     * otherwise, so the padding is left alone.
     */
    @Test
    fun `S4 maximize with no keyboard leaves the padding alone`() {
        layoutRoot(WINDOW_HEIGHT)
        dispatchInsets(imeVisible = true, imeHeight = KB)
        dispatchInsets(imeVisible = false, imeHeight = 0)
        assertEquals("the lift must still be held", BASE + KB, sheet.layoutParams.height)
        assertEquals(NAV_BAR, sheet.paddingBottom)

        var onEndCount = 0
        controller.maximize { onEndCount++ }
        settleAnimation()

        assertEquals(WINDOW_HEIGHT - STATUS_BAR, sheet.layoutParams.height)
        assertEquals("padding must not be ramped out without a keyboard", NAV_BAR, sheet.paddingBottom)
        assertEquals(1, onEndCount)
    }

    /** S4b — with the keyboard actually up, the padding it consumed is still animated back out. */
    @Test
    fun `S4b maximize with the keyboard visible animates its padding out`() {
        layoutRoot(WINDOW_HEIGHT)
        dispatchInsets(imeVisible = true, imeHeight = KB)
        assertEquals(KB, sheet.paddingBottom)

        controller.maximize { }
        settleAnimation()

        assertEquals(WINDOW_HEIGHT - STATUS_BAR, sheet.layoutParams.height)
        assertEquals(0, sheet.paddingBottom)
    }

    /** The double-tap guard still runs exactly one maximize, now from inside the controller. */
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

    /** Once maximize owns the sheet, a late insets pass must not write geometry underneath it. */
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
