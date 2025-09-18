package com.difft.android.network.responses

data class AppVersionResponse(
    val versionName: String,
    val versionCode: Int,
    val url: String,
    val hash: String
)