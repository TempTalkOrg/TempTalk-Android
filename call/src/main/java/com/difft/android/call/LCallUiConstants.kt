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

}