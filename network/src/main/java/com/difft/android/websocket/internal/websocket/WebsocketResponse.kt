package com.difft.android.websocket.internal.websocket

import com.difft.android.websocket.api.util.Preconditions
import java.util.Locale


class WebsocketResponse internal constructor(
  @JvmField val status: Int,
  @JvmField val body: String,
  headers: List<String>,
) {
    private val headers: Map<String, String> = parseHeaders(headers)

    fun getHeader(key: String): String? {
        return headers[Preconditions.checkNotNull(
            key.lowercase(
                Locale.getDefault()
            )
        )]
    }

    companion object {
        private fun parseHeaders(rawHeaders: List<String>): Map<String, String> {
            val headers: MutableMap<String, String> = HashMap(rawHeaders.size)

            for (raw in rawHeaders) {
                if (raw.isNotEmpty()) {
                    val colonIndex = raw.indexOf(":")

                    if (colonIndex > 0 && colonIndex < raw.length - 1) {
                        val key = raw.substring(0, colonIndex).trim { it <= ' ' }
                            .lowercase(Locale.getDefault())
                        val value = raw.substring(colonIndex + 1).trim { it <= ' ' }

                        headers[key] = value
                    }
                }
            }

            return headers
        }
    }
}
