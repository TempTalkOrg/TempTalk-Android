package com.difft.android.network.speedtest

data class HostSpeedResult(
    val host: String,
    val latencyMs: Long,
    val isAvailable: Boolean
)
