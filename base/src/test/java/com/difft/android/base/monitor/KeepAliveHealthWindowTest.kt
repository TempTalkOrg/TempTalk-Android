package com.difft.android.base.monitor

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeepAliveHealthWindowTest {

    init {
        
        KeepAliveHealthWindow.zoneOffsetMs = { 0L }
    }

    private val day = KeepAliveHealthWindow.DAY_MS
    private val t0 = 100 * day + 5_000L 

    private fun slot(dayIdx: Long, firstWrite: Long, vararg counts: Pair<String, Int>) =
        DailySlot(dayIdx, firstWrite, counts.toMap())

    @Test
    fun `mergeSlots keeps only window keys and stamps t0 on the first write of a day`() {
        val first = KeepAliveHealthWindow.mergeSlots(emptyList(), mapOf("lat_ws_more" to 2, "fgs_alive_s" to 90), t0)
        assertEquals(listOf(slot(100, t0, "lat_ws_more" to 2)), first)

        val later = KeepAliveHealthWindow.mergeSlots(first, mapOf("lat_ws_more" to 1, "pull_ok" to 1), t0 + 3_600_000L)
        assertEquals(listOf(slot(100, t0, "lat_ws_more" to 3, "pull_ok" to 1)), later)

        
        assertEquals(emptyList(), KeepAliveHealthWindow.mergeSlots(emptyList(), mapOf("fgs_alive_s" to 90), t0))
    }

    @Test
    fun `slots older than 7 days are pruned and the start moves to the oldest kept t0`() {
        val slots = (0L..7L).map { slot(100 + it, (100 + it) * day + it, "pull_ok" to 1) }
        val kept = KeepAliveHealthWindow.normalize(slots, 107 * day + 1)
        assertEquals((101L..107L).toList(), kept.map { it.day })
        assertEquals(101 * day + 1, KeepAliveHealthWindow.windowStart(kept))
    }

    @Test
    fun `a future-dated slot after a clock rollback empties the window`() {
        val slots = listOf(slot(100, 100 * day, "pull_ok" to 1), slot(103, 103 * day, "pull_ok" to 1))
        assertEquals(emptyList(), KeepAliveHealthWindow.normalize(slots, 101 * day))
        
        assertEquals(
            listOf(slot(101, 101 * day, "pull_ok" to 1)),
            KeepAliveHealthWindow.mergeSlots(slots, mapOf("pull_ok" to 1), 101 * day)
        )
    }

    @Test
    fun `codec round trips, merges duplicate days and drops corrupt slots`() {
        val slots = listOf(slot(100, 5L, "pull_ok" to 2), slot(101, 9L, "lat_fcm_more" to 1))
        assertEquals(slots, KeepAliveHealthWindow.decode(KeepAliveHealthWindow.encode(slots)))
        assertEquals(
            listOf(slot(100, 3L, "a" to 3)),
            KeepAliveHealthWindow.decode("100@5=a:1;bad;x@1=a:1;100@3=a:2;101@=a:1;102@5")
        )
        assertEquals(emptyList(), KeepAliveHealthWindow.decode(null))
    }

    private fun verdict(counts: Map<String, Int>, ageMs: Long = 3 * day, now: Long = 200 * day): HealthVerdict =
        KeepAliveHealthWindow.verdict(listOf(DailySlot(199, now - ageMs, counts)), now)

    @Test
    fun `latency rule is late deliveries on two days of the window, whatever the count or share`() {
        
        assertEquals("0", verdict(mapOf("late_5m" to 30)).value)
        assertEquals("0", verdict(mapOf("late_5m" to 30, "lat_ws_2h" to 60)).value)
        
        assertEquals("0", verdict(mapOf("lat_ws_2h" to 60, "lat_fcm_more" to 60)).value)
    }

    @Test
    fun `late deliveries on two days of the window trigger L, the share of slow samples aside`() {
        val now = 200 * day
        val slots = listOf(
            DailySlot(197, now - 3 * day, mapOf("late_5m" to 1, "lat_ws_10s" to 500)),
            DailySlot(199, now - day, mapOf("late_5m" to 1)),
        )
        val v = KeepAliveHealthWindow.verdict(slots, now)
        assertEquals(listOf("L"), v.why)
        assertEquals(2, v.lateDeliveries)
    }

    @Test
    fun `foreground service rule needs 10 attempts and half failing`() {
        assertEquals("0", verdict(mapOf("fgs_starts" to 5, "fgs_fail" to 4)).value) 
        assertEquals(listOf("F"), verdict(mapOf("fgs_starts" to 5, "fgs_fail" to 5)).why)
        assertEquals("0", verdict(mapOf("fgs_starts" to 6, "fgs_fail" to 4)).value) 
    }

    @Test
    fun `pull rule triggers at 5 failures`() {
        assertEquals("0", verdict(mapOf("pull_fail" to 4, "pull_ok" to 100)).value)
        assertEquals(listOf("P"), verdict(mapOf("pull_fail" to 5)).why)
    }

    @Test
    fun `rules combine and the verdict stays na until 3 days of observation`() {
        val all = mapOf("pull_fail" to 5, "fgs_fail" to 10, "late_5m" to 3, "lat_fcm_more" to 50)
        assertEquals(listOf("F", "P"), verdict(all).why)
        assertEquals("1", verdict(all).value)

        val young = verdict(all, ageMs = 3 * day - 1)
        assertEquals("na", young.value)
        assertEquals(emptyList(), young.why)
        assertEquals(50, young.samples) 
        assertEquals("na", KeepAliveHealthWindow.verdict(emptyList(), 200 * day).value)
    }

    @Test
    fun `fcm only share is reported separately`() {
        val v = verdict(mapOf("lat_fcm_10s" to 30, "lat_fcm_2h" to 5, "lat_fcm_more" to 5, "lat_ws_10s" to 10))
        assertEquals(40, v.fcmSamples)
        assertEquals(25, v.fcmSlowPercent)
        assertEquals(50, v.samples)
        assertEquals(1, v.days)
        assertEquals(null, verdict(mapOf("lat_ws_10s" to 3)).fcmSlowPercent)
    }

    @Test
    fun `fcm transit and stall aggregates are report-only and leave the latency rule alone`() {
        val v = verdict(mapOf("fcmt_10s" to 30, "fcmt_2h" to 5, "fcmt_more" to 45, "stall_more" to 3, "stall_2h" to 2, "stall_min" to 900))
        assertEquals("0", v.value)
        assertEquals(0, v.samples)
        assertEquals(80, v.fcmTransitSamples)
        assertEquals(62, v.fcmTransitSlowPercent)
        assertEquals(3, v.stallsOver2h)
        assertEquals(null, verdict(mapOf("lat_ws_10s" to 3)).fcmTransitSlowPercent)

        
        assertEquals("0", verdict(mapOf("lat_ws_10s" to 50, "fcmt_more" to 50)).value)
        val young = verdict(mapOf("fcmt_more" to 2, "stall_more" to 1), ageMs = day)
        assertEquals("na", young.value)
        assertEquals(2, young.fcmTransitSamples) 
        assertEquals(1, young.stallsOver2h)
    }

    @Test
    fun `daily keys cover latency, transit and stall buckets plus seven counters`() {
        assertEquals(35, KeepAliveHealthWindow.DAILY_KEYS.size)
        assertTrue("late_5m" in KeepAliveHealthWindow.DAILY_KEYS)
        assertTrue("fcmt_more" in KeepAliveHealthWindow.DAILY_KEYS)
        assertTrue("stall_min" in KeepAliveHealthWindow.DAILY_KEYS)
        assertTrue("fgs_fail" in KeepAliveHealthWindow.DAILY_KEYS)
        assertTrue("fgs_alive_s" !in KeepAliveHealthWindow.DAILY_KEYS)
    }

    @Test
    fun `a slot one day ahead is kept and a later one empties the window`() {
        val today = 107L
        val kept = KeepAliveHealthWindow.normalize(listOf(slot(107, 107 * day, "pull_ok" to 1), slot(108, 108 * day, "pull_ok" to 1)), today * day + 1)
        assertEquals(listOf(107L, 108L), kept.map { it.day })
        assertEquals(emptyList(), KeepAliveHealthWindow.normalize(listOf(slot(109, 109 * day, "pull_ok" to 1)), today * day + 1))
    }
}
