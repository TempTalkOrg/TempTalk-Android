package com.difft.android.setting

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.widget.ToastUtil

/**
 * Tap target of the upgrade-failed notification. Routes the retry through
 * [AppUpgradeService.start], whose channel preflight must run before the service starts (#1354).
 * The notification tap exempts the app from background-start limits for this delivery.
 */
class AppUpgradeRetryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val started = AppUpgradeService.start(
            context,
            url = intent.getStringExtra(AppUpgradeService.EXTRA_URL).orEmpty(),
            apkHash = intent.getStringExtra(AppUpgradeService.EXTRA_HASH).orEmpty(),
            filePath = intent.getStringExtra(AppUpgradeService.EXTRA_PATH).orEmpty(),
            isForce = intent.getBooleanExtra(AppUpgradeService.EXTRA_FORCE, false),
            asForeground = true,
        )
        if (!started) {
            L.w { "[AppUpgradeService] retry not started" }
            ToastUtil.showLong(R.string.status_upgrade_downolad_failed)
        }
    }

    companion object {
        /** Explicit broadcast carrying the retry params; the receiver is not exported. */
        fun intent(context: Context, url: String, apkHash: String, filePath: String, isForce: Boolean): Intent =
            Intent(context, AppUpgradeRetryReceiver::class.java)
                .putExtra(AppUpgradeService.EXTRA_URL, url)
                .putExtra(AppUpgradeService.EXTRA_HASH, apkHash)
                .putExtra(AppUpgradeService.EXTRA_PATH, filePath)
                .putExtra(AppUpgradeService.EXTRA_FORCE, isForce)
    }
}
