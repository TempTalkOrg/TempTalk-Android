package com.difft.android.websocket.api.push.exceptions

import java.util.Optional

class RateLimitException @JvmOverloads constructor(
    status: Int,
    message: String?,
    val retryAfterMilliseconds: Optional<Long> = Optional.empty()
) : NonSuccessfulResponseCodeException(status, message) {

    override fun toString(): String =
        "RateLimitException{retryAfterMilliseconds=$retryAfterMilliseconds}"
}
