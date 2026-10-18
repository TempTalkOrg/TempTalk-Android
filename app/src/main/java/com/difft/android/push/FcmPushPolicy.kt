package com.difft.android.push

import com.difft.android.base.call.CallData

private const val SERVER_PUSH_UID = "server"

/**
 * True for a server notify push (uid "server" or a negative notifyType) that carries no
 * envelope: the generic "You received a message." fallback must not be shown for it (#1347).
 */
internal fun isServerNotifyPush(uid: String?, notifyType: Int): Boolean =
    uid == SERVER_PUSH_UID || notifyType < 0

/**
 * Room ids of the notifying calls an FCM call-invite push may be a duplicate of (#1347). The push
 * carries no roomId, so match by conversation: `gid` when set, otherwise `uid` as the conversation
 * (1v1) or as the caller. Instant calls have no conversation, so only the caller can match them.
 */
internal fun matchNotifyingCallRoomIds(calls: Collection<CallData>, gid: String?, uid: String?): List<String> =
    calls.filter { call ->
        call.notifying && when {
            !gid.isNullOrEmpty() -> call.conversation == gid
            !uid.isNullOrEmpty() -> call.conversation == uid || call.caller.uid == uid
            else -> false
        }
    }.map { it.roomId }
