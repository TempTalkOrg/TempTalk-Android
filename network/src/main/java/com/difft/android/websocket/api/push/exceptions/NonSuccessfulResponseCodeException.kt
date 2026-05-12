package com.difft.android.websocket.api.push.exceptions

import java.io.IOException

/**
 * Indicates a server response that is not successful, typically something outside the 2xx range.
 */
open class NonSuccessfulResponseCodeException : IOException {

    val code: Int

    constructor(code: Int) : super("StatusCode: $code") {
        this.code = code
    }

    constructor(code: Int, s: String?) : super("[$code] $s") {
        this.code = code
    }

    val is5xx: Boolean get() = code in 500..599
}
