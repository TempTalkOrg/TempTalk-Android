package com.difft.android.websocket.internal

import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.api.push.exceptions.PushNetworkException
import com.difft.android.websocket.api.util.Preconditions
import com.difft.android.websocket.internal.websocket.WebsocketResponse
import java.util.Optional
import java.util.concurrent.ExecutionException

/**
 * Encapsulates a parsed API response regardless of where it came from (WebSocket or REST). Not only
 * includes the success result but also any application errors encountered (404s, parsing, etc.) or
 * execution errors encountered (IOException, etc.).
 */
class ServiceResponse<Result>(
    val status: Int,
    body: String?,
    result: Result?,
    applicationError: Throwable?,
    executionError: Throwable?
) {

    val body: Optional<String> = Optional.ofNullable(body)
    @Suppress("UNCHECKED_CAST")
    val result: Optional<Result> = Optional.ofNullable(result) as Optional<Result>
    val applicationError: Optional<Throwable> = Optional.ofNullable(applicationError)
    val executionError: Optional<Throwable> = Optional.ofNullable(executionError)

    init {
        if (result != null) {
            Preconditions.checkArgument(applicationError == null && executionError == null)
        } else {
            Preconditions.checkArgument(applicationError != null || executionError != null)
        }
    }

    companion object {
        @JvmStatic
        fun <T> forResult(result: T, response: WebsocketResponse): ServiceResponse<T> =
            ServiceResponse(response.status, response.body, result, null, null)

        @JvmStatic
        fun <T> forResult(result: T, status: Int, body: String?): ServiceResponse<T> =
            ServiceResponse(status, body, result, null, null)

        @JvmStatic
        fun <T> forApplicationError(throwable: Throwable, response: WebsocketResponse): ServiceResponse<T> =
            ServiceResponse(response.status, response.body, null, throwable, null)

        @JvmStatic
        fun <T> forApplicationError(throwable: Throwable, status: Int, body: String?): ServiceResponse<T> =
            ServiceResponse(status, body, null, throwable, null)

        @JvmStatic
        fun <T> forExecutionError(throwable: Throwable?): ServiceResponse<T> =
            ServiceResponse(0, null, null, null, throwable)

        @JvmStatic
        fun <T> forUnknownError(throwable: Throwable?): ServiceResponse<T> {
            return if (throwable is ExecutionException) {
                forUnknownError(throwable.cause)
            } else if (throwable is NonSuccessfulResponseCodeException) {
                forApplicationError(throwable, throwable.code, null)
            } else if (throwable is PushNetworkException && throwable.cause != null) {
                forUnknownError(throwable.cause)
            } else {
                forExecutionError(throwable)
            }
        }

        @JvmStatic
        fun <T, I> coerceError(response: ServiceResponse<I>): ServiceResponse<T> {
            return if (response.applicationError.isPresent) {
                forApplicationError(response.applicationError.get(), response.status, response.body.orElse(null))
            } else {
                forExecutionError(response.executionError.orElse(null))
            }
        }
    }
}
