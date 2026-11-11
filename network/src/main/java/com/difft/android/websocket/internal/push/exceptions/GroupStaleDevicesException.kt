package com.difft.android.websocket.internal.push.exceptions

import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.internal.push.GroupStaleDevices




class GroupStaleDevicesException(
    staleDevices: Array<GroupStaleDevices>
) : NonSuccessfulResponseCodeException(410) {
    val staleDevices: List<GroupStaleDevices> = staleDevices.toList()
}
