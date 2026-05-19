package com.difft.android.websocket.internal.push.exceptions

import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.internal.push.GroupStaleDevices

/**
 * Represents a 410 response from the service during a sender key send.
 */
class GroupStaleDevicesException(
    staleDevices: Array<GroupStaleDevices>
) : NonSuccessfulResponseCodeException(410) {
    val staleDevices: List<GroupStaleDevices> = staleDevices.toList()
}
