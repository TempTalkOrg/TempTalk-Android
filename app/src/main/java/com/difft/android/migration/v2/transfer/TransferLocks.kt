package com.difft.android.migration.v2.transfer

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.util.WakeLockUtil







internal class TransferLocks(private val context: Context) {

    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    
    fun acquire(wifi: Boolean = true) {
        if (cpuLock == null) {
            cpuLock = WakeLockUtil.acquire(context, PowerManager.PARTIAL_WAKE_LOCK, TRANSFER_LOCK_TIMEOUT_MS, WAKE_LOCK_TAG)
        }
        if (wifi && wifiLock == null) {
            wifiLock = runCatching {
                val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                
                @Suppress("DEPRECATION")
                manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WAKE_LOCK_TAG).apply { acquire() }
            }.onFailure { L.w { "[MigrationV2][P2P][FGS] wifi lock acquire failed: ${it.message}" } }.getOrNull()
        }
    }

    
    fun release() {
        WakeLockUtil.release(cpuLock, WAKE_LOCK_TAG)
        cpuLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
            .onFailure { L.w { "[MigrationV2][P2P][FGS] wifi lock release failed: ${it.message}" } }
        wifiLock = null
    }

    companion object {
        private const val WAKE_LOCK_TAG = "migration-p2p-transfer"

        



        private const val TRANSFER_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
    }
}
