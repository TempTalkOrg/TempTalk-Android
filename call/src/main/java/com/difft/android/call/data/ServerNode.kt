package com.difft.android.call.data

data class ServerNode(
    val name: String,
    val url: String,
    val flag: String,
    val region: String,
    val domain: String,
    val addrs: List<String>,
    val isPrimary: Boolean,
)

enum class CONNECTION_TYPE {
    WEB_SOCKET,
    HTTP3_QUIC,
}
