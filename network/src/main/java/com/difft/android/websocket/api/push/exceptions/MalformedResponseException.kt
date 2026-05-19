package com.difft.android.websocket.api.push.exceptions

import java.io.IOException

/**
 * Indicates that a response is malformed or otherwise in an unexpected format.
 */
class MalformedResponseException : IOException {

    constructor(message: String?) : super(message)

    constructor(message: String?, e: IOException?) : super(message, e)
}
