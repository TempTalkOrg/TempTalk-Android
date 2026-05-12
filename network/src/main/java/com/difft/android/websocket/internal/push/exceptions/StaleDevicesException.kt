package com.difft.android.websocket.internal.push.exceptions

import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.internal.push.StaleDevices

class StaleDevicesException(
    val staleDevices: StaleDevices
) : NonSuccessfulResponseCodeException(410)
