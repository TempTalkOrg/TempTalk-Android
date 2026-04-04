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
    val showLimitCount: Int = 6,
    val baseSpeed: Long, // 气泡消息展示 duration 基础时间（毫秒）
    val deltaSpeed: Long, // 气泡消息展示 duration 偏移时间（毫秒）
    val columns: List<Int>, // 气泡消息从底部出现的位置偏移（屏幕左侧百分比）
    val emojiPresets: List<String>, // 气泡消息预设文本
    val textPresets: List<String>, // 气泡消息预设文本
    val textMaxLength: Int // 可以输入文本弹幕消息的最大长度
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

enum class BubbleAnimationState {
    Start,
    End
}