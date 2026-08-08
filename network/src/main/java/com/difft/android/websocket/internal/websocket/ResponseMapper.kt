package com.difft.android.websocket.internal.websocket

import com.difft.android.websocket.internal.ServiceResponse
import java.util.function.Function

/**
 * Responsible for taking an API response and converting it to a [ServiceResponse]. This includes
 * parsing for a success as well as any application errors. All errors (application or parsing
 * related) are encapsulated in an error version of a [ServiceResponse], hence why no method throws
 * an exception.
 *
 * Unless you need to do something really special, you should only be extending this to be provided
 * to [DefaultResponseMapper].
 *
 * @param T The final type the API response will map into.
 */
fun interface ResponseMapper<T> {
    fun map(status: Int, body: String, getHeader: Function<String, String>): ServiceResponse<T>

    fun map(response: WebsocketResponse): ServiceResponse<T> =
        map(response.status, response.body, Function { key -> response.getHeader(key) ?: "" })
}
