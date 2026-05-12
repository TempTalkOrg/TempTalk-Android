package com.difft.android.websocket.internal.push

import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException

class LockedException(
    val length: Int,
    val timeRemaining: Long,
    val basicStorageCredentials: String?
) : NonSuccessfulResponseCodeException(423)
