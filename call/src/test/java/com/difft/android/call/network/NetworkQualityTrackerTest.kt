package com.difft.android.call.network

import io.livekit.android.room.participant.ConnectionQuality
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue









class NetworkQualityTrackerTest {

    private val tracker = NetworkQualityTracker()

    
    private fun feedLocal(level: NetworkQualityLevel, now: Long): Boolean =
        tracker.onQualityChanged(LOCAL_KEY, level, isLocal = true, now = now)

    private fun feedRemote(identity: String, level: NetworkQualityLevel, now: Long): Boolean =
        tracker.onQualityChanged(identity, level, isLocal = false, now = now)

    
    
    
    @Test
    fun `worsening publishes at 3000 not 2900`() {
        feedLocal(NetworkQualityLevel.BAD, now = 0)

        assertFalse(tracker.evaluate(2_900), "2.9 s must not fill the 3 s worsening delay")
        assertEquals(NetworkQualityLevel.EXCELLENT, tracker.view().local)

        assertTrue(tracker.evaluate(3_000), "3.0 s must fill the delay (boundary is >=)")
        assertEquals(NetworkQualityLevel.BAD, tracker.view().local)

        assertFalse(tracker.evaluate(3_001), "a settled entry must not report a change again")
    }

    @Test
    fun `short dip below 3s is swallowed whole`() {
        feedLocal(NetworkQualityLevel.BAD, now = 0)
        feedLocal(NetworkQualityLevel.EXCELLENT, now = 2_000)

        for (now in longArrayOf(2_000, 2_900, 3_000, 5_000, 8_000)) {
            tracker.evaluate(now)
            assertEquals(NetworkQualityView.NONE, tracker.view(), "a <3 s dip must never surface (now=$now)")
        }
    }

    
    
    
    @Test
    fun `recovery publishes at 5000 not 4900`() {
        feedLocal(NetworkQualityLevel.BAD, now = 0)
        tracker.evaluate(3_000)
        assertEquals(NetworkQualityLevel.BAD, tracker.view().local)

        feedLocal(NetworkQualityLevel.EXCELLENT, now = 3_000)

        tracker.evaluate(7_900)
        assertEquals(NetworkQualityLevel.BAD, tracker.view().local, "4.9 s after recovery is still bad")

        tracker.evaluate(8_000)
        assertEquals(NetworkQualityLevel.EXCELLENT, tracker.view().local, "5.0 s after recovery clears it")
    }

    @Test
    fun `recovery lands on the current raw tier not excellent`() {
        
        
        
        
        feedRemote("alice", NetworkQualityLevel.BAD, now = 0)
        tracker.evaluate(3_000)
        assertEquals(NetworkQualityLevel.BAD, tracker.view().remote["alice"])

        feedRemote("alice", NetworkQualityLevel.GOOD, now = 3_000)
        assertTrue(tracker.evaluate(8_000), "5.0 s of improvement republishes the entry")

        val view = tracker.view()
        assertEquals(
            NetworkQualityLevel.GOOD,
            view.remote["alice"],
            "BAD -> GOOD must land on GOOD, not collapse to EXCELLENT and vanish from the snapshot",
        )
        assertTrue(view.badRemoteIdentities.isEmpty(), "GOOD is not a fault, so nothing renders")
    }

    
    
    
    @Test
    fun `unknown never renders`() {
        val unknown = ConnectionQuality.UNKNOWN.toNetworkQualityLevel()
        feedLocal(unknown, now = 0)
        feedRemote("alice", unknown, now = 0)

        tracker.evaluate(10_000)

        assertEquals(NetworkQualityView.NONE, tracker.view())
        assertTrue(tracker.view().badRemoteIdentities.isEmpty())
    }

    
    
    
    @Test
    fun `a published bad verdict never times out on its own`() {
        feedLocal(NetworkQualityLevel.BAD, now = 0)
        tracker.evaluate(3_000)
        assertEquals(NetworkQualityLevel.BAD, tracker.view().local)

        
        for (now in longArrayOf(10_000, 60_000, 300_000)) {
            assertFalse(tracker.evaluate(now), "a settled verdict must not report a change (now=$now)")
            assertEquals(
                NetworkQualityLevel.BAD,
                tracker.view().local,
                "the hint must still be up after ${now / 1_000}s without a new reading",
            )
        }
    }

    @Test
    fun `a second worsening step earns its own 3s`() {
        
        
        feedLocal(NetworkQualityLevel.GOOD, now = 0)
        tracker.evaluate(3_000)

        feedLocal(NetworkQualityLevel.BAD, now = 3_000)

        tracker.evaluate(5_900)
        assertNotEquals(NetworkQualityLevel.BAD, tracker.view().local, "2.9 s of the second step is not enough")

        tracker.evaluate(6_000)
        assertEquals(NetworkQualityLevel.BAD, tracker.view().local)
    }

    
    
    
    @Test
    fun `local bad hides all remotes`() {
        feedLocal(NetworkQualityLevel.BAD, now = 0)
        feedRemote("alice", NetworkQualityLevel.BAD, now = 0)
        tracker.evaluate(3_000)

        val view = tracker.view()
        assertEquals(NetworkQualityLevel.BAD, view.local)
        assertTrue(view.remote.isEmpty(), "a bad local downlink degrades every remote reading — do not blame them")
        assertTrue(view.badRemoteIdentities.isEmpty())
    }

