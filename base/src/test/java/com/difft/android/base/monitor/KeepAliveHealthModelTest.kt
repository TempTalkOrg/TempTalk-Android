package com.difft.android.base.monitor

import android.app.ApplicationExitInfo
import org.junit.Test
import kotlin.test.assertEquals

class KeepAliveHealthModelTest {

    private val day = KeepAliveHealthModel.REPORT_INTERVAL_MS
    private val zeroPower = " pw_awake_bat=na pw_el_s_bat=0 pw_up_s_bat=0 pw_el_s_chg=0 pw_up_s_chg=0 pw_cpu_ms_bat=0 " +
        "pw_net_kb_bat=0 pw_batt_mah=0 pw_batt_pct=0 pw_net_wifi_s=0 pw_net_cell_s=0 pw_net_other_s=0 pw_net_none_s=0 " +
        "pw_wake_ka=0 pw_wake_svc=0 pw_ka_sent=0 pw_ka_miss=0 pw_reconnect=0 pw_qwait_2s=0 pw_qwait_10s=0 pw_qwait_more=0"

    private val zeroTail = "lat_ws_10s=0 lat_ws_1m=0 lat_ws_5m=0 lat_ws_30m=0 lat_ws_2h=0 lat_ws_more=0 " +
        "lat_fcm_10s=0 lat_fcm_1m=0 lat_fcm_5m=0 lat_fcm_30m=0 lat_fcm_2h=0 lat_fcm_more=0 " +
        "lat_pull_10s=0 lat_pull_1m=0 lat_pull_5m=0 lat_pull_30m=0 lat_pull_2h=0 lat_pull_more=0 " +
        "fcmt_10s=0 fcmt_1m=0 fcmt_5m=0 fcmt_30m=0 fcmt_2h=0 fcmt_more=0 " +
        "stall_30m=0 stall_2h=0 stall_more=0 stall_min=0 " +
        "pv=na pv_why=none pv_days=0 pv_n=0 pv_fcm_n=0 pv_fcm_slow=na " +
        "pv_fcmt_n=0 pv_fcmt_slow=na pv_stall_2h=0 fcm_state=unknown fcm_fail_h=0" +
        " adb=na" + zeroPower

    @Test
    fun `decide covers disabled seed wait report and clock rollback`() {
        assertEquals(ReportDecision.DISABLED, KeepAliveHealthModel.decide(false, 1L, 5L))
        assertEquals(ReportDecision.SEED, KeepAliveHealthModel.decide(true, null, 5L))
        assertEquals(ReportDecision.SEED, KeepAliveHealthModel.decide(true, 100L, 50L))
        assertEquals(ReportDecision.WAIT, KeepAliveHealthModel.decide(true, 1000L, 1000L + day - 1))
        assertEquals(ReportDecision.REPORT, KeepAliveHealthModel.decide(true, 1000L, 1000L + day))
    }

    @Test
    fun `toSample splits on foreground-service importance boundary`() {
        assertEquals(ExitSample("ANR", true), KeepAliveHealthModel.toSample(ApplicationExitInfo.REASON_ANR, 125))
        assertEquals(ExitSample("ANR", true), KeepAliveHealthModel.toSample(ApplicationExitInfo.REASON_ANR, 100))
        assertEquals(ExitSample("ANR", false), KeepAliveHealthModel.toSample(ApplicationExitInfo.REASON_ANR, 126))
    }

