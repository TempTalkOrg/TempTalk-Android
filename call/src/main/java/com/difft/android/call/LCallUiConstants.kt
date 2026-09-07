package com.difft.android.call

object LCallUiConstants {

    const val BARRAGE_MESSAGE_ITEM_HEIGHT = 36

    const val SCREEN_SHARE_FLOATING_VIEW_WIDTH = 120
    const val SCREEN_SHARE_FLOATING_VIEW_HEIGHT = 90
    val DEFAULT_BUBBLE_EMOJIS = listOf("👍", "👏", "🎉", "🙋", "❤️", "😂")

    val DEFAULT_BUBBLE_TEXTS = listOf("Agree ✅", "Disagree ⛔", "Bye 👋", "Can't hear 🙉", "Speed up 🐰", "Slow down 🐢")

    // ---- Call chrome geometry (dp magnitudes; convert with `.dp` at the use site) ----
    //
    // Owner principle: each constant is owned by the component that RENDERS it. Consumers
    // reserve space from TOP_BAR_TOTAL_HEIGHT_DP / ActionBarPlan.chromeBottomReserveDp and
    // never re-derive a bar's internals.

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

    /**
     * Diameter of one bottom control button at full size. The rest of the action bar's
     * geometry (compact size, margins, two-row height, outside Emoji pill) is derived per
     * window in `ui/actionbar/CallActionBarPlan.kt`; consumers reserve space from
     * `ActionBarPlan.chromeBottomReserveDp`, never from a fixed total.
     */
    const val BOTTOM_BAR_CONTROL_SIZE_DP = 48

    /** Breathing gap between full-screen content and the chrome bars. */
    const val CHROME_CONTENT_GAP_DP = 8
}
