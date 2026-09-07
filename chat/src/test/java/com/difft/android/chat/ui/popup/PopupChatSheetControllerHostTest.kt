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

/**
 * The `KeyboardPanelHost` forwarding chain that the popup Activities expose: the five methods on
 * [PopupChatSheetController] that the Activities' overrides delegate to.
 *
 * These are the E-row assertions of the design's test inventory, expressed at the highest tier that
 * is reachable without a Hilt test harness. The rows the design specifies end-to-end drive the real
 * `ChatMessageInputFragment` inside a launched `@AndroidEntryPoint` popup Activity; this repository
 * has no Hilt test infrastructure, so those halves are covered by manual QA. What IS pinned here is
 * everything on the host side of the handshake:
 *
 *  - the panel geometry the fragment reports actually reaches the sheet (Bug (1)),
 *  - a listener registered through the chain actually receives the keyboard callbacks that the
 *    fragment's (unmodified) listener body turns into a panel dismissal (Bug (2)),
 *  - the derived-freeze form cannot strand the padding, whatever the fragment does.
 *
 * The Activity is mocked; the sheet, scrim, root and [BottomSheetBehavior] are real.
 */
@RunWith(RobolectricTestRunner::class)
class PopupChatSheetControllerHostTest {

    private companion object {
        const val WINDOW_HEIGHT = 2400
        const val BASE = WINDOW_HEIGHT / 2
        const val STATUS_BAR = 100
        const val NAV_BAR = 60
        const val KB = 900

        /**
         * The fragment sizes the panel from the cached keyboard height, which the popup persists as
         * `ime - nav` — so the panel that pairs with a [KB]-inset keyboard is `KB - NAV_BAR`, not
         * `KB`. The panel is content above the navigation-bar padding, so it lifts the sheet by
         * [PANEL_LIFT] == `PANEL + NAV_BAR` == `KB`, and the two states are interchangeable.
         */
        const val PANEL = KB - NAV_BAR
        const val PANEL_LIFT = PANEL + NAV_BAR
    }

