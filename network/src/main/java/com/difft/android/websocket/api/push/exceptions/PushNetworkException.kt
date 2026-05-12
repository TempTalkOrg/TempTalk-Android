package com.difft.android.websocket.api.push.exceptions

import java.io.IOException

class PushNetworkException : IOException {

    constructor(exception: Exception?) : super(exception)

    constructor(s: String?) : super(s)
}
