package com.difft.android.websocket.internal.websocket

import com.difft.android.websocket.api.push.exceptions.MalformedResponseException
import java.util.function.Function

/**
 * Can map an API response to an appropriate [Throwable].
 *
 * Unless you need to do something really special, you should only be implementing this to customize
 * [DefaultErrorMapper].
 */
fun interface ErrorMapper {
    @Throws(MalformedResponseException::class)
    fun parseError(status: Int, body: String, getHeader: Function<String, String>): Throwable?

    @Throws(MalformedResponseException::class)
    fun parseError(status: Int): Throwable? = parseError(status, "", Function { "" })
}
