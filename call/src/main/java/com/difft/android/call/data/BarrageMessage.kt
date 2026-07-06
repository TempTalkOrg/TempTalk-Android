package com.difft.android.call.data

import androidx.compose.runtime.Stable
import java.util.concurrent.atomic.AtomicLong

data class BarrageMessage(
    val userName: String,
    val message: String,
    val timestamp: Long,
    val id: Long = barrageIdGenerator.incrementAndGet()
)

private val barrageIdGenerator = AtomicLong(0)

@Stable
data class BarrageMessageConfig(
    val isOneVOneCall: Boolean,
    val barrageTexts: List<String>,
    val displayDurationMillis: Long = 6000L,
    val showLimitCount: Int = 6,
    val baseSpeed: Long,
    val deltaSpeed: Long,
    val columns: List<Int>,
    val emojiPresets: List<String>,
    val textPresets: List<String>,
    val textMaxLength: Int
)

data class EmojiBubbleMessage(
    val emoji: String,
    val userName: String,
    val startOffsetPercent: Int, // 从屏幕左侧的偏移百分比
    val durationMillis: Long, // 展示持续时间
    val id: Long = System.currentTimeMillis() // 唯一标识
)

data class TextBubbleMessage(
    val emoji: String?,
    val text: String,
    val userName: String,
    val startOffsetPercent: Int, // 从屏幕左侧的偏移百分比
    val durationMillis: Long, // 展示持续时间
    val id: Long = System.currentTimeMillis() // 唯一标识
)