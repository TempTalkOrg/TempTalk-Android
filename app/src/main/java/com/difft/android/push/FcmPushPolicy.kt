package com.difft.android.push

import com.difft.android.base.call.CallData

private const val SERVER_PUSH_UID = "server"





internal fun isServerNotifyPush(uid: String?, notifyType: Int): Boolean =
    uid == SERVER_PUSH_UID || notifyType < 0






internal fun matchNotifyingCallRoomIds(calls: Collection<CallData>, gid: String?, uid: String?): List<String> =
    calls.filter { call ->
        call.notifying && when {
            !gid.isNullOrEmpty() -> call.conversation == gid
            !uid.isNullOrEmpty() -> call.conversation == uid || call.caller.uid == uid
            else -> false
        }
    }.map { it.roomId }
