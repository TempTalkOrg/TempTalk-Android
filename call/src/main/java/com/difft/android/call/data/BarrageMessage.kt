package com.difft.android.call.data

data class BarrageMessage(
    val userName: String,
    val message: String,
    val timestamp: Long
)

data class BarrageMessageConfig(
    val isOneVOneCall: Boolean,
    val barrageTexts: List<String>,
    val displayDurationMillis: Long = 6000L,
    val showLimitCount: Int = 6
)