package com.difft.android.network.requests

data class CameraControlRequest(
    val channelName: String,
    val account: String,
    val camera: String // 使用内部枚举类型
) {
    enum class CameraStatus(val value: String) {
        ON("on"),
        OFF("off");

        override fun toString(): String {
            return value
        }
    }
}