package com.difft.android.websocket.api.push.exceptions

import java.io.IOException




class MalformedResponseException : IOException {

    constructor(message: String?) : super(message)

    constructor(message: String?, e: IOException?) : super(message, e)
}
