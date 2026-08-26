package com.difft.android.call

object LCallUiConstants {

    const val SIMPLE_BARRAGE_UI_WIDTH = 272
    const val SIMPLE_BARRAGE_UI_WIDTH_SCREEN_SHARE = 360
    const val SIMPLE_BARRAGE_ITEM_HEIGHT = 36
    const val SIMPLE_BARRAGE_MAX_SINGLE_TEXT_LENGTH = 16
    const val SIMPLE_BARRAGE_INPUT_UI_HEIGHT = 36
    const val BARRAGE_MESSAGE_ITEM_HEIGHT = 36

    /**
     * Minimum tap-area height for the outer Box that wraps the bubble-barrage picker.
     * Before the first expansion the content is not composed (ANR fix); this value
     * keeps the Box non-zero so `tapInterceptor` still covers the picker zone.
     * After the first expansion the content stays in the tree permanently (hidden
     * via `graphicsLayer { alpha }`) and its natural height takes over.
     *
     * Layout (single-row FlowRow): 8 + 40 + 12 + 1 + 12 + 36 + 8 ≈ 117 dp.
     * 140 dp adds slack for line-height variance and minor padding shifts.
     */
    const val SIMPLE_BARRAGE_PICKER_MIN_HEIGHT = 140

    const val SCREEN_SHARE_FLOATING_VIEW_WIDTH = 120
    const val SCREEN_SHARE_FLOATING_VIEW_HEIGHT = 90
    val DEFAULT_BUBBLE_EMOJIS = listOf("👍", "👏", "🎉", "🙋", "❤️", "😂")

    val DEFAULT_BUBBLE_TEXTS = listOf("Agree ✅", "Disagree ⛔", "Bye 👋", "Can't hear 🙉", "Speed up 🐰", "Slow down 🐢")

    // ---- Call chrome geometry (dp magnitudes; convert with `.dp` at the use site) ----
    //
    // Owner principle: each constant is owned by the component that RENDERS it. Consumers
    // reserve space from the *_TOTAL_* values and never re-derive a bar's internals.

    /** Rendered height of the top status bar box. Owner: MainPageWithTopStatusView TopStatusBar. */
    const val TOP_BAR_HEIGHT_DP = 52

    /** Gap between the status-bar inset and the top bar. Owner: MainPageWithTopStatusView column. */
    const val TOP_BAR_MARGIN_TOP_DP = 0

    /** Gap below the top bar. Owner: MainPageWithTopStatusView column. */
    const val TOP_BAR_MARGIN_BOTTOM_DP = 4

    /**
     * Total vertical space the top bar occupies **below the status-bar inset**.
     * Callers add their own `topInset`; this constant never includes it.
     */
    const val TOP_BAR_TOTAL_HEIGHT_DP =
        TOP_BAR_MARGIN_TOP_DP + TOP_BAR_HEIGHT_DP + TOP_BAR_MARGIN_BOTTOM_DP  // 56

    /** Diameter of one bottom control button. Owner: MainPageWithBottomControlView. */
    const val BOTTOM_BAR_CONTROL_SIZE_DP = 48

    /** Portrait bottom margin under the control row. Owner: MainPageWithBottomControlView. */
    const val BOTTOM_BAR_MARGIN_BOTTOM_DP = 32

    /** Total vertical space the portrait bottom control bar occupies. */
    const val BOTTOM_BAR_TOTAL_HEIGHT_DP =
        BOTTOM_BAR_CONTROL_SIZE_DP + BOTTOM_BAR_MARGIN_BOTTOM_DP  // 80

    /** Barrage entry (smiley) icon size. Owner: ShouldShowBarrageInput. */
    const val BARRAGE_ENTRY_ICON_SIZE_DP = 20

    /** Barrage entry button padding, applied on all four sides. Owner: ShouldShowBarrageInput. */
    const val BARRAGE_ENTRY_PADDING_DP = 12

    /** Intrinsic height of the barrage entry button (icon + vertical padding). */
    const val BARRAGE_ENTRY_TOTAL_HEIGHT_DP =
        BARRAGE_ENTRY_ICON_SIZE_DP + BARRAGE_ENTRY_PADDING_DP * 2  // 44

    /** Breathing gap between full-screen content and the chrome bars / entry button. */
    const val CHROME_CONTENT_GAP_DP = 8
}
