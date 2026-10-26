package com.difft.android.base.monitor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.storage.AppStateKeys
import io.mockk.mockk
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
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeepAliveHealthReporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var reporter: KeepAliveHealthReporter
    private lateinit var recorder: ForegroundServiceFailureRecorder
    private lateinit var counters: KeepAliveHealthCounters
    private var elapsed = 0L
    private val sent = mutableListOf<String>()
    private val day = KeepAliveHealthModel.REPORT_INTERVAL_MS
    private val zeroTail = "lat_ws_10s=0 lat_ws_1m=0 lat_ws_5m=0 lat_ws_30m=0 lat_ws_2h=0 lat_ws_more=0 " +
        "lat_fcm_10s=0 lat_fcm_1m=0 lat_fcm_5m=0 lat_fcm_30m=0 lat_fcm_2h=0 lat_fcm_more=0 " +
        "lat_pull_10s=0 lat_pull_1m=0 lat_pull_5m=0 lat_pull_30m=0 lat_pull_2h=0 lat_pull_more=0 " +
        "fcmt_10s=0 fcmt_1m=0 fcmt_5m=0 fcmt_30m=0 fcmt_2h=0 fcmt_more=0 " +
        "stall_30m=0 stall_2h=0 stall_more=0 stall_min=0 " +
        "pv=na pv_why=none pv_days=0 pv_n=0 pv_fcm_n=0 pv_fcm_slow=na " +
        "pv_fcmt_n=0 pv_fcmt_slow=na pv_stall_2h=0 fcm_state=unknown fcm_fail_h=0" + POWER_TAIL

    private val snapshot = HealthSnapshot(
        sdkInt = 34, keepAliveEnabled = true, ignoringBatteryOptimizations = true,
        backgroundRestricted = false, restrictBackgroundStatus = 1, notificationsEnabled = true,
        canUseFullScreenIntent = true, standbyBucket = 10, canScheduleExactAlarms = false
    )

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { tmp.newFile("s.preferences_pb") })
        counters = KeepAliveHealthCounters(store).apply {
            elapsedClock = { elapsed }
            serverNow = { 1_000_000L }
            isForeground = { false }
            wallClock = { 0L }
            lastFlushElapsed = 0L // keep hot-path counts in memory so the report drains them deterministically
        }
        reporter = KeepAliveHealthReporter(mockk(relaxed = true), store, counters)
        recorder = ForegroundServiceFailureRecorder(store)
    }

    @After
    fun tearDown() {
        util.AppForegroundObserver.removeListener(counters.backgroundListener)
        scope.cancel()
    }

    private suspend fun run(enabled: Boolean, exits: List<ExitSample>, now: Long, slot: Int = 99) =
        reporter.process(enabled, exits, now, { snapshot }, { sent += it }, { slot })

    private suspend fun prefs() = store.data.first()

    @Test
    fun `first enabled launch seeds window without reporting`() = runTest {
        run(true, listOf(ExitSample("ANR", true)), now = 1_000L)
        assertEquals(emptyList(), sent)
        assertEquals(1_000L, prefs()[AppStateKeys.BG_HEALTH_LAST_REPORT_TS])
        assertNull(prefs()[AppStateKeys.BG_HEALTH_EXIT_COUNTS])
    }

    @Test
    fun `throttled launches accumulate then one report clears pending and restarts window`() = runTest {
        run(true, emptyList(), now = 1_000L)
        recorder.recordNow(FgsStartFailure.DIRECT_NOT_ALLOWED)
        run(true, listOf(ExitSample("EXIT_SELF", false)), now = 2_000L)
        run(true, listOf(ExitSample("EXIT_SELF", false), ExitSample("ANR", true)), now = 3_000L)
        assertEquals(emptyList(), sent)
        assertEquals("ANR_fg:1,EXIT_SELF_bg:2", prefs()[AppStateKeys.BG_HEALTH_EXIT_COUNTS]?.split(',')?.sorted()?.joinToString(","))

        val reportAt = 1_000L + day
        run(true, listOf(ExitSample("OTHER", false)), now = reportAt)
        assertEquals(1, sent.size)
        assertEquals(
            "window_min=1440 sdk=34 keep_alive=1 ignore_batt=1 bg_restricted=0 data_saver=off notif=1 fsi=1 " +
                "bucket=ACTIVE exact_alarm=0 exits_total=4 exit_ANR_fg=1 exit_EXIT_SELF_bg=2 exit_OTHER_bg=1 " +
                "fgs_fail_total=1 fgs_fail_DIRECT_NOT_ALLOWED=1 fgs_alive_min=0 fgs_starts=0 fcm_recv=0 fcm_deprio=0 pull_ok=0 pull_fail=0 " +
                zeroTail,
            sent[0]
        )
        assertEquals(reportAt, prefs()[AppStateKeys.BG_HEALTH_LAST_REPORT_TS])
        assertNull(prefs()[AppStateKeys.BG_HEALTH_EXIT_COUNTS])
        assertNull(prefs()[AppStateKeys.BG_HEALTH_FGS_FAILS])

        run(true, emptyList(), now = reportAt + 1)
        assertEquals(1, sent.size)
    }

    @Test
    fun `all-zero window still reports the denominator`() = runTest {
        run(true, emptyList(), now = 0L)
        run(true, emptyList(), now = day)
        assertEquals(1, sent.size)
        assertEquals(true, sent[0].endsWith("exits_total=0 fgs_fail_total=0 fgs_alive_min=0 fgs_starts=0 fcm_recv=0 fcm_deprio=0 pull_ok=0 pull_fail=0 " + zeroTail))
    }

    @Test
    fun `disabled keep-alive drops pending data and window`() = runTest {
        run(true, emptyList(), now = 0L)
        recorder.recordNow(FgsStartFailure.RETRY_FAILED)
        run(true, listOf(ExitSample("ANR", true)), now = 10L)
        run(false, listOf(ExitSample("ANR", true)), now = day * 2)
        assertEquals(emptyList(), sent)
        assertNull(prefs()[AppStateKeys.BG_HEALTH_LAST_REPORT_TS])
        assertNull(prefs()[AppStateKeys.BG_HEALTH_EXIT_COUNTS])
        assertNull(prefs()[AppStateKeys.BG_HEALTH_FGS_FAILS])
        assertNull(prefs()[AppStateKeys.BG_HEALTH_DAILY])
    }

    @Test
    fun `sink failure leaves pending data for the next launch`() = runTest {
        run(true, emptyList(), now = 0L)
        reporter.process(true, listOf(ExitSample("ANR", true)), day, { snapshot }, { error("boom") })
        assertEquals("ANR_fg:1", prefs()[AppStateKeys.BG_HEALTH_EXIT_COUNTS])
        assertEquals(0L, prefs()[AppStateKeys.BG_HEALTH_LAST_REPORT_TS])
        run(true, emptyList(), now = day + 1)
        assertEquals(1, sent.size)
        assertEquals(true, sent[0].contains("exit_ANR_fg=1"))
    }

    @Test
    fun `events from memory and app_state land in the report and are cleared`() = runTest {
        run(true, emptyList(), now = 0L)
        counters.onFcmReceived(deprioritized = true)
        counters.persist(mapOf("fcm_recv" to 2, "lat_ws_10s" to 1))
        counters.onEnvelopeDelivered(serverTimestamp = 1_000_000L - 90_000L, channel = DeliveryChannel.WS)
        counters.onServiceStarted()
        elapsed += 30 * 60_000L
        run(true, emptyList(), now = day)
        assertEquals(1, sent.size)
        assertEquals(
            true,
            sent[0].endsWith(
                " fgs_alive_min=30 fgs_starts=1 fcm_recv=3 fcm_deprio=1 pull_ok=0 pull_fail=0 " +
                    "lat_ws_10s=1 lat_ws_1m=0 lat_ws_5m=1 lat_ws_30m=0 lat_ws_2h=0 lat_ws_more=0 " +
                    "lat_fcm_10s=0 lat_fcm_1m=0 lat_fcm_5m=0 lat_fcm_30m=0 lat_fcm_2h=0 lat_fcm_more=0 " +
                    "lat_pull_10s=0 lat_pull_1m=0 lat_pull_5m=0 lat_pull_30m=0 lat_pull_2h=0 lat_pull_more=0 " +
                    "fcmt_10s=0 fcmt_1m=0 fcmt_5m=0 fcmt_30m=0 fcmt_2h=0 fcmt_more=0 " +
                    "stall_30m=0 stall_2h=0 stall_more=0 stall_min=0 " +
                    "pv=na pv_why=none pv_days=2 pv_n=2 pv_fcm_n=0 pv_fcm_slow=na " +
                    "pv_fcmt_n=0 pv_fcmt_slow=na pv_stall_2h=0 fcm_state=unknown fcm_fail_h=0" + POWER_TAIL
            )
        )
        assertNull(prefs()[AppStateKeys.BG_HEALTH_EVENT_COUNTS])
    }

    @Test
    fun `fcm transit and app stalls reach the 24h fields and the window aggregates`() = runTest {
        run(true, emptyList(), now = 0L)
        val serverNow = 4 * 60 * 60_000L
        counters.serverNow = { serverNow }
        counters.onFcmReceived(deprioritized = false, highPrioritySentTimeMs = serverNow - 5_000L)
        counters.onFcmReceived(deprioritized = false, highPrioritySentTimeMs = serverNow - 3 * 60 * 60_000L)
        counters.onAppStall(durationMs = 3 * 60 * 60_000L)
        run(true, emptyList(), now = day)
        assertEquals(true, sent[0].contains(" fcmt_10s=1 fcmt_1m=0 fcmt_5m=0 fcmt_30m=0 fcmt_2h=0 fcmt_more=1 "), sent[0])
        assertEquals(true, sent[0].contains(" stall_30m=0 stall_2h=0 stall_more=1 stall_min=180 "), sent[0])
        assertEquals(true, sent[0].contains(" pv_n=0 pv_fcm_n=0 pv_fcm_slow=na pv_fcmt_n=2 pv_fcmt_slow=50 pv_stall_2h=1 "), sent[0])
    }

    @Test
    fun `control sample reports without keep-alive and the slot is stable`() = runTest {
        run(false, emptyList(), now = 0L, slot = 0)
        run(false, emptyList(), now = day, slot = 99) // stored slot 0 wins over a new draw
        assertEquals(1, sent.size)
        assertEquals(0, prefs()[AppStateKeys.BG_HEALTH_SAMPLE_SLOT])
    }

    @Test
    fun `non-sampled keep-alive-off install stops collecting and drops events`() = runTest {
        counters.onFcmReceived(deprioritized = false)
        run(false, emptyList(), now = 0L, slot = 50)
        assertEquals(false, counters.collecting)
        counters.onFcmReceived(deprioritized = false)
        assertEquals(emptyMap(), counters.drain())
        assertNull(prefs()[AppStateKeys.BG_HEALTH_EVENT_COUNTS])
    }

    @Test
    fun `recorder accumulates every type but only terminal failures reach the daily window`() = runTest {
        recorder.recordNow(FgsStartFailure.DIRECT_OTHER, nowWall = 5 * day + 10)
        recorder.recordNow(FgsStartFailure.DIRECT_OTHER, nowWall = 5 * day + 20)
        recorder.recordNow(FgsStartFailure.DIRECT_NOT_ALLOWED, nowWall = 5 * day + 25)
        recorder.recordNow(FgsStartFailure.DIRECT_SECURITY, nowWall = 5 * day + 26)
        // Direct failures are followed by a retry, so they never open a daily slot on their own.
        assertEquals(emptyList(), KeepAliveHealthWindow.decode(prefs()[AppStateKeys.BG_HEALTH_DAILY]))

        recorder.recordNow(FgsStartFailure.STARTFG_REFUSED, nowWall = 5 * day + 30)
        recorder.recordNow(FgsStartFailure.RETRY_FAILED, nowWall = 5 * day + 40)
        assertEquals(
            mapOf(
                "DIRECT_OTHER" to 2, "DIRECT_NOT_ALLOWED" to 1, "DIRECT_SECURITY" to 1,
                "STARTFG_REFUSED" to 1, "RETRY_FAILED" to 1
            ),
            KeepAliveHealthCodec.decode(prefs()[AppStateKeys.BG_HEALTH_FGS_FAILS])
        )
        assertEquals(
            listOf(DailySlot(5, 5 * day + 30, mapOf("fgs_fail" to 2))),
            KeepAliveHealthWindow.decode(prefs()[AppStateKeys.BG_HEALTH_DAILY])
        )
        assertNull(prefs()[AppStateKeys.BG_HEALTH_EVENT_COUNTS])
        assertEquals(
            setOf(FgsStartFailure.RETRY_FAILED, FgsStartFailure.STARTFG_REFUSED, FgsStartFailure.NO_CHANNEL),
            FgsStartFailure.entries.filter { it.terminal }.toSet()
        )
    }

    @Test
    fun `recovered starts do not trip the foreground service rule`() = runTest {
        // 10 starts that each needed the background retry: 10 direct failures, 10 successful starts.
        repeat(10) { recorder.recordNow(FgsStartFailure.DIRECT_NOT_ALLOWED, nowWall = 100 * day + it) }
        store.edit { KeepAliveHealthWindow.mergeEvents(it, mapOf("fgs_starts" to 10), 100 * day) }
        val slots = KeepAliveHealthWindow.decode(prefs()[AppStateKeys.BG_HEALTH_DAILY])
        assertEquals("0", KeepAliveHealthWindow.verdict(slots, 104 * day).value)
    }

    // Seeds the window and adds counts to the daily slot the way a counters flush does.
    private suspend fun preload(at: Long, counts: Map<String, Int>) {
        store.edit { KeepAliveHealthWindow.mergeEvents(it, counts, at) }
    }

    @Test
    fun `T1 report drain lands in the daily slot before the verdict`() = runTest {
        val base = 100 * day
        run(true, emptyList(), now = base)
        preload(base, mapOf("lat_ws_more" to 49))
        counters.serverNow = { 100_000_000L }
        counters.onEnvelopeDelivered(serverTimestamp = 1_000L, channel = DeliveryChannel.WS) // memory only
        run(true, emptyList(), now = base + 3 * day)
        assertEquals(1, sent.size)
        assertEquals(true, sent[0].contains(" lat_ws_more=50 "), sent[0])
        assertEquals(true, sent[0].contains(" pv=1 pv_why=L pv_days=2 pv_n=50 "), sent[0])
        // The drained in-memory sample is merged into today's slot, next to the 49 already persisted.
        assertEquals(
            listOf(49, 1),
            KeepAliveHealthWindow.decode(prefs()[AppStateKeys.BG_HEALTH_DAILY]).map { it.counts.getValue("lat_ws_more") }
        )
    }

    @Test
    fun `T2 an idle gap prunes the window before the verdict`() = runTest {
        val base = 100 * day
        run(true, emptyList(), now = base)
        preload(base, mapOf("lat_ws_more" to 50))
        run(true, emptyList(), now = base + 8 * day)
        assertEquals(1, sent.size)
        assertEquals(true, sent[0].contains(" pv=na pv_why=none pv_days=0 pv_n=0 "), sent[0])
        assertNull(prefs()[AppStateKeys.BG_HEALTH_DAILY])
    }

    @Test
    fun `T4 verdict waits 72h of real observation and pruning moves the start forward`() = runTest {
        val first = 101 * day - 60_000L // day 100, 23:59
        run(true, emptyList(), now = first)
        preload(first, mapOf("lat_ws_more" to 50))
        run(true, emptyList(), now = 103 * day) // 2 days 1 min later
        assertEquals(true, sent[0].contains(" pv=na pv_why=none pv_days=1 pv_n=50 "), sent[0])

        run(true, emptyList(), now = 104 * day + 1) // more than 72 h since the first write
        assertEquals(true, sent[1].contains(" pv=1 pv_why=L pv_days=1 pv_n=50 "), sent[1])
    }

    @Test
    fun `T4 start is the oldest retained slot, not the pruned one`() = runTest {
        val first = 101 * day - 60_000L
        run(true, emptyList(), now = first)
        preload(first, mapOf("lat_ws_more" to 50)) // day 100
        val second = 105 * day - 60_000L
        preload(second, mapOf("lat_ws_more" to 5)) // day 104
        run(true, emptyList(), now = 107 * day) // day 100 pruned; start = day-104 t0, 2 days 1 min old
        assertEquals(true, sent[0].contains(" pv=na pv_why=none pv_days=1 pv_n=5 "), sent[0])
    }

    @Test
    fun `report carries the fcm token state and failure hours`() = runTest {
        run(true, emptyList(), now = 0L)
        store.edit {
            it[AppStateKeys.BG_FCM_STATE] = "fail"
            it[AppStateKeys.BG_FCM_FAIL_SINCE] = 8 * day - 31 * 3_600_000L
        }
        run(true, emptyList(), now = 8 * day)
        assertEquals(true, sent[0].endsWith(" fcm_state=fail fcm_fail_h=31$POWER_TAIL"), sent[0])

        store.edit { it[AppStateKeys.BG_FCM_STATE] = "nogms" } // failure hours only apply to a failing state
        run(true, emptyList(), now = 9 * day)
        assertEquals(true, sent[1].endsWith(" fcm_state=nogms fcm_fail_h=0$POWER_TAIL"), sent[1])
    }

    @Test
    fun `isReporting covers keep-alive users and the sampled control group and persists the slot`() = runTest {
        assertEquals(true, reporter.isReporting(false, newSampleSlot = { 3 }))
        assertEquals(3, prefs()[AppStateKeys.BG_HEALTH_SAMPLE_SLOT])
        assertEquals(true, reporter.isReporting(false, newSampleSlot = { 99 })) // stored slot wins
        assertEquals(true, reporter.isReporting(true))

        store.edit { it[AppStateKeys.BG_HEALTH_SAMPLE_SLOT] = 50 }
        assertEquals(false, reporter.isReporting(false))
        assertEquals(true, reporter.isReporting(true))
    }

    @Test
    fun `exits seen while keep-alive is unknown are stashed, and a disabled decision drops them`() = runTest {
        reporter.onProbe(null, listOf(ExitSample("ANR", true)))
        reporter.onProbe(null, listOf(ExitSample("ANR", true), ExitSample("EXIT_SELF", false)))
        assertEquals("ANR_fg:2,EXIT_SELF_bg:1", prefs()[AppStateKeys.BG_HEALTH_EXIT_COUNTS]?.split(',')?.sorted()?.joinToString(","))
        assertNull(prefs()[AppStateKeys.BG_HEALTH_LAST_REPORT_TS]) // no decision was made

        run(false, emptyList(), now = 0L, slot = 50)
        assertNull(prefs()[AppStateKeys.BG_HEALTH_EXIT_COUNTS])
    }

    @Test
    fun `stashed exits reach the report of an eligible install`() = runTest {
        run(true, emptyList(), now = 0L)
        reporter.onProbe(null, listOf(ExitSample("ANR", true)))
        run(true, emptyList(), now = day)
        assertEquals(true, sent.single().contains(" exits_total=1 exit_ANR_fg=1 "), sent.single())
    }

    private companion object {
        // adb is unknown in the test snapshot; no power counters were recorded.
        val POWER_TAIL = " adb=na" + BackgroundPowerModel.formatSection(emptyMap())
    }
}
