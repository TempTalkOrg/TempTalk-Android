package com.difft.android.base.monitor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.storage.AppStateKeys
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

class LateDeliveryTrackerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val minute = 60_000L
    private val day = 24L * 60 * minute

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private val counters = mockk<KeepAliveHealthCounters>(relaxed = true)
    private lateinit var tracker: LateDeliveryTracker

    private val now = 1_700_000_000_000L
    private var foreground = false
    private var elapsed = 10_000_000L
    private var wall = 100 * day + 5_000L
    private var serverNow: Long? = now
    private var onlineMs: Long? = Long.MAX_VALUE

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { tmp.newFile("late.preferences_pb") })
        tracker = newTracker()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newTracker() = LateDeliveryTracker(store, counters, ValidatedNetworkTracker { elapsed }).apply {
        serverNow = { this@LateDeliveryTrackerTest.serverNow }
        isForeground = { foreground }
        elapsedClock = { elapsed }
        wallClock = { wall }
        zoneOffsetMs = { 0L }
        onlineMsWithin = { onlineMs }
        scheduleFlush = {}
        lastForegroundElapsed = null
    }

    private fun deliver(latencyMs: Long) = tracker.onEnvelopeDelivered(now - latencyMs)

    private fun count() = runBlocking { tracker.lateCount() }

    private fun days() = runBlocking { tracker.lateDays() }

    @Test
    fun `a background delivery later than 5 minutes counts and feeds the report counter`() {
        deliver(5 * minute)
        assertEquals(0, count())
        deliver(5 * minute + 1)
        assertEquals(1, count())
        verify(exactly = 1) { counters.onLateDelivery() }
    }

    @Test
    fun `delays over 24 hours are an offline phone or a history replay, not push quality`() {
        deliver(24 * 60 * minute)
        assertEquals(1, count())
        deliver(24 * 60 * minute + 1)
        deliver(30 * day)
        assertEquals(1, count())
    }

    @Test
    fun `a foreground delivery counts only as catch-up within 120 seconds of coming to the foreground`() {
        foreground = true
        deliver(10 * minute)
        assertEquals(0, count(), "foreground without a recorded foreground moment")

        tracker.lastForegroundElapsed = elapsed
        elapsed += 120_000L
        deliver(10 * minute)
        assertEquals(1, count(), "at the 120 s edge")

        elapsed += 1L
        deliver(10 * minute)
        assertEquals(1, count(), "after the window")
    }

    @Test
    fun `an untrusted clock and a missing server timestamp are skipped`() {
        serverNow = null
        deliver(10 * minute)
        serverNow = now
        tracker.onEnvelopeDelivered(0L)
        tracker.onEnvelopeDelivered(-1L)
        assertEquals(0, count())
        verify(exactly = 0) { counters.onLateDelivery() }
    }

    @Test
    fun `time spent without a validated network is not counted as late`() {
        
        onlineMs = 2 * minute
        deliver(10 * minute)
        assertEquals(0, count())
        onlineMs = 0L
        deliver(10 * minute)
        assertEquals(0, count())
        
        onlineMs = 6 * minute
        deliver(10 * minute)
        assertEquals(1, count())
        
        onlineMs = null
        deliver(10 * minute)
        assertEquals(1, count())
    }

    @Test
    fun `counts persist per day and the window keeps 7 days`() = runBlocking {
        deliver(10 * minute)
        deliver(10 * minute)
        assertEquals(1, days(), "a backlog on one day is one day, written or not")
        tracker.flushPending()
        assertEquals("d100:2", store.data.first()[AppStateKeys.BG_LATE_DELIVERY_DAYS])
        assertEquals(1, days())

        wall += 3 * day
        deliver(10 * minute)
        assertEquals(2, days(), "the second day counts before it is written")
        assertEquals(setOf(100L, 103L), tracker.lateDayKeys())
        tracker.flushPending()
        assertEquals(3, newTracker().lateCount())
        assertEquals(2, newTracker().lateDays())

        
        wall = 106 * day
        assertEquals(3, newTracker().lateCount())
        wall = 107 * day
        assertEquals(1, newTracker().lateCount())
        wall = 110 * day
        assertEquals(0, newTracker().lateCount())
    }

    @Test
    fun `days are local calendar days, so a backlog across UTC midnight stays one day`() = runBlocking {
        tracker.zoneOffsetMs = { 8 * 60 * minute }
        wall = 101 * day - 30 * minute 
        deliver(10 * minute)
        tracker.flushPending()
        wall = 101 * day + 30 * minute 
        deliver(10 * minute)
        tracker.flushPending()
        assertEquals("d101:2", store.data.first()[AppStateKeys.BG_LATE_DELIVERY_DAYS])
        assertEquals(1, tracker.lateDays())
    }

    @Test
    fun `pending deliveries count before they are written and coalesce into one write`() = runBlocking {
        repeat(4) { deliver(10 * minute) }
        assertEquals(4, tracker.lateCount())
        tracker.flushPending()
        assertEquals("d100:4", store.data.first()[AppStateKeys.BG_LATE_DELIVERY_DAYS])
        assertEquals(4, tracker.lateCount())
    }

    @Test
    fun `a clock moved back empties the window and starts again`() {
        assertEquals(mapOf(100L to 2, 103L to 3), LateDeliveryTracker.merge("d100:2,d103:1", 2, 103 * day))
        assertEquals(0, LateDeliveryTracker.count("d100:2,d103:1", 101 * day))
        assertEquals(mapOf(101L to 1), LateDeliveryTracker.merge("d100:2,d103:1", 1, 101 * day))
    }

    @Test
    fun `corrupt stored counts are ignored`() {
        assertEquals(1, LateDeliveryTracker.count("x1:5,dabc:2,d100:1,bad", 100 * day))
    }
}