    @Test
    fun `local good does not suppress remotes`() {
        feedLocal(NetworkQualityLevel.GOOD, now = 0)
        feedRemote("alice", NetworkQualityLevel.BAD, now = 0)
        tracker.evaluate(3_000)

        val view = tracker.view()
        assertNotEquals(NetworkQualityLevel.BAD, view.local, "rule 1 must trigger on the BAD tier only")
        assertEquals(NetworkQualityLevel.BAD, view.remote["alice"])
        assertEquals(setOf("alice"), view.badRemoteIdentities)
    }

    
    
    
    @Test
    fun `suppressed view is empty`() {
        feedLocal(NetworkQualityLevel.BAD, now = 0)
        tracker.evaluate(3_000)

        assertTrue(tracker.onSuppressedChanged(isSuppressed = true, now = 3_000))

        val view = tracker.view()
        assertEquals(NetworkQualityView(suppressed = true), view)
        assertEquals(NetworkQualityLevel.EXCELLENT, view.local)
        assertTrue(view.remote.isEmpty())
        assertTrue(view.suppressed)

        assertFalse(
            tracker.onSuppressedChanged(isSuppressed = true, now = 4_000),
            "re-asserting the same flag is not a transition",
        )
    }

    @Test
    fun `leaving suppression restarts the timers`() {
        feedLocal(NetworkQualityLevel.BAD, now = 0)
        tracker.evaluate(3_000)
        tracker.onSuppressedChanged(isSuppressed = true, now = 3_000)

        val reconnectedAt = 10_000L
        assertTrue(tracker.onSuppressedChanged(isSuppressed = false, now = reconnectedAt))

        assertEquals(
            NetworkQualityView.NONE,
            tracker.view(),
            "the pre-disconnect verdict must not be republished onto a fresh link",
        )

        tracker.evaluate(reconnectedAt + 2_900)
        assertEquals(NetworkQualityView.NONE, tracker.view())

        tracker.evaluate(reconnectedAt + 3_000)
        assertEquals(NetworkQualityLevel.BAD, tracker.view().local, "a still-bad link lights up 3 s later")
    }

    
    
    
    @Test
    fun `participant left drops its entry`() {
        feedRemote("alice", NetworkQualityLevel.BAD, now = 0)
        tracker.evaluate(3_000)
        assertEquals(NetworkQualityLevel.BAD, tracker.view().remote["alice"])

        tracker.onParticipantLeft("alice")
        assertTrue(tracker.view().remote.isEmpty())

        tracker.evaluate(13_000)
        assertTrue(tracker.view().remote.isEmpty(), "a later tick must not resurrect a departed participant")
    }

    @Test
    fun `repeated same-tier events do not reset since`() {
        assertTrue(feedLocal(NetworkQualityLevel.BAD, now = 0), "the first reading is a real transition")
        assertFalse(feedLocal(NetworkQualityLevel.BAD, now = 1_000), "a repeat of the same tier is not")
        assertFalse(feedLocal(NetworkQualityLevel.BAD, now = 2_000))

        tracker.evaluate(2_999)
        assertEquals(NetworkQualityLevel.EXCELLENT, tracker.view().local)

        tracker.evaluate(3_000)
        assertEquals(
            NetworkQualityLevel.BAD,
            tracker.view().local,
            "the delay is measured from the FIRST reading — resetting per event would never fill it",
        )
    }

    @Test
    fun `suppression hides remotes without deleting them`() {
        feedLocal(NetworkQualityLevel.BAD, now = 0)
        feedRemote("alice", NetworkQualityLevel.BAD, now = 0)
        tracker.evaluate(3_000)
        assertTrue(tracker.view().remote.isEmpty())

        feedLocal(NetworkQualityLevel.EXCELLENT, now = 3_000)
        tracker.evaluate(8_000)

        val view = tracker.view()
        assertEquals(NetworkQualityLevel.EXCELLENT, view.local)
        assertEquals(
            NetworkQualityLevel.BAD,
            view.remote["alice"],
            "hidden, not deleted — the remote verdict must not have to earn its 3 s again",
        )
    }

    @Test
    fun `retainRemotes prunes stale remotes and keeps local`() {
        
        feedLocal(NetworkQualityLevel.GOOD, now = 0)
        feedRemote("stale", NetworkQualityLevel.BAD, now = 0)
        tracker.evaluate(3_000)
        assertEquals(setOf("stale"), tracker.view().badRemoteIdentities)

        
        feedRemote("bob", NetworkQualityLevel.BAD, now = 3_000)
        tracker.retainRemotes(setOf("bob"))
        tracker.evaluate(6_000)

        val view = tracker.view()
        assertEquals(setOf("bob"), view.remote.keys, "the stale remote is gone, the freshly seeded one stays")
        
        
        
        assertEquals(
            NetworkQualityLevel.GOOD,
            view.local,
            "the local entry survives even though it is never in the remote identity set",
        )

        tracker.retainRemotes(setOf("bob"))
        assertEquals(view, tracker.view(), "retainRemotes is idempotent")
    }

    @Test
    fun `reset clears entries and suppression`() {
        feedLocal(NetworkQualityLevel.BAD, now = 0)
        feedRemote("alice", NetworkQualityLevel.BAD, now = 0)
        tracker.evaluate(3_000)
        tracker.onSuppressedChanged(isSuppressed = true, now = 3_000)

        tracker.reset()

        assertEquals(NetworkQualityView.NONE, tracker.view())
        assertTrue(
            tracker.onSuppressedChanged(isSuppressed = true, now = 4_000),
            "the suppression flag is back to false, so asserting it again is a transition",
        )
    }

    private companion object {
        const val LOCAL_KEY = "__local__"
    }
}