    /** Records what a real `KeyboardStateListener` would observe, in order. */
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
        // Robolectric does not synthesise insets from requestApplyInsets, so drive the resting pass
        // a real popup always receives before any user interaction. It is what configures the
        // behavior and attaches the keyboard/panel controller.
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
        // The controller reads the root's current height for its ceiling on every pass. Robolectric
        // re-lays the root out against its small default window whenever an animation triggers a
        // layout, so restate the real popup geometry (root == the whole window) before each pass.
        layoutRoot(WINDOW_HEIGHT)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAV_BAR))
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_BAR, 0, 0))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeHeight))
            .setVisible(WindowInsetsCompat.Type.ime(), imeVisible)
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
    }

    /** The content box the message list and the input row share. */
    private fun contentBox() = sheet.layoutParams.height - sheet.paddingBottom

    // --- Bug (1) ---------------------------------------------------------------------------

    /**
     * E1 (host half) — a panel reported through the host lifts the sheet WITHOUT consuming bottom
     * padding, so the space the panel needs comes from the sheet growing and not from the message
     * list. That identity is Invariant P, and it is the whole of Bug (1): before this change the
     * panel expanded inside a sheet that never grew.
     *
     * The height is derived from the list box rather than observed: the panel is content stacked
     * above the navigation-bar padding, so the sheet must grow by the panel height AND that bar for
     * the list box (`contentBox() - PANEL`) to land on [BASE] — the value the keyboard state
     * produces. Lifting by only `PANEL` leaves the panel state one navigation bar lower than the
     * keyboard state, which is the jump QA reported.
     */
    @Test
    fun `E1 a panel reported through the host lifts the sheet and grows the content box`() {
        controller.onChatPanelVisibilityChanged(visible = true, panelHeightPx = PANEL)
        settleAnimation()

        assertEquals(BASE + PANEL_LIFT, sheet.layoutParams.height)
        assertEquals("the panel must not consume padding, unlike the IME", NAV_BAR, sheet.paddingBottom)
        assertEquals(
            "the list box must match the keyboard state's, so the list keeps its size",
            BASE,
            contentBox() - PANEL
        )
    }

    /**
     * E9 (O2/O4, the MORE <-> GIF in-place swaps) — those branches deliberately skip `showPanel`,
     * so the panel never re-animates; the host is only re-told the same geometry. Re-asserting an
     * unchanged panel state must therefore write nothing at all, or the sheet would restart its
     * lift animation while the panel sits still.
     */
    @Test
    fun `E9 re-asserting an unchanged panel state writes nothing`() {
        controller.onChatPanelVisibilityChanged(visible = true, panelHeightPx = PANEL)
        settleAnimation()
        val height = sheet.layoutParams.height
        val padding = sheet.paddingBottom

        controller.onChatPanelVisibilityChanged(visible = true, panelHeightPx = PANEL)

        assertEquals("no animation may start from a different value", height, sheet.layoutParams.height)
        assertEquals(padding, sheet.paddingBottom)
        settleAnimation()
        assertEquals(height, sheet.layoutParams.height)
    }

    // --- Bug (2) ---------------------------------------------------------------------------

    /**
     * E2/E3 (host half) — the callback that dismisses the panel. The fragment's listener body is
     * unmodified; it dismisses the panel from `onKeyboardAnimationEnded(true)`. Before this change
     * the popup never registered that listener at all, so the callback never arrived and the panel
     * and keyboard coexisted. Registering through the host chain must deliver `onKeyboardShown`
     * and then exactly one `onKeyboardAnimationEnded(true)`.
     */
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

    /**
     * Interpolated IME insets (a device that delivers the keyboard height in steps) must still
     * produce exactly ONE `ended(true)`, one frame after the last step — otherwise the panel would
     * be dismissed mid-animation.
     */
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

    /**
     * E4 — the panel/keyboard handoff must not move the sheet, at ANY step of the sequence. With a
     * panel of `PANEL` open the lift is `PANEL + NAV_BAR`; the IME that replaces it lifts by `KB`,
     * and those are the same number, so `max` is constant through the frame where both are active
     * and through the frame after the panel goes. Only the padding moves, from the navigation bar to
     * the IME. This is the row that fails if the panel is compared against the raw IME inset.
     */
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
        assertEquals("the panel still owns the slot, so the IME must not pad", NAV_BAR, sheet.paddingBottom)

        // What the fragment's listener body does on ended(true): hide the panel.
        controller.onChatPanelVisibilityChanged(visible = false, panelHeightPx = 0)
        settleAnimation()

        assertEquals("still no jump after the panel goes", BASE + PANEL_LIFT, sheet.layoutParams.height)
        assertEquals("the IME claims the slot the panel just freed", KB, sheet.paddingBottom)
    }

    // --- close paths ------------------------------------------------------------------------

    /**
     * E5 (C1, tap the message list) — closing the panel holds the lift rather than collapsing back
     * to `baseHeight`, so the list grows into the freed space instead of the sheet shrinking under
     * the user.
     */
    @Test
    fun `E5 closing the panel holds the lift and returns the space to the content box`() {
        controller.onChatPanelVisibilityChanged(visible = true, panelHeightPx = PANEL)
        settleAnimation()

        controller.onChatPanelVisibilityChanged(visible = false, panelHeightPx = 0)
        settleAnimation()

        assertEquals(BASE + PANEL_LIFT, sheet.layoutParams.height)
        assertEquals(NAV_BAR, sheet.paddingBottom)
    }

    /**
     * E6 (C5, the group `@`-insert path) — that path hides the panel and opens the keyboard WITHOUT
     * ever calling `releaseKeyboardPaddingFreeze`. A freeze latch would stay set and pin the padding
     * at the navigation bar forever, leaving the input row behind the keyboard. The padding is
     * derived from panel visibility instead, so the next IME pass is correct with no release call.
     */
    @Test
    fun `E6 a panel closed without releasing the freeze does not strand the padding`() {
        controller.freezeKeyboardPadding()
        controller.onChatPanelVisibilityChanged(visible = true, panelHeightPx = PANEL)
        settleAnimation()

        // The `@` path: panel hidden synchronously, no release call, then the keyboard opens.
        controller.onChatPanelVisibilityChanged(visible = false, panelHeightPx = 0)
        dispatchInsets(imeVisible = true, imeHeight = KB)
        idle()

        assertEquals("the IME must still be able to claim the padding", KB, sheet.paddingBottom)
        assertEquals("and no dip below the held lift", BASE + KB, sheet.layoutParams.height)
    }

    /**
     * The freeze hook is an idempotent recompute, not a latch: calling it while the keyboard is up
     * must not change the geometry that is already applied. If it dropped the height, the sheet
     * would visibly collapse in the frame between the keyboard closing and the panel appearing.
     */
    @Test
    fun `freezing through the host writes nothing while the keyboard is up`() {
        dispatchInsets(imeVisible = true, imeHeight = KB)
        idle()
        val height = sheet.layoutParams.height
        val padding = sheet.paddingBottom

        controller.freezeKeyboardPadding()

        assertEquals(height, sheet.layoutParams.height)
        assertEquals(padding, sheet.paddingBottom)
    }

    // --- teardown ---------------------------------------------------------------------------

    /**
     * R4 — the popup's listener list outlives the input fragment's view, so `onDestroyView`'s
     * removal is a correctness requirement rather than hygiene. Removal through the chain must
     * actually stop delivery.
     */
    @Test
    fun `R4 a listener removed through the host stops receiving callbacks`() {
        val listener = RecordingListener()
        controller.addKeyboardStateListener(listener)
        controller.removeKeyboardStateListener(listener)

        dispatchInsets(imeVisible = true, imeHeight = KB)
        idle()

        assertTrue("removed listeners must not be called", listener.events.isEmpty())
    }

    /**
     * E10 (C9/C10) — the popup dismisses itself from `STATE_HIDDEN`, so an IME-hidden edge with a
     * pending dispatch is guaranteed on every dismissal. After `release()` nothing is dispatched
     * and no geometry is written, and the late calls the fragment may still make must not throw.
     */
    @Test
    fun `E10 after release nothing is dispatched and late host calls are inert`() {
        val listener = RecordingListener()
        controller.addKeyboardStateListener(listener)
        controller.onChatPanelVisibilityChanged(visible = true, panelHeightPx = PANEL)
        settleAnimation()
        val height = sheet.layoutParams.height
        val padding = sheet.paddingBottom

        controller.release()

        // Exactly what ChatMessageInputFragment.onDestroyView does, after the Activity is gone.
        controller.releaseKeyboardPaddingFreeze()
        controller.onChatPanelVisibilityChanged(visible = false, panelHeightPx = 0)
        dispatchInsets(imeVisible = false, imeHeight = 0)
        settleAnimation()

        assertTrue("no callback after release", listener.events.isEmpty())
        assertEquals(height, sheet.layoutParams.height)
        assertEquals(padding, sheet.paddingBottom)
    }
}
