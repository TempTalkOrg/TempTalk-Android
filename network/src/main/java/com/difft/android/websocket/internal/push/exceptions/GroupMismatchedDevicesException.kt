package com.difft.android.websocket.internal.push.exceptions

import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.internal.push.GroupMismatchedDevices




class GroupMismatchedDevicesException(
    mismatchedDevices: Array<GroupMismatchedDevices>
) : NonSuccessfulResponseCodeException(409) {
    val mismatchedDevices: List<GroupMismatchedDevices> = mismatchedDevices.toList()
}
