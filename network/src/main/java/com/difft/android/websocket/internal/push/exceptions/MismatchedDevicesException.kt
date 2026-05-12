package com.difft.android.websocket.internal.push.exceptions

import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.internal.push.MismatchedDevices

class MismatchedDevicesException(
    val mismatchedDevices: MismatchedDevices
) : NonSuccessfulResponseCodeException(409)
