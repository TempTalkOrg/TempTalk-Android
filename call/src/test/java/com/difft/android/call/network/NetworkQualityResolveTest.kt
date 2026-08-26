package com.difft.android.call.network

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [resolveBadge] — the per-tile render decision.
 *
 * Three regression guards live here:
 *  - the local tile never gets a badge. The banner announces a bad local link in every scene, so a
 *    local badge would report the same fault twice on the one tile the user cannot read as "that
 *    person is the problem".
 *  - the two-person hand-off is decided by HEADCOUNT, never by the layout or the call type: a
 *    2-person group call has tiles, yet "the other party" is unambiguous, so it takes the banner.
 *  - only the BAD tier renders, and only for an identity the snapshot actually names.
 */
class NetworkQualityResolveTest {

    private val localIdentity = "self"

    private fun badge(
        view: NetworkQualityView,
        identity: String,
        participantCount: Int,
    ) = resolveBadge(view, localIdentity, participantCount, identity)

    @Test
    fun `the local tile never carries a badge`() {
        val localBad = NetworkQualityView(local = NetworkQualityLevel.BAD)
        assertFalse(
            badge(localBad, identity = localIdentity, participantCount = 4),
            "a bad local link is the banner's job in every scene, never the local tile's",
        )
        assertFalse(
            badge(localBad, identity = localIdentity, participantCount = 2),
            "the same holds with only two people in the call",
        )
    }

    @Test
    fun `a bad remote is badged in a multi-party call`() {
        val remoteBad = NetworkQualityView(remote = mapOf("alice" to NetworkQualityLevel.BAD))

        assertTrue(badge(remoteBad, identity = "alice", participantCount = 3))
        assertFalse(
            badge(remoteBad, identity = "bob", participantCount = 3),
            "only the identity the snapshot names is badged",
        )
        assertFalse(
            badge(remoteBad, identity = "ghost", participantCount = 3),
            "an identity absent from the snapshot is healthy, not a fault",
        )
    }

    @Test
    fun `two people in the call hand a bad remote to the banner`() {
        val remoteBad = NetworkQualityView(remote = mapOf("alice" to NetworkQualityLevel.BAD))
        assertFalse(
            badge(remoteBad, identity = "alice", participantCount = 2),
            "with one peer the banner says who it is — the badge would be redundant",
        )
        assertTrue(
            badge(remoteBad, identity = "alice", participantCount = 3),
            "the SAME snapshot badges as soon as a third person is in the call",
        )
    }

    @Test
    fun `a good remote entry is never badged`() {
        val remoteGood = NetworkQualityView(remote = mapOf("alice" to NetworkQualityLevel.GOOD))
        assertFalse(
            badge(remoteGood, identity = "alice", participantCount = 3),
            "only the bad tier renders — the snapshot's remote map also carries non-bad entries",
        )
    }

    @Test
    fun `a suppressed snapshot badges nothing without reading the flag`() {
        // A suppressed snapshot is already emptied upstream, so the decision falls out of the
        // emptied values alone and never has to branch on `suppressed` (double suppression).
        assertFalse(badge(NetworkQualityView(suppressed = true), identity = "alice", participantCount = 3))
    }
}
