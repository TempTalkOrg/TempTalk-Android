package com.difft.android.call.session

import com.difft.android.base.call.CallType

/**
 * The single decision point for a call's meeting type, kept identical across Android, iOS and
 * Desktop so all participants of a call agree on what they are in.
 *
 * The server's `room.metadata.callType` is the primary input. Only two local corrections are
 * allowed on top of it, and both can only ever DOWNGRADE to [CallType.INSTANT]:
 *  - `1on1` holds only while the room has at most [ONE_ON_ONE_MAX_PARTICIPANTS] participants,
 *  - `group` holds only while the local user is still a member of the conversation's group.
 *
 * Those two corrections are not equally optional, because `callType` describes the ROOM and not the
 * viewer. For `1on1` and `instant` the room-level answer is also every participant's answer, so the
 * server value applies as-is. `group` is the one value where the two diverge: a group meeting that
 * invites someone from outside the group reports `group` to that invitee as well, and the server
 * cannot report anything else — one room carries one value, with no per-participant view. So a
 * server `group` never decides this participant's type on its own; the branch below explains what
 * decides it instead.
 *
 * `instant` is the one unconditional branch, and it is a one-way latch: a call resolved as instant
 * never goes back to `1on1`/`group`. The latch is load-bearing, not defensive — a participant
 * leaving an upgraded 1v1 (count 3 → 2) would otherwise flip the type back on the next
 * `RoomUpdate`, visibly reverting the title, layout and add-participant entry mid-call.
 *
 * A server value this build cannot parse is treated as multi-party (`instant`), never as `1on1`.
 * That is the cross-platform contract rather than a local judgement call, so a client too old to
 * understand a newly added type degrades identically everywhere.
 */
internal object CallTypeResolver {

    /** Participant count that still counts as 1v1: the local user plus exactly one remote. */
    const val ONE_ON_ONE_MAX_PARTICIPANTS = 2

    /**
     * @param serverCallType raw `room.metadata.callType`. Null, blank, or unparseable when the
     *   server has not sent it or sent a value newer than this build, in which case [currentCallType]
     *   becomes the base so the corrections that shipped before this field existed keep running —
     *   the contract is "fall through to the existing behavior", not "reset to a default".
     * @param participantCount total participants in the room, INCLUDING the local user. This counts
     *   LiveKit participants, so two devices signed into the same account count twice.
     * @param localCallType the type resolved before joining ([com.difft.android.call.CallIntent.callType]).
     *   Used solely as the "am I still in the group" answer: the pre-join resolution in
     *   `CallMessageCallingProcessor` already downgrades to `instant` when the local user has left
     *   the group, so no in-call group membership query is needed.
     * @param currentCallType the type currently in effect, which drives the instant latch.
     * @return the type to apply, or null when nothing can be decided and the caller must leave the
     *   current type untouched.
     */
    fun resolve(
        serverCallType: String?,
        participantCount: Int,
        localCallType: String,
        currentCallType: String,
    ): CallType? {
        if (currentCallType == CallType.INSTANT.type) return CallType.INSTANT

        val base = serverCallType?.takeIf { it.isNotBlank() }
            ?: currentCallType.takeIf { it.isNotBlank() }
            ?: return null

        return when (CallType.fromString(base)) {
            CallType.ONE_ON_ONE ->
                if (participantCount <= ONE_ON_ONE_MAX_PARTICIPANTS) CallType.ONE_ON_ONE
                else CallType.INSTANT

            // A server `group` carries nothing about THIS participant, so the answer comes entirely
            // from [localCallType]: the pre-join resolution already asked whether the local user is
            // in the group and downgraded to `instant` when they are not. Across the values
            // localCallType can actually hold in a `group` room that makes this branch an identity —
            // which is precisely why it must not be "simplified" to `-> CallType.GROUP`. An
            // out-of-group invitee resolved as `group` would be shown the group's name, which they
            // are not entitled to see, and their hangup would route to `For.Group`, whose member
            // public keys they cannot fetch, so the message would fail to encrypt.
            CallType.GROUP ->
                if (localCallType == CallType.GROUP.type) CallType.GROUP
                else CallType.INSTANT

            CallType.INSTANT -> CallType.INSTANT

            // A value the server added after this build shipped (e.g. "external") counts as
            // multi-party, never as 1v1 — the cross-platform contract, so that a client too old to
            // understand a new type degrades the same way on all three platforms instead of each
            // picking its own fallback. An unparseable base that did NOT come from the server means
            // the local type itself is malformed: nothing authoritative to act on, so leave the
            // current type alone.
            //
            // The cost is real and accepted: a value differing only in spelling or casing turns a
            // genuine 1v1 into instant, which renames the room and suppresses the join-time mic. That
            // makes `callType` a value the server must not change casing or wording of without
            // shipping clients first.
            null -> if (serverCallType.isNullOrBlank()) null else CallType.INSTANT
        }
    }
}
