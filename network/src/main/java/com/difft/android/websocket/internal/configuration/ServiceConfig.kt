package com.difft.android.websocket.internal.configuration

import okhttp3.ConnectionSpec

/**
 * Simplified service configuration that replaces the complex SignalServiceConfiguration.
 * Only contains the fields that are actually used.
 */
data class ServiceConfig(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val connectionSpec: ConnectionSpec? = null
)
