package com.difft.android.setting

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.text.TextUtils
import autodispose2.autoDispose
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.RxUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.UrlUtil
import com.kongzue.dialogx.dialogs.TipDialog
import io.reactivex.rxjava3.subjects.CompletableSubject
import java.io.File
import java.io.FileOutputStream

class AppUpgradeService : Service() {

    private val autoDisposeCompletable = CompletableSubject.create()

    override fun onBind(intent: Intent?): IBinder? {
        throw UnsupportedOperationException("Not yet implemented")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val url = it.getStringExtra(UpdateManager.INTENT_PARAM_APK_DOWNLOAD_URL)?:""
            val apkHash = it.getStringExtra(UpdateManager.INTENT_PARAM_APK_VERIFY_HASH)?:""
            val filepath = it.getStringExtra(UpdateManager.INTENT_PARAM_APK_STORE_PATH)?:""
            val isForce = it.getBooleanExtra(UpdateManager.INTENT_PARAM_APK_FORCE_UPGRADE, false)
            if(!TextUtils.isEmpty(url) && !TextUtils.isEmpty(filepath) &&!TextUtils.isEmpty(apkHash)){
                isDownloading = true
                downloadApkAndInstall(url, filepath, apkHash, isForce)
            }else{
                sendDownloadCompletedBroadcast(UpdateManager.STATUS_DOWNLOAD_FAILED, filepath, isForce)
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun downloadApkAndInstall(url: String, filepath: String, apkHash: String, isForce: Boolean = false){

        val newFile =File(filepath)
        ChativeHttpClient(this, UrlUtil.getBaseUrl(url)!!, null, false).httpService.getResponseBody(url, emptyMap(), emptyMap())
            .doOnSubscribe {
                TipDialog.show(R.string.status_upgrade_downloading)
            }
            .doOnNext { response ->
                FileOutputStream(filepath).use { outputStream ->
                    outputStream.write(response.bytes())
                }
            }
            .compose(RxUtil.getSchedulerComposer())
            .autoDispose(autoDisposeCompletable)
            .subscribe({
                if(UpdateManager.verifyApk(filepath, apkHash)){
                    L.i { "AppUpgradeService downloadApkAndInstall verifyApk success." }
                    sendDownloadCompletedBroadcast(UpdateManager.STATUS_DOWNLOAD_SUCCESS, filepath, isForce)
                }else{
                    L.i { "AppUpgradeService downloadApkAndInstall verifyApk error." }
                    sendDownloadCompletedBroadcast(UpdateManager.STATUS_VERIFY_FAILED, filepath, isForce)
                }
            }, {
                it.printStackTrace()
                if (newFile.exists()) {
                    newFile.delete()
                }
                L.i { "AppUpgradeService downloadApkAndInstall error:{${it.message}}" }
                sendDownloadCompletedBroadcast(UpdateManager.STATUS_DOWNLOAD_FAILED, filepath, isForce)
            })
    }

    private fun sendDownloadCompletedBroadcast(result: Int, filepath: String, isForce: Boolean = false) {
        L.i { "AppUpgradeService sendDownloadCompletedBroadcast action:${UpdateManager.ACTION_APK_DOWNLOAD_COMPLETED}" }
        val intent = Intent(UpdateManager.ACTION_APK_DOWNLOAD_COMPLETED)
        intent.setPackage(ApplicationHelper.instance.packageName)
        intent.putExtra(UpdateManager.INTENT_PARAM_APK_DOWNLOAD_STATUS, result)
        intent.putExtra(UpdateManager.INTENT_PARAM_APK_STORE_PATH, filepath)
        intent.putExtra(UpdateManager.INTENT_PARAM_APK_FORCE_UPGRADE, isForce)
        this.sendBroadcast(intent)
        isDownloading = false
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isDownloading = false
    }

    companion object{
        @Volatile
        var isDownloading: Boolean = false
    }

}