package com.difft.android.base.monitor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.storage.AppStateKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import util.AppForegroundObserver
import kotlin.test.assertEquals

class KeepAliveHealthCountersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var counters: KeepAliveHealthCounters
    private var elapsed = 1_000L
    private var foreground = false
    private var serverNow: Long? = 100_000L

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { tmp.newFile("c.preferences_pb") })
        counters = KeepAliveHealthCounters(store).apply {
            elapsedClock = { elapsed }
            serverNow = { this@KeepAliveHealthCountersTest.serverNow }
            isForeground = { foreground }
        }
    }

    @After
    fun tearDown() {
        AppForegroundObserver.removeListener(counters.backgroundListener)
        scope.cancel()
    }

    @Test
    fun `latency is skipped in foreground and without a trusted clock`() {
        counters.lastFlushElapsed = elapsed // keep counts in memory for drain()
        foreground = true
        counters.onEnvelopeDelivered(serverTimestamp = 90_000L, DeliveryChannel.WS)
        foreground = false
        serverNow = null
        counters.onEnvelopeDelivered(serverTimestamp = 90_000L, DeliveryChannel.WS)
        serverNow = 100_000L
        counters.onEnvelopeDelivered(serverTimestamp = 0L, DeliveryChannel.WS)
        assertEquals(emptyMap(), counters.drain())

        counters.onEnvelopeDelivered(serverTimestamp = 95_000L, DeliveryChannel.WS)
        assertEquals(mapOf("lat_ws_10s" to 1), counters.drain())
    }

    @Test
    fun `latency lands in the arrival channel's counter`() {
        counters.lastFlushElapsed = elapsed
        val now = 10 * 60 * 60_000L
        serverNow = now
        counters.onEnvelopeDelivered(serverTimestamp = now - 5_000L, channel = DeliveryChannel.WS)
        counters.onEnvelopeDelivered(serverTimestamp = now - 5_000L, channel = DeliveryChannel.FCM)
        counters.onEnvelopeDelivered(serverTimestamp = now - 31 * 60_000L, channel = DeliveryChannel.PULL)
        assertEquals(mapOf("lat_ws_10s" to 1, "lat_fcm_10s" to 1, "lat_pull_2h" to 1), counters.drain())
    }

    @Test
    fun `pull results count ok and fail`() {
        counters.lastFlushElapsed = elapsed
        counters.lastPullFlushElapsed = elapsed
        counters.onPullResult(success = true)
        counters.onPullResult(success = false)
        counters.onPullResult(success = false)
        assertEquals(mapOf("pull_ok" to 1, "pull_fail" to 2), counters.drain())
        counters.collecting = false
        counters.onPullResult(success = false)
        assertEquals(emptyMap(), counters.drain())
    }

    @Test
    fun `persist writes the 24h counters and today's daily slot in one edit`() = runTest {
        counters.wallClock = { 100 * KeepAliveHealthWindow.DAY_MS + 7L }
        counters.persist(mapOf("pull_fail" to 2, "lat_fcm_more" to 1, "fgs_alive_s" to 40))
        counters.persist(mapOf("pull_fail" to 1))
        val prefs = store.data.first()
        assertEquals(
            mapOf("pull_fail" to 3, "lat_fcm_more" to 1, "fgs_alive_s" to 40),
            KeepAliveHealthCodec.decode(prefs[AppStateKeys.BG_HEALTH_EVENT_COUNTS])
        )
        assertEquals(
            listOf(DailySlot(100, 100 * KeepAliveHealthWindow.DAY_MS + 7L, mapOf("pull_fail" to 3, "lat_fcm_more" to 1))),
            KeepAliveHealthWindow.decode(prefs[AppStateKeys.BG_HEALTH_DAILY])
        )
    }

    @Test
    fun `an isolated fcm wake persists its pull result seconds after the first-event flush`() = runTest {
        counters.drain()
        counters.onFcmReceived(deprioritized = false) // first event: flushes at once
        assertEquals("fcm_recv:1", awaitPersisted { it == "fcm_recv:1" })

        elapsed += 10_000L // far below the 5 min interval; no alarm tick
        counters.onPullResult(success = true)
        assertEquals(
            mapOf("fcm_recv" to 1, "pull_ok" to 1),
            KeepAliveHealthCodec.decode(awaitPersisted { it == "fcm_recv:1,pull_ok:1" || it == "pull_ok:1,fcm_recv:1" })
        )
        assertEquals(emptyMap(), counters.drain())
    }

    @Test
    fun `a pull result within the floor of the previous pull-result flush stays pending`() = runTest {
        counters.drain()
        counters.onPullResult(success = true)
        awaitPersisted { it == "pull_ok:1" }

        elapsed += KeepAliveHealthCounters.PULL_FLUSH_FLOOR_MS - 1
        counters.onPullResult(success = false)
        assertEquals(mapOf("pull_fail" to 1), counters.drain()) // still in memory, not written

        counters.onPullResult(success = false)
        elapsed += 1
        counters.onPullResult(success = false)
        assertEquals(
            mapOf("pull_ok" to 1, "pull_fail" to 2),
            KeepAliveHealthCodec.decode(awaitPersisted { it.contains("pull_fail:2") })
        )
    }

    @Test
    fun `a service start persists without waiting for an alarm tick`() = runTest {
        counters.onServiceStarted()
        assertEquals("fgs_starts:1", awaitPersisted { it == "fgs_starts:1" })
    }

    @Test
    fun `first event persists at once, later ones wait for the flush interval`() = runTest {
        counters.drain() // the startup report drain must not delay the first-event flush
        counters.onFcmReceived(deprioritized = false)
        assertEquals(mapOf("fcm_recv" to 1), KeepAliveHealthCodec.decode(awaitPersisted { it == "fcm_recv:1" }))

        counters.onFcmReceived(deprioritized = true)
        elapsed += KeepAliveHealthCounters.FLUSH_INTERVAL_MS - 1
        counters.onAlarmTick()
        assertEquals(mapOf("fcm_recv" to 1, "fcm_deprio" to 1), counters.drain())

        counters.onFcmReceived(deprioritized = false)
        elapsed += 1
        counters.onAlarmTick()
        assertEquals(mapOf("fcm_recv" to 2), KeepAliveHealthCodec.decode(awaitPersisted { it == "fcm_recv:2" }))
        assertEquals(emptyMap(), counters.drain())
    }

    @Test
    fun `uptime accrues across ticks and is persisted when the service stops`() = runTest {
        counters.lastFlushElapsed = elapsed
        counters.onServiceStarted()
        counters.onServiceStarted() // onCreate only once per instance; a repeat must not reset the start
        elapsed += 90_000L
        assertEquals(mapOf("fgs_starts" to 1, "fgs_alive_s" to 90), counters.drain())
        elapsed += 30_500L
        counters.onServiceStopped()
        assertEquals("fgs_alive_s:30", awaitPersisted { it == "fgs_alive_s:30" })
        elapsed += 600_000L
        assertEquals(emptyMap(), counters.drain())
    }

    private suspend fun awaitPersisted(matches: (String) -> Boolean): String? {
        repeat(250) {
            store.data.first()[AppStateKeys.BG_HEALTH_EVENT_COUNTS]?.takeIf(matches)?.let { return it }
            Thread.sleep(20)
        }
        return store.data.first()[AppStateKeys.BG_HEALTH_EVENT_COUNTS]
    }
}
