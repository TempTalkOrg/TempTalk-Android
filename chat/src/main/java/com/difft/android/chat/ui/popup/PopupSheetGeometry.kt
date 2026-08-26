package com.difft.android.chat.ui.popup

/**
 * Immutable inputs to the popup chat bottom-sheet geometry. All values are px.
 *
 * @param baseHeightPx resting sheet height (50% of the window; 0 before the sheet is configured).
 * @param navigationBarHeightPx navigation-bar inset bottom.
 * @param imeHeightPx raw IME inset bottom; meaningful only when [imeVisible].
 * @param panelHeightPx the chat action panel's intended final height; meaningful only when
 *        [panelVisible]. Reported by ChatMessageInputFragment and never scaled down: it must never
 *        be smaller than the panel's rendered height, or the panel squeezes the message list. It is
 *        measured from the top of the navigation-bar padding, unlike [imeHeightPx] — see
 *        [PopupSheetGeometry.compute].
 * @param heldLiftPx the lift currently applied; held when no source is active so the sheet never
 *        collapses back to [baseHeightPx].
 * @param maxHeightPx upper bound for the sheet (window height minus the status bar); 0 = unbounded.
 */
data class SheetLiftInput(
    val baseHeightPx: Int,
    val navigationBarHeightPx: Int,
    val imeHeightPx: Int,
    val imeVisible: Boolean,
    val panelHeightPx: Int,
    val panelVisible: Boolean,
    val heldLiftPx: Int,
    val maxHeightPx: Int,
)

/** Geometry to apply to the bottom sheet. [heldLiftPx] is fed back into the next [SheetLiftInput]. */
data class SheetGeometry(
    val heightPx: Int,
    val paddingBottomPx: Int,
    val peekHeightPx: Int,
    val heldLiftPx: Int,
)

object PopupSheetGeometry {

    /**
     * Resolve the sheet's outer height and bottom padding from the active lift sources.
     *
     * The keyboard is a system window: it lifts the sheet AND consumes the same number of pixels as
     * bottom padding, so the content area is unchanged. The action panel is content: it lifts the
     * sheet and consumes NOTHING, so the content area grows by exactly the panel height and the
     * message list keeps its size. Making the panel contribute to padding "for symmetry" re-breaks
     * that: the content area would stay fixed and the panel would squeeze the list again.
     *
     * Bottom padding is derived from panel visibility rather than a freeze latch: a latch gets stuck
     * whenever a panel-close path forgets to release it (ChatMessageInputFragment hides the panel on
     * the group `@`-insert path without releasing), which would leave the input row behind the
     * keyboard.
     *
     * Coordinate convention — the two lift sources are measured from DIFFERENT origins and must be
     * converted before they can be compared. [imeHeightPx] is the raw IME inset, which spans the
     * navigation-bar region as well. [panelHeightPx] is the cached keyboard height (`ime - nav`, or
     * the fixed fallback when nothing is cached), and the panel renders as content sitting ABOVE the
     * navigation-bar padding. The panel's contribution to the lift is therefore
     * `panelHeightPx + navigationBarHeightPx`. Comparing the raw values instead lifted the panel
     * state one navigation bar LESS than the keyboard state, so the sheet's top edge dropped by that
     * amount on every keyboard<->panel switch and the panel read visually shorter than the keyboard.
     *
     * Transition math, with `panel == ime - nav` (the cached-keyboard case):
     * ```
     * keyboard: lift = ime,                    padding = ime, list = base + ime - ime           = base
     * both:     lift = max(ime, panel + nav)   padding = nav, list = base + ime - nav - panel    = base
     *                = ime
     * panel:    lift = panel + nav = ime,      padding = nav, list = base + ime - nav - panel    = base
     * ```
     * The outer height is `base + ime` in all three, so neither the sheet's top edge nor the list box
     * moves across the switch — including the transitional frame where both sources are visible, which
     * is why no intermediate jump is possible either. With the fallback-sized panel (no cached
     * keyboard) the same identity holds against that height instead: lift = fallback + nav, and the
     * list box is still exactly `base`.
     */
    fun compute(input: SheetLiftInput): SheetGeometry {
        // A panel reported with no height contributes NOTHING: adding the navigation bar on its own
        // would lift the sheet by a bar for a panel that is not there.
        val panelLift =
            if (input.panelVisible && input.panelHeightPx > 0) {
                input.panelHeightPx + input.navigationBarHeightPx
            } else {
                0
            }
        val required = maxOf(
            if (input.imeVisible) input.imeHeightPx else 0,
            panelLift,
        ).coerceAtLeast(0)
        // Never collapse to baseHeight when every source goes away: the sheet would shrink under the
        // user's finger during drag-to-dismiss. This generalises the keyboard-only "keep the height
        // to avoid a jump during drag" rule the popup already relied on to any lift source.
        val lift = (if (required > 0) required else input.heldLiftPx).coerceAtLeast(0)
        val height = clampHeight(input.baseHeightPx + lift, input.maxHeightPx)
        val padding =
            if (input.imeVisible && !input.panelVisible) input.imeHeightPx
            else input.navigationBarHeightPx
        return SheetGeometry(
            heightPx = height,
            paddingBottomPx = padding,
            peekHeightPx = height,
            // Echoed from the CLAMPED height so a clamped lift cannot re-inflate on the next pass.
            heldLiftPx = (height - input.baseHeightPx).coerceAtLeast(0),
        )
    }

    private fun clampHeight(height: Int, maxHeightPx: Int): Int =
        if (maxHeightPx > 0) height.coerceAtMost(maxHeightPx) else height
}
