package com.difft.android.websocket.internal.push.exceptions

import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException

class AccountOfflineException(
    val status: Int,
    val reason: String
) : NonSuccessfulResponseCodeException(404)
