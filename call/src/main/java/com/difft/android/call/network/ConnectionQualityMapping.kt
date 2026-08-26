package com.difft.android.call.network

import io.livekit.android.room.participant.ConnectionQuality

/**
 * The only place the LiveKit [ConnectionQuality] enum is translated into a [NetworkQualityLevel].
 *
 * Mapping rules per the cross-platform spec:
 *  - `UNKNOWN -> EXCELLENT` on purpose. The SDK reports UNKNOWN for every participant before the
 *    first stats batch lands; treating it as a fault would flash a badge on every join. It is
 *    mapped for the "render nothing" behaviour, not because "unknown" means "best".
 *  - `POOR` and `LOST` collapse into one [NetworkQualityLevel.BAD] tier (single colour, no
 *    red/yellow split).
 *
 * This mapping is deliberately NOT the same classification the post-call rating trigger uses
 * (`RoomEventDispatcher.goodQualities` treats UNKNOWN as poor). The two must stay independent:
 * aligning them would silently change how often the rating sheet appears.
 *
 * No `else` branch on purpose: a future SDK constant stops this compiling instead of being
 * silently classified as excellent.
 */
fun ConnectionQuality.toNetworkQualityLevel(): NetworkQualityLevel = when (this) {
    ConnectionQuality.EXCELLENT, ConnectionQuality.UNKNOWN -> NetworkQualityLevel.EXCELLENT
    ConnectionQuality.GOOD -> NetworkQualityLevel.GOOD
    ConnectionQuality.POOR, ConnectionQuality.LOST -> NetworkQualityLevel.BAD
}
