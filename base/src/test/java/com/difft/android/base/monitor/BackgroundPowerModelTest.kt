package com.difft.android.base.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1376: [BackgroundPowerModel] attributes background intervals by charging state and transport. */
class BackgroundPowerModelTest {

    private fun sample(
        elapsedS: Long,
        uptimeS: Long,
        cpuMs: Long = 0L,
        foreground: Boolean = false,
        charging: Boolean? = false,
        transport: PowerTransport = PowerTransport.CELLULAR,
        bytes: Long? = 0L,
        uah: Long? = 3_000_000L,
        pct: Int? = 80,
    ) = PowerSample(elapsedS * 1000, uptimeS * 1000, cpuMs, foreground, charging, transport, bytes, uah, pct)

    @Test
    fun `on-battery background interval yields awake share cpu traffic and battery drop`() {
        val delta = BackgroundPowerModel.carry(
            HashMap(),
            BackgroundPowerModel.delta(
                sample(elapsedS = 0, uptimeS = 0),
                sample(elapsedS = 600, uptimeS = 30, cpuMs = 1_500, bytes = 20_480, uah = 2_990_000, pct = 79),
            )
        )

        assertEquals(
            mapOf(
                "pw_el_s_bat" to 600, "pw_up_s_bat" to 30, "pw_net_cell_s" to 600, "pw_cpu_ms_bat" to 1_500,
                "pw_net_kb_bat" to 20, "pw_batt_mah" to 10, "pw_batt_pct" to 1,
            ),
            delta
        )
        assertTrue(BackgroundPowerModel.formatSection(delta).startsWith(" pw_awake_bat=5 "))
    }

    @Test
    fun `charging interval only counts time and transport`() {
        val delta = BackgroundPowerModel.delta(
            sample(elapsedS = 0, uptimeS = 0, charging = true, transport = PowerTransport.WIFI),
            sample(elapsedS = 300, uptimeS = 300, cpuMs = 9_000, charging = true, transport = PowerTransport.WIFI, uah = 3_100_000),
        )

        assertEquals(mapOf("pw_el_s_chg" to 300_000L, "pw_up_s_chg" to 300_000L, "pw_net_wifi_s" to 300_000L), delta)
    }

    @Test
    fun `foreground endpoints and implausible intervals are not attributed`() {
        assertEquals(emptyMap<String, Long>(), BackgroundPowerModel.delta(sample(0, 0, foreground = true), sample(60, 60)))
        assertEquals(emptyMap<String, Long>(), BackgroundPowerModel.delta(sample(0, 0), sample(60, 60, foreground = true)))
        assertEquals(emptyMap<String, Long>(), BackgroundPowerModel.delta(sample(0, 0), sample(31 * 60, 10)))
        assertEquals(emptyMap<String, Long>(), BackgroundPowerModel.delta(sample(60, 60), sample(0, 0)))
    }

    @Test
    fun `power keys are recognised for the daily-slot filter`() {
        BackgroundPowerModel.REPORT_KEYS.forEach { assertTrue(it, BackgroundPowerModel.isPowerKey(it)) }
        assertEquals(false, BackgroundPowerModel.isPowerKey(HealthEventKeys.FCM_RECV))
    }

    @Test
    fun `queue wait is bucketed at 2 s and 10 s inclusive`() {
        assertEquals(PowerEvent.QUEUE_WAIT_2S, BackgroundPowerModel.queueWaitEvent(2_000))
        assertEquals(PowerEvent.QUEUE_WAIT_10S, BackgroundPowerModel.queueWaitEvent(2_001))
        assertEquals(PowerEvent.QUEUE_WAIT_10S, BackgroundPowerModel.queueWaitEvent(10_000))
        assertEquals(PowerEvent.QUEUE_WAIT_MORE, BackgroundPowerModel.queueWaitEvent(50_000))
    }

    @Test
    fun `sub-unit amounts carry over instead of truncating to zero`() {
        val residue = HashMap<String, Long>()
        val total = HashMap<String, Int>()
        repeat(60) { i ->
            val prev = sample(elapsedS = i * 60L, uptimeS = i * 1L, bytes = i * 512L, uah = 3_000_000L - i * 500L)
            val now = sample(elapsedS = (i + 1) * 60L, uptimeS = i + 1L, bytes = (i + 1) * 512L, uah = 3_000_000L - (i + 1) * 500L)
            BackgroundPowerModel.carry(residue, BackgroundPowerModel.delta(prev, now)).forEach { (k, v) -> total[k] = (total[k] ?: 0) + v }
        }

        assertEquals(30, total["pw_batt_mah"])
        assertEquals(30, total["pw_net_kb_bat"])
        assertEquals(60, total["pw_up_s_bat"])
        assertEquals(3_600, total["pw_el_s_bat"])
    }
}
