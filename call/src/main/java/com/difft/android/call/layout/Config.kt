// Synced from call-layout bf6b84ecbe, ports/kotlin/src/main/kotlin/org/difft/calllayout/Config.kt (spec v25), by scripts/sync-apps.ts.
// Do not edit here: change call-layout and sync again.

package com.difft.android.call.layout

// Every tunable number of the layout lives here, ported from src/core/config.ts. The defaults are
// DEFAULT_CONFIG; golden vectors are generated with it. Times are milliseconds.

const val LAYOUT_SPEC_VERSION = 25

data class LayoutConfig(
    val wideMinWidth: Double = 600.0,
    val wideMinHeight: Double = 480.0,
    // Grid cards fill when mismatch <= threshold, otherwise fit. 0.52 sits in the gap between the
    // near-square cards (mismatch 0.40 to 0.50 with 16:9 or 9:16 video) and the clearly mismatched
    // ones (0.53 and up), so the same scene fills on every phone width.
    val fillThreshold: Double = 0.52,
    // The same for large views (the 1on1 floating window, solo); a 1on1 main video is always shown whole.
    val largeFillThreshold: Double = 0.45,
    // Card aspect (w / h) is clamped into [min, max].
    val cardAspectMin: Double = 3.0 / 4,
    val cardAspectMax: Double = 16.0 / 9,
    // Below this short side cards stop shrinking and the rest go to the next page (Desktop has no
    // minimum: overflow 'more', decision 19); adaptive rows must be at least this tall.
    // 125: two columns on 360, 393 and 411 wide phones alike, eight people on the first screen.
    val minCardCompact: Double = 125.0,
    val minCardWide: Double = 140.0,
    // The same while the device is half open, whatever the window class: the part beside the fold
    // is small, so cards shrink a little further before the rest go to the next page.
    val minCardHalf: Double = 120.0,
    val gap: Double = 8.0,
    // Cards take the shape of their video and are packed in rows of equal height.
    val adaptiveCards: Boolean = true,
    // Above this many people rows on one screen are not tried and phones and folds use the paged rows
    // (decisions 21, 24); Desktop raises it to moreMaxTiles (decision 19).
    val adaptiveMaxCount: Int = 12,
    // Phones and folds (decision 36): no card in rows is narrower than this, so a name fits (the pill
    // holds four Chinese characters or about nine letters); a portrait video in a card widened to it
    // shows whole, with bars at the sides. Desktop has none.
    val cardMinWidth: Double = 100.0,
    // Phones and folds (decision 37): a row that does not span the box widens its cards, the video
    // whole in each, until bars would take this share of a card (a portrait video widens to 4:5).
    val widenBarsMax: Double = 0.3,
    // Video card aspect is clamped into [min, max].
    val adaptiveAspectMin: Double = 9.0 / 16,
    val adaptiveAspectMax: Double = 16.0 / 9,
    // Phones and folds (decisions 31, 35): adaptive rows may differ in height up to this many times. Two:
    // rows twice as tall as others are as far as cards may differ on one screen.
    val rowsHeightRatioMax: Double = 2.0,
    // Scrolling rows (decision 21), whose first screen becomes the page, cap landscape cards at this
    // aspect, cropping a 16:9 video by at most 25%.
    val scrollAspectMax: Double = 4.0 / 3,
    // ...and let a row grow to this many times the first screen's row height to span the width.
    val scrollStretch: Double = 1.3,
    // Overflow.MORE (Desktop, decisions 19 and 27): at most this many cells, the last one "+N" when more
    // people are in. Fifteen, the "+N" cell included, as Desktop does today.
    val moreMaxTiles: Int = 15,
    // Phones and folds (Overflow.PAGES): the first page shows at least this many people, or everyone
    // if fewer; to get there cards shrink below minCardCompact / minCardWide / minCardHalf (decision 22)...
    // Eight, as two columns of four on a phone (decision 12); 90 lets a fold held level and half open
    // (vivo X Fold6, 738×240 above the fold) show two rows of four.
    val firstScreenMin: Int = 8,
    // ...but no row, and no uniform card's short side, below this.
    val minCardFloor: Double = 90.0,
    // Phones and folds: at most this many people on a page, you included (decision 26). Nine: three
    // by three on a fold's inner display; a phone fits two columns of four anyway.
    val pageMax: Int = 9,
    // A changed video aspect must hold this long before cards reshape.
    val aspectSettleMs: Long = 500,
    // The floating window keeps the area pipLong × pipShort and takes its video's shape (decision 18).
    val pipLong: Double = 214.0,
    val pipShort: Double = 120.0,
    val pipMargin: Double = 12.0,
    // The floating window may shrink to this share of its size to stay clear of a whole main video.
    val pipMinScale: Double = 0.75,
    val promoteSpeakingMs: Long = 1500,
    val promoteVideoMs: Long = 300,
    val holdMs: Long = 4000,
    val recentSpeakMs: Long = 10000,
    // The controller (decision 32): its timer fires every tickMs, it steps the scheduler every
    // schedulerStepMs, and after a page turn it holds the scheduler for pageTurnHoldMs.
    val tickMs: Long = 100,
    val schedulerStepMs: Long = 300,
    val pageTurnHoldMs: Long = 1200,
    // Motion values are consumed by the UI layers, kept here so all ends agree.
    val videoFadeMs: Long = 200,
    val firstFrameHintMs: Long = 1000,
    val layoutMoveMs: Long = 280,
    val shareEnterMs: Long = 300,
) {
    companion object {
        val DEFAULT = LayoutConfig()
    }
}
