


package com.difft.android.call.layout




const val LAYOUT_SPEC_VERSION = 28

data class LayoutConfig(
    val wideMinWidth: Double = 600.0,
    val wideMinHeight: Double = 480.0,
    
    
    
    val fillThreshold: Double = 0.52,
    
    val largeFillThreshold: Double = 0.45,
    
    val cardAspectMin: Double = 3.0 / 4,
    val cardAspectMax: Double = 16.0 / 9,
    
    
    
    val minCardCompact: Double = 125.0,
    val minCardWide: Double = 140.0,
    
    
    val minCardHalf: Double = 120.0,
    val gap: Double = 8.0,
    
    val adaptiveCards: Boolean = true,
    
    
    val adaptiveMaxCount: Int = 12,
    
    
    
    val cardMinWidth: Double = 100.0,
    
    
    
    
    
    
    val widenBarsMax: Double = 0.3,
    val cropMax: Double = 0.25,
    val cardAreaRatioMax: Double = 1.7,
    
    
    val regroupGain: Double = 0.1,
    
    val adaptiveAspectMin: Double = 9.0 / 16,
    val adaptiveAspectMax: Double = 16.0 / 9,
    
    
    val scrollAspectMax: Double = 4.0 / 3,
    
    val scrollStretch: Double = 1.3,
    
    
    val moreMaxTiles: Int = 15,
    
    
    
    
    val firstScreenMin: Int = 8,
    
    val minCardFloor: Double = 90.0,
    
    
    val pageMax: Int = 9,
    
    val aspectSettleMs: Long = 500,
    
    val pipLong: Double = 214.0,
    val pipShort: Double = 120.0,
    val pipMargin: Double = 12.0,
    
    val pipMinScale: Double = 0.75,
    val promoteSpeakingMs: Long = 1500,
    
    val speakingHangoverMs: Long = 600,
    
    
    
    val speakerSwitchMs: Long = 200,
    val speakerSwitchMaxMs: Long = 600,
    val speakerHoldMs: Long = 2500,
    val promoteVideoMs: Long = 300,
    val holdMs: Long = 4000,
    val recentSpeakMs: Long = 10000,
    
    
    val tickMs: Long = 100,
    val schedulerStepMs: Long = 300,
    val pageTurnHoldMs: Long = 1200,
    
    val videoFadeMs: Long = 200,
    val firstFrameHintMs: Long = 1000,
    val layoutMoveMs: Long = 280,
    val shareEnterMs: Long = 300,
) {
    companion object {
        val DEFAULT = LayoutConfig()
    }
}
