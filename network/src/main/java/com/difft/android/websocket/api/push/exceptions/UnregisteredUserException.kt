package com.difft.android.websocket.api.push.exceptions

import java.io.IOException

class UnregisteredUserException(
    val e164Number: String?,
    exception: Exception?
) : IOException(exception)
