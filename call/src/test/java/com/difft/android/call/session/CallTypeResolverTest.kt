package com.difft.android.call.session

import com.difft.android.base.call.CallType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Rule coverage for [CallTypeResolver] — the cross-platform meeting-type decision.
 *
 * The invariants worth protecting here are the asymmetries: `instant` is the only unconditional
 * branch and is a one-way latch, `1on1`/`group` can only ever be downgraded to `instant`, an unknown
 * server value must land on `instant` (never `1on1`, which would open the mic on join), and a missing
 * server value must leave the pre-existing local behavior running rather than reset anything.
 */
class CallTypeResolverTest {

    private fun resolve(
        serverCallType: String?,
        participantCount: Int = 2,
        localCallType: String = CallType.ONE_ON_ONE.type,
        currentCallType: String = CallType.ONE_ON_ONE.type,
    ) = CallTypeResolver.resolve(serverCallType, participantCount, localCallType, currentCallType)

    // --------------------------------------------------------------------------------------
    // "1on1": holds only while the room has at most 2 participants (local user included).
    // --------------------------------------------------------------------------------------

    @Test
    fun `server 1on1 with two participants stays one-on-one`() {
        assertEquals(CallType.ONE_ON_ONE, resolve(serverCallType = "1on1", participantCount = 2))
    }

    @Test
    fun `server 1on1 with a third participant downgrades to instant`() {
        assertEquals(CallType.INSTANT, resolve(serverCallType = "1on1", participantCount = 3))
    }

    // Two devices of the same account each occupy a participant slot, so a 1v1 between two
    // dual-device users reports 4 and is treated as instant. Pinned deliberately: it is the
    // behavior the cross-platform spec prescribes, and changing it requires the server to switch
    // to de-duplicated account counting in lockstep.
    @Test
    fun `server 1on1 with two dual-device users is instant`() {
        assertEquals(CallType.INSTANT, resolve(serverCallType = "1on1", participantCount = 4))
    }

    // --------------------------------------------------------------------------------------
    // "group": holds only while the local user is still in the group. The pre-join resolution
    // already encodes that answer in localCallType.
    // --------------------------------------------------------------------------------------

    @Test
    fun `server group while still in the group stays group`() {
        assertEquals(
            CallType.GROUP,
            resolve(
                serverCallType = "group",
                participantCount = 5,
                localCallType = CallType.GROUP.type,
                currentCallType = CallType.GROUP.type,
            ),
        )
    }

    @Test
    fun `server group after leaving the group downgrades to instant`() {
        assertEquals(
            CallType.INSTANT,
            resolve(
                serverCallType = "group",
                participantCount = 5,
                // Pre-join resolution downgraded to instant because we are no longer a member.
                localCallType = CallType.INSTANT.type,
                currentCallType = CallType.ONE_ON_ONE.type,
            ),
        )
    }

    @Test
    fun `server group is never upgraded from a non-group local type`() {
        assertEquals(
            CallType.INSTANT,
            resolve(serverCallType = "group", localCallType = CallType.ONE_ON_ONE.type),
        )
    }

    // --------------------------------------------------------------------------------------
    // "instant": unconditional, and a latch once in effect.
    // --------------------------------------------------------------------------------------

    @Test
    fun `server instant is applied unconditionally`() {
        assertEquals(CallType.INSTANT, resolve(serverCallType = "instant", participantCount = 2))
    }

    // The regression this latch exists for: a participant leaves an already-upgraded 1v1 (3 -> 2)
    // while the server still reports 1on1. Without the latch the call would flip back to 1on1 and
    // visibly revert its title, layout and add-participant entry mid-call.
    @Test
    fun `instant is never downgraded back to one-on-one when the room empties out`() {
        assertEquals(
            CallType.INSTANT,
            resolve(
                serverCallType = "1on1",
                participantCount = 2,
                currentCallType = CallType.INSTANT.type,
            ),
        )
    }

    @Test
    fun `instant is never downgraded back to group`() {
        assertEquals(
            CallType.INSTANT,
            resolve(
                serverCallType = "group",
                participantCount = 5,
                localCallType = CallType.GROUP.type,
                currentCallType = CallType.INSTANT.type,
            ),
        )
    }

    // --------------------------------------------------------------------------------------
    // Unknown and missing server values.
    // --------------------------------------------------------------------------------------

    // Cross-platform contract: a type added after this build shipped degrades to multi-party on
    // every platform, so an old client never opens the mic on join by mistaking it for 1v1.
    @Test
    fun `unknown server value is treated as multi-party`() {
        assertEquals(CallType.INSTANT, resolve(serverCallType = "external", participantCount = 2))
    }

    // Same for a value that only differs in casing — fromString matches exactly. Pinned so the
    // blast radius of a server-side rename is visible: it silently turns 1v1 calls into instant.
    @Test
    fun `server value with different casing is treated as unknown`() {
        assertEquals(CallType.INSTANT, resolve(serverCallType = "1ON1", participantCount = 2))
    }

    @Test
    fun `missing server value keeps running the local one-on-one participant rule`() {
        assertEquals(
            CallType.ONE_ON_ONE,
            resolve(serverCallType = null, participantCount = 2, currentCallType = CallType.ONE_ON_ONE.type),
        )
        assertEquals(
            CallType.INSTANT,
            resolve(serverCallType = null, participantCount = 3, currentCallType = CallType.ONE_ON_ONE.type),
        )
    }

    @Test
    fun `blank server value is treated the same as missing`() {
        assertEquals(
            CallType.INSTANT,
            resolve(serverCallType = "   ", participantCount = 3, currentCallType = CallType.ONE_ON_ONE.type),
        )
    }

    @Test
    fun `missing server value leaves a group call untouched`() {
        assertEquals(
            CallType.GROUP,
            resolve(
                serverCallType = null,
                participantCount = 9,
                localCallType = CallType.GROUP.type,
                currentCallType = CallType.GROUP.type,
            ),
        )
    }

    // Nothing authoritative and nothing usable locally: the caller must leave the type alone rather
    // than pick a default.
    @Test
    fun `no server value and no current type decides nothing`() {
        assertNull(resolve(serverCallType = null, currentCallType = ""))
    }
}
