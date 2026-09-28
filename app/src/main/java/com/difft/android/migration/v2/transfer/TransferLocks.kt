package com.difft.android.migration.v2.transfer

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.util.WakeLockUtil

/**
 * The CPU and Wi-Fi locks held for the life of a transfer. A foreground service keeps the process
 * alive but does not keep the CPU awake or stop Wi-Fi entering power save, so with the screen off a
 * multi-minute LAN transfer can stall or drop — and an interrupted transfer restarts from zero.
 * [release] is reached by every teardown path of the service.
 */
internal class TransferLocks(private val context: Context) {

    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Acquires the CPU and Wi-Fi locks for a transfer. Idempotent: a second call is a no-op. */
    fun acquire(wifi: Boolean = true) {
        if (cpuLock == null) {
            cpuLock = WakeLockUtil.acquire(context, PowerManager.PARTIAL_WAKE_LOCK, TRANSFER_LOCK_TIMEOUT_MS, WAKE_LOCK_TAG)
        }
        if (wifi && wifiLock == null) {
            wifiLock = runCatching {
                val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                // High-performance mode keeps the radio out of power save for a sustained LAN stream.
                @Suppress("DEPRECATION")
                manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WAKE_LOCK_TAG).apply { acquire() }
            }.onFailure { L.w { "[MigrationV2][P2P][FGS] wifi lock acquire failed: ${it.message}" } }.getOrNull()
        }
    }

    /** Releases both locks. Safe to call repeatedly and from any teardown path. */
    fun release() {
        WakeLockUtil.release(cpuLock, WAKE_LOCK_TAG)
        cpuLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
            .onFailure { L.w { "[MigrationV2][P2P][FGS] wifi lock release failed: ${it.message}" } }
        wifiLock = null
    }

    companion object {
        private const val WAKE_LOCK_TAG = "migration-p2p-transfer"

        /**
         * Safety timeout on the CPU lock: a lock leaked by a path that somehow skips the service's
         * teardown must not drain the battery for ever. Longer than any transfer is expected to run.
         */
        private const val TRANSFER_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
    }
}
