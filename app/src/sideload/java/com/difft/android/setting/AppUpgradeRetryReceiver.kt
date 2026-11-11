package com.difft.android.setting

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.widget.ToastUtil






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
        
        fun intent(context: Context, url: String, apkHash: String, filePath: String, isForce: Boolean): Intent =
            Intent(context, AppUpgradeRetryReceiver::class.java)
                .putExtra(AppUpgradeService.EXTRA_URL, url)
                .putExtra(AppUpgradeService.EXTRA_HASH, apkHash)
                .putExtra(AppUpgradeService.EXTRA_PATH, filePath)
                .putExtra(AppUpgradeService.EXTRA_FORCE, isForce)
    }
}
