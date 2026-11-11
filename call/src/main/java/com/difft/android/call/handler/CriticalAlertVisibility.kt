package com.difft.android.call.handler

import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.call.data.CallStatus






object CriticalAlertVisibility {

    fun for1v1(type: String?, role: CallRole, status: CallStatus): Boolean =
        type == CallType.ONE_ON_ONE.type && role == CallRole.CALLER && status == CallStatus.CALLING

    fun forGroup(type: String?, isCriticalAlertEnable: Boolean): Boolean =
        type == CallType.GROUP.type && isCriticalAlertEnable

    fun forInstant(type: String?, awaitingJoinInvitees: List<String>): Boolean =
        type == CallType.INSTANT.type && awaitingJoinInvitees.isNotEmpty()
}
