package com.difft.android.call.data

data class ServerNode(
    val name: String,
    val url: String,
    val flag: String,
    val ping: Int,
    val recommended: Boolean
)

enum class CONNECTION_TYPE {
    WEB_SOCKET,
    HTTP3_QUIC,
}
