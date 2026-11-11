package com.difft.android.call.network

import io.livekit.android.room.participant.ConnectionQuality


















fun ConnectionQuality.toNetworkQualityLevel(): NetworkQualityLevel = when (this) {
    ConnectionQuality.EXCELLENT, ConnectionQuality.UNKNOWN -> NetworkQualityLevel.EXCELLENT
    ConnectionQuality.GOOD -> NetworkQualityLevel.GOOD
    ConnectionQuality.POOR, ConnectionQuality.LOST -> NetworkQualityLevel.BAD
}
