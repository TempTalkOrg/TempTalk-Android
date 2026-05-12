package com.difft.android.websocket.internal.push.exceptions

import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.internal.push.GroupMismatchedDevices

/**
 * Represents a 409 response from the service during a sender key send.
 */
class GroupMismatchedDevicesException(
    mismatchedDevices: Array<GroupMismatchedDevices>
) : NonSuccessfulResponseCodeException(409) {
    val mismatchedDevices: List<GroupMismatchedDevices> = mismatchedDevices.toList()
}
