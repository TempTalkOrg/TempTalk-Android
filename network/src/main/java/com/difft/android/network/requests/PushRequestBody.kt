package com.difft.android.network.requests

data class BindPushTokenRequestBody(
    val fcmID: String?// 安装了Google 服务的要上传
)