package com.difft.android.websocket.internal.push

import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException

class DeviceLimitExceededException(
    val deviceLimit: DeviceLimit
) : NonSuccessfulResponseCodeException(411) {

    val current: Int get() = deviceLimit.current

    val max: Int get() = deviceLimit.max
}
