package com.difft.android.network.requests

data class BindPushTokenRequestBody(
    val tpnID: String?,// 现在必须非空
    val fcmID: String?// 安装了Google 服务的要上传
)