    @Test
    fun `exitReasonName names known reasons and falls back for unknown`() {
        assertEquals("FREEZER", KeepAliveHealthModel.exitReasonName(ApplicationExitInfo.REASON_FREEZER))
        assertEquals("USER_REQUESTED", KeepAliveHealthModel.exitReasonName(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals("OTHER", KeepAliveHealthModel.exitReasonName(ApplicationExitInfo.REASON_OTHER))
        assertEquals("R999", KeepAliveHealthModel.exitReasonName(999))
    }

    @Test
    fun `aggregate counts per reason and importance bucket`() {
        val samples = listOf(
            ExitSample("EXIT_SELF", false), ExitSample("EXIT_SELF", false),
            ExitSample("EXIT_SELF", true), ExitSample("ANR", true)
        )
        assertEquals(
            mapOf("EXIT_SELF_bg" to 2, "EXIT_SELF_fg" to 1, "ANR_fg" to 1),
            KeepAliveHealthModel.aggregate(samples)
        )
    }

    @Test
    fun `merge adds counts`() {
        assertEquals(mapOf("a" to 3, "b" to 1), KeepAliveHealthModel.merge(mapOf("a" to 2), mapOf("a" to 1, "b" to 1)))
    }

    @Test
    fun `codec round trips and drops corrupt pairs`() {
        val counts = mapOf("ANR_fg" to 2, "OTHER_bg" to 1)
        assertEquals(counts, KeepAliveHealthCodec.decode(KeepAliveHealthCodec.encode(counts)))
        assertEquals(mapOf("A" to 1), KeepAliveHealthCodec.decode("A:1,bad,B:x,:3,C:0,D E:2"))
        assertEquals(emptyMap(), KeepAliveHealthCodec.decode(null))
        assertEquals("", KeepAliveHealthCodec.encode(mapOf("A" to 0, "bad key" to 1)))
    }

    @Test
    fun `formatReport is one line and keeps zero totals`() {
        val snapshot = HealthSnapshot(
            sdkInt = 34, keepAliveEnabled = true, ignoringBatteryOptimizations = false,
            backgroundRestricted = false, restrictBackgroundStatus = 1, notificationsEnabled = true,
            canUseFullScreenIntent = null, standbyBucket = 20
        )
        val zero = KeepAliveHealthModel.formatReport(snapshot, day, emptyMap(), emptyMap())
        assertEquals(
            "window_min=1440 sdk=34 keep_alive=1 ignore_batt=0 bg_restricted=0 data_saver=off notif=1 " +
                "fsi=na bucket=WORKING_SET exact_alarm=na exits_total=0 fgs_fail_total=0" +
                " fgs_alive_min=0 fgs_starts=0 fcm_recv=0 fcm_deprio=0 pull_ok=0 pull_fail=0 " +
                zeroTail,
            zero
        )
        val full = KeepAliveHealthModel.formatReport(
            snapshot, 90 * 60_000L,
            mapOf("EXIT_SELF_bg" to 2, "ANR_fg" to 1),
            mapOf("DIRECT_NOT_ALLOWED" to 3)
        )
        assertEquals(
            "window_min=90 sdk=34 keep_alive=1 ignore_batt=0 bg_restricted=0 data_saver=off notif=1 " +
                "fsi=na bucket=WORKING_SET exact_alarm=na exits_total=3 exit_ANR_fg=1 exit_EXIT_SELF_bg=2 " +
                "fgs_fail_total=3 fgs_fail_DIRECT_NOT_ALLOWED=3 fgs_alive_min=0 fgs_starts=0 fcm_recv=0 fcm_deprio=0 pull_ok=0 pull_fail=0 " +
                zeroTail,
            full
        )
    }

    @Test
    fun `formatReport renders events verdict and fcm fields in fixed order`() {
        val snapshot = HealthSnapshot(34, false, true, false, 1, true, true, 10)
        val line = KeepAliveHealthModel.formatReport(
            snapshot, day, emptyMap(), emptyMap(),
            mapOf(
                "lat_ws_more" to 2, "fcm_recv" to 7, "fgs_alive_s" to 3_599, "lat_fcm_10s" to 5,
                "fcm_deprio" to 1, "pull_ok" to 3, "pull_fail" to 1
            ),
            verdict = HealthVerdict("1", listOf("L", "P"), 4, 120, 40, 25),
            fcmState = "fail",
            fcmFailHours = 30
        )
        assertEquals(
            true,
            line.endsWith(
                " fgs_alive_min=59 fgs_starts=0 fcm_recv=7 fcm_deprio=1 pull_ok=3 pull_fail=1 " +
                    "lat_ws_10s=0 lat_ws_1m=0 lat_ws_5m=0 lat_ws_30m=0 lat_ws_2h=0 lat_ws_more=2 " +
                    "lat_fcm_10s=5 lat_fcm_1m=0 lat_fcm_5m=0 lat_fcm_30m=0 lat_fcm_2h=0 lat_fcm_more=0 " +
                    "lat_pull_10s=0 lat_pull_1m=0 lat_pull_5m=0 lat_pull_30m=0 lat_pull_2h=0 lat_pull_more=0 " +
                    "fcmt_10s=0 fcmt_1m=0 fcmt_5m=0 fcmt_30m=0 fcmt_2h=0 fcmt_more=0 " +
                    "stall_30m=0 stall_2h=0 stall_more=0 stall_min=0 " +
                    "pv=1 pv_why=L,P pv_days=4 pv_n=120 pv_fcm_n=40 pv_fcm_slow=25 " +
                    "pv_fcmt_n=0 pv_fcmt_slow=na pv_stall_2h=0 fcm_state=fail fcm_fail_h=30" +
                    " adb=na" + zeroPower
            )
        )
        assertEquals(true, line.contains(" keep_alive=0 "))
    }

    @Test
    fun `fcmStateName passes known states and maps the rest to unknown`() {
        assertEquals("ok", KeepAliveHealthModel.fcmStateName("ok"))
        assertEquals("fail", KeepAliveHealthModel.fcmStateName("fail"))
        assertEquals("nogms", KeepAliveHealthModel.fcmStateName("nogms"))
        assertEquals("unknown", KeepAliveHealthModel.fcmStateName(null))
        assertEquals("unknown", KeepAliveHealthModel.fcmStateName("weird"))
    }

    @Test
    fun `latency keys are per channel, bounds are inclusive and negatives clamp (T3)`() {
        val ws = DeliveryChannel.WS
        assertEquals("lat_ws_10s", KeepAliveHealthModel.latencyKey(ws, -5_000L))
        assertEquals("lat_ws_10s", KeepAliveHealthModel.latencyKey(ws, 10_000L))
        assertEquals("lat_ws_1m", KeepAliveHealthModel.latencyKey(ws, 10_001L))
        assertEquals("lat_fcm_30m", KeepAliveHealthModel.latencyKey(DeliveryChannel.FCM, 30 * 60_000L))
        assertEquals("lat_fcm_2h", KeepAliveHealthModel.latencyKey(DeliveryChannel.FCM, 30 * 60_000L + 1))
        assertEquals("lat_pull_2h", KeepAliveHealthModel.latencyKey(DeliveryChannel.PULL, 2 * 60 * 60_000L))
        assertEquals("lat_pull_more", KeepAliveHealthModel.latencyKey(DeliveryChannel.PULL, 2 * 60 * 60_000L + 1))
        assertEquals(18, HealthEventKeys.ALL_LATENCY_KEYS.size)
        assertEquals(18, HealthEventKeys.ALL_LATENCY_KEYS.toSet().size)
    }

    @Test
    fun `fcm transit keys share the latency bounds and stall keys bucket at 30m and 2h`() {
        assertEquals("fcmt_10s", KeepAliveHealthModel.fcmTransitKey(-5_000L))
        assertEquals("fcmt_1m", KeepAliveHealthModel.fcmTransitKey(10_001L))
        assertEquals("fcmt_2h", KeepAliveHealthModel.fcmTransitKey(2 * 60 * 60_000L))
        assertEquals("fcmt_more", KeepAliveHealthModel.fcmTransitKey(2 * 60 * 60_000L + 1))
        assertEquals(
            listOf("fcmt_10s", "fcmt_1m", "fcmt_5m", "fcmt_30m", "fcmt_2h", "fcmt_more"),
            HealthEventKeys.ALL_FCM_TRANSIT_KEYS
        )
        assertEquals("stall_30m", KeepAliveHealthModel.stallKey(30 * 60_000L))
        assertEquals("stall_2h", KeepAliveHealthModel.stallKey(30 * 60_000L + 1))
        assertEquals("stall_2h", KeepAliveHealthModel.stallKey(2 * 60 * 60_000L))
        assertEquals("stall_more", KeepAliveHealthModel.stallKey(2 * 60 * 60_000L + 1))
    }

    @Test
    fun `formatReport renders transit and stall fields after latency and their aggregates after pv_fcm_slow`() {
        val snapshot = HealthSnapshot(34, true, true, false, 1, true, true, 10)
        val line = KeepAliveHealthModel.formatReport(
            snapshot, day, emptyMap(), emptyMap(),
            mapOf("fcmt_more" to 2, "fcmt_10s" to 6, "stall_more" to 1, "stall_30m" to 3, "stall_min" to 250),
            verdict = HealthVerdict("0", emptyList(), 5, 0, 0, null, 8, 25, 1),
            fcmState = "ok"
        )
        assertEquals(
            true,
            line.endsWith(
                " lat_pull_2h=0 lat_pull_more=0 " +
                    "fcmt_10s=6 fcmt_1m=0 fcmt_5m=0 fcmt_30m=0 fcmt_2h=0 fcmt_more=2 " +
                    "stall_30m=3 stall_2h=0 stall_more=1 stall_min=250 " +
                    "pv=0 pv_why=none pv_days=5 pv_n=0 pv_fcm_n=0 pv_fcm_slow=na " +
                    "pv_fcmt_n=8 pv_fcmt_slow=25 pv_stall_2h=1 fcm_state=ok fcm_fail_h=0" +
                    " adb=na" + zeroPower
            ),
            line
        )
    }

    @Test
    fun `isEligible covers keep-alive users and the control sample only`() {
        assertEquals(20, KeepAliveHealthModel.CONTROL_SAMPLE_PERCENT)
        assertEquals(true, KeepAliveHealthModel.isEligible(true, 99))
        assertEquals(true, KeepAliveHealthModel.isEligible(false, KeepAliveHealthModel.CONTROL_SAMPLE_PERCENT - 1))
        assertEquals(false, KeepAliveHealthModel.isEligible(false, KeepAliveHealthModel.CONTROL_SAMPLE_PERCENT))
    }
}
