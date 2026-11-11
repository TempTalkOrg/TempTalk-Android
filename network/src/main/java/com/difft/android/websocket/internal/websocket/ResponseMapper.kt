package com.difft.android.websocket.internal.websocket

import com.difft.android.websocket.internal.ServiceResponse
import java.util.function.Function












fun interface ResponseMapper<T> {
    fun map(status: Int, body: String, getHeader: Function<String, String>): ServiceResponse<T>

    fun map(response: WebsocketResponse): ServiceResponse<T> =
        map(response.status, response.body, Function { key -> response.getHeader(key) ?: "" })
}
