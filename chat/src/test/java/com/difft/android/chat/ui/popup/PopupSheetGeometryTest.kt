package com.difft.android.chat.ui.popup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * U1-U16 — the popup sheet's lift arithmetic, asserted numerically.
 *
 * [PopupSheetGeometry.compute] is a pure function with zero Android imports, so these are plain
 * JUnit rows: no Robolectric, no mocks, milliseconds to run. They are the cheapest place to catch
 * the three regressions that would otherwise only show up on a device — the panel being made to
 * consume bottom padding (which re-squeezes the message list), the freeze latch coming back (which
 * strands the input row behind the keyboard), and the panel lift being compared against the raw IME
 * inset (which drops the sheet's top edge by one navigation bar on every keyboard<->panel switch).
 *
 * Fixtures, shared by every row: base = 1200, navBar = 60, keyboard inset = 900, panel = 840.
 * `PANEL == KB - NAV` is not a coincidence: the panel is sized from the cached keyboard height,
 * which the popup persists as `ime - nav`. Its lift contribution is [PANEL_LIFT] == `PANEL + NAV`
 * == `KB`, which is what makes the two states interchangeable.
 *
 * Every panel-state expectation below is DERIVED from one invariant rather than read off the
 * implementation: in any lifted state the list box — `height - padding - (panel, if shown)` — equals
 * [BASE] exactly, so the sheet's top edge and the message list are identical in the keyboard state,
 * the panel state, and the frame where both are visible.
 */
class PopupSheetGeometryTest {

    private companion object {
        const val BASE = 1200
        const val NAV = 60
        const val KB = 900
        const val PANEL = KB - NAV

        /** The panel sits above the navigation-bar padding, so it lifts by its height plus that bar. */
        const val PANEL_LIFT = PANEL + NAV
    }

    private fun input(
        imeHeight: Int = 0,
        imeVisible: Boolean = false,
        panelHeight: Int = 0,
        panelVisible: Boolean = false,
        held: Int = 0,
        max: Int = 0,
    ) = SheetLiftInput(
        baseHeightPx = BASE,
        navigationBarHeightPx = NAV,
        imeHeightPx = imeHeight,
        imeVisible = imeVisible,
        panelHeightPx = panelHeight,
        panelVisible = panelVisible,
        heldLiftPx = held,
        maxHeightPx = max,
    )

    /** U1 — resting: no lift source, nothing held. */
    @Test
    fun `U1 resting sheet keeps base height and nav-bar padding`() {
        val geometry = PopupSheetGeometry.compute(input())

        assertEquals(BASE, geometry.heightPx)
        assertEquals(NAV, geometry.paddingBottomPx)
        assertEquals(BASE, geometry.peekHeightPx)
        assertEquals(0, geometry.heldLiftPx)
    }

    /** U2 — keyboard visible: reproduces the popup's existing IME lift exactly. */
    @Test
    fun `U2 keyboard lifts the sheet and consumes the same pixels as padding`() {
        val geometry = PopupSheetGeometry.compute(input(imeHeight = KB, imeVisible = true))

        assertEquals(BASE + KB, geometry.heightPx)
        assertEquals(KB, geometry.paddingBottomPx)
        assertEquals(BASE + KB, geometry.peekHeightPx)
        assertEquals(KB, geometry.heldLiftPx)
    }

    /** U3 — never-shrink: the keyboard going away holds the lift instead of collapsing to base. */
    @Test
    fun `U3 keyboard hidden holds the previous lift`() {
        val geometry = PopupSheetGeometry.compute(input(held = KB))

        assertEquals(BASE + KB, geometry.heightPx)
        assertEquals(NAV, geometry.paddingBottomPx)
    }

    /**
     * U4 — Bug ①, Invariant P. The panel lifts the sheet and consumes NO padding (the padding stays
     * at the navigation bar), so the space it needs comes from the sheet growing rather than from
     * the message list. If someone makes the panel contribute to padding "for symmetry with the
     * IME", this row fails immediately.
     *
     * The height is derived, not observed: the panel is content stacked above the navigation-bar
     * padding, so the sheet must grow by the panel height AND the bar it sits on for the list box to
     * land on [BASE] — the same list box the keyboard state produces in U2.
     */
    @Test
    fun `U4 panel lifts the sheet by its height plus the navigation bar without consuming padding`() {
        val geometry = PopupSheetGeometry.compute(input(panelHeight = PANEL, panelVisible = true))

        assertEquals(BASE + PANEL_LIFT, geometry.heightPx)
        assertEquals(NAV, geometry.paddingBottomPx)

        val listBox = geometry.heightPx - geometry.paddingBottomPx - PANEL
        assertEquals("the list box must match the keyboard state's", BASE, listBox)
    }

    /**
     * U5 — handoff transient: both sources active. `max(ime, panel + nav)` is the IME value, so the
     * height does not move, and padding hands the slot to the panel.
     */
    @Test
    fun `U5 keyboard and panel both active take the maximum and give padding to the panel`() {
        val geometry = PopupSheetGeometry.compute(
            input(imeHeight = KB, imeVisible = true, panelHeight = PANEL, panelVisible = true)
        )

        assertEquals(BASE + KB, geometry.heightPx)
        assertEquals(NAV, geometry.paddingBottomPx)
        assertEquals(BASE, geometry.heightPx - geometry.paddingBottomPx - PANEL)
    }

    /**
     * U6 — the freeze analog: the keyboard-to-panel handoff must not move the sheet. The height is
     * identical to the keyboard-only state, so the user sees no jump while the keyboard closes
     * behind the panel.
     */
    @Test
    fun `U6 keyboard to panel handoff does not change the sheet height`() {
        val keyboardOnly = PopupSheetGeometry.compute(input(imeHeight = KB, imeVisible = true))
        val handoff = PopupSheetGeometry.compute(
            input(imeHeight = KB, imeVisible = true, panelHeight = PANEL, panelVisible = true)
        )

        assertEquals(keyboardOnly.heightPx, handoff.heightPx)
    }

    /**
     * U7' — the instant `freezeKeyboardPadding()` runs, before the panel notification arrives. The
     * computed geometry is identical to the pre-freeze state, so the controller's write guard
     * suppresses the write entirely and no intermediate value is ever laid out.
     */
    @Test
    fun `U7 freeze before the panel notification computes the unchanged keyboard state`() {
        val geometry = PopupSheetGeometry.compute(input(imeHeight = KB, imeVisible = true))

        assertEquals(KB, geometry.paddingBottomPx)
        assertEquals(BASE + KB, geometry.heightPx)
    }

    /**
     * U8 — closing the panel does not restore base height (no dip while the panel collapses). The
     * held value is the one the panel state echoed, i.e. [PANEL_LIFT], not the bare panel height.
     */
    @Test
    fun `U8 panel close holds the lift instead of restoring base height`() {
        val geometry = PopupSheetGeometry.compute(input(held = PANEL_LIFT))

        assertEquals(BASE + PANEL_LIFT, geometry.heightPx)
        assertEquals(NAV, geometry.paddingBottomPx)
    }

    /** U9 — a shorter second keyboard shrinks the sheet, exactly as the popup does today. */
    @Test
    fun `U9 a shorter keyboard shrinks the sheet as before`() {
        val geometry = PopupSheetGeometry.compute(input(imeHeight = 700, imeVisible = true, held = KB))

        assertEquals(BASE + 700, geometry.heightPx)
    }

    /** U10 — the one shrink the model permits: a fallback-sized panel replaced by a shorter IME. */
    @Test
    fun `U10 a shorter keyboard replacing a taller panel shrinks once`() {
        val geometry = PopupSheetGeometry.compute(
            input(imeHeight = 700, imeVisible = true, held = PANEL_LIFT)
        )

        assertEquals(BASE + 700, geometry.heightPx)
    }

    /** U11 — the clamp, and heldLift echoed from the CLAMPED height. */
    @Test
    fun `U11 height is clamped and heldLift follows the clamped height`() {
        val geometry = PopupSheetGeometry.compute(
            input(imeHeight = 1200, imeVisible = true, max = 2200)
        )

        assertEquals(2200, geometry.heightPx)
        assertEquals(1000, geometry.heldLiftPx)
    }

    /** U12 — idempotence: feeding heldLift back must not inflate the sheet pass after pass. */
    @Test
    fun `U12 feeding heldLift back reproduces the same geometry`() {
        val first = PopupSheetGeometry.compute(input(imeHeight = 1200, imeVisible = true, max = 2200))
        val second = PopupSheetGeometry.compute(
            input(imeHeight = 1200, imeVisible = true, held = first.heldLiftPx, max = 2200)
        )

        assertEquals(first, second)
    }

    /**
     * U13 — contract violation (panel reports a non-positive height) degrades, never throws. In
     * particular the navigation bar is NOT added on its own: an absent panel must lift nothing, not
     * one bar's worth of empty sheet.
     */
    @Test
    fun `U13 a panel with no height applies no lift`() {
        val geometry = PopupSheetGeometry.compute(input(panelHeight = 0, panelVisible = true))

        assertEquals(BASE, geometry.heightPx)
        assertEquals(NAV, geometry.paddingBottomPx)
    }

    /**
     * U14 — the anti-leak property. This is the state the full-screen group `@`-insert path leaves
     * behind: the panel was hidden without any `releaseKeyboardPaddingFreeze()` call. A boolean
     * freeze latch would still be set here and would pin the padding at navBar forever, putting the
     * input row behind the keyboard. Deriving padding from panelVisible cannot get stuck.
     */
    @Test
    fun `U14 padding follows the keyboard even when the panel closed without releasing`() {
        val geometry = PopupSheetGeometry.compute(
            input(imeHeight = KB, imeVisible = true, panelVisible = false, held = PANEL)
        )

        assertEquals(KB, geometry.paddingBottomPx)
        assertEquals(BASE + KB, geometry.heightPx)
    }

    /**
     * U15 — the no-jump property, pinned over the WHOLE keyboard->panel sequence rather than at its
     * two ends. This is the QA-reported defect: with the panel lift compared against the raw IME
     * inset, step 3 came out one navigation bar shorter than step 1 and the sheet's top edge dropped
     * on every switch.
     *
     * Derivation, not observation: `PANEL + NAV == KB`, so the required lift is `KB` in all three
     * steps and the outer height is `BASE + KB` throughout — including step 2, the transitional
     * frame where both sources are momentarily visible, which is why no intermediate value exists to
     * flicker through either. The list box (`height - padding - panel`) stays at [BASE] as well, so
     * the message list does not resize under the swap.
     */
    @Test
    fun `U15 keyboard to panel keeps the sheet height and the list box constant at every step`() {
        val keyboard = PopupSheetGeometry.compute(input(imeHeight = KB, imeVisible = true))
        val both = PopupSheetGeometry.compute(
            input(
                imeHeight = KB,
                imeVisible = true,
                panelHeight = PANEL,
                panelVisible = true,
                held = keyboard.heldLiftPx,
            )
        )
        val panelOnly = PopupSheetGeometry.compute(
            input(panelHeight = PANEL, panelVisible = true, held = both.heldLiftPx)
        )

        assertEquals(BASE + KB, keyboard.heightPx)
        assertEquals("no jump while both are momentarily visible", BASE + KB, both.heightPx)
        assertEquals("no jump once the keyboard is gone", BASE + KB, panelOnly.heightPx)

        assertEquals(BASE, keyboard.heightPx - keyboard.paddingBottomPx)
        assertEquals(BASE, both.heightPx - both.paddingBottomPx - PANEL)
        assertEquals(BASE, panelOnly.heightPx - panelOnly.paddingBottomPx - PANEL)
    }

    /**
     * U16 — the fallback-sized panel (no keyboard has been measured yet, so the fragment uses its
     * fixed 280dp). It is not derived from any IME inset, so nothing can be assumed about how it
     * compares to one; the invariant that still has to hold is the list box landing on [BASE], which
     * requires the same `panel + nav` lift.
     */
    @Test
    fun `U16 a fallback-sized panel lifts by its height plus the navigation bar`() {
        val fallback = 700

        val geometry = PopupSheetGeometry.compute(
            input(panelHeight = fallback, panelVisible = true)
        )

        assertEquals(BASE + fallback + NAV, geometry.heightPx)
        assertEquals(NAV, geometry.paddingBottomPx)
        assertEquals(BASE, geometry.heightPx - geometry.paddingBottomPx - fallback)
    }
}
