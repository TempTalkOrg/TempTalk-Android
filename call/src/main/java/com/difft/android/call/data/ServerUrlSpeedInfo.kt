package com.difft.android.call.data

data class ServerUrlSpeedInfo(
    val url: String,
    var lastResponseTime: Long,
    var lastTestTime: Long,
    var errorCount: Int = 0,
    var errorTime: Long,
    var status: SpeedResponseStatus
)

data class UrlSpeedResponse(
    val status: SpeedResponseStatus,
    val speed: Long,
)

enum class SpeedResponseStatus {
    SUCCESS,
    ERROR,
}


