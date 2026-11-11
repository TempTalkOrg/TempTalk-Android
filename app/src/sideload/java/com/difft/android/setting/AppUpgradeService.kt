package com.difft.android.setting

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.TextUtils
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationChannelSpecs
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.ForegroundServiceStarter
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import com.difft.android.base.widget.ToastUtil




class AppUpgradeService : Service() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ServiceEntryPoint {
        @ChativeHttpClientModule.NoHeader
        fun noHeaderHttpClient(): ChativeHttpClient
        fun appUpgradeNotifier(): AppUpgradeNotifier
    }

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, ServiceEntryPoint::class.java)
    }

    private val notifier by lazy { entryPoint.appUpgradeNotifier() }

    private var downloadJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? {
        throw UnsupportedOperationException("Not yet implemented")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_REDELIVER_INTENT

        val url = intent.getStringExtra(EXTRA_URL) ?: ""
        val apkHash = intent.getStringExtra(EXTRA_HASH) ?: ""
        val filepath = intent.getStringExtra(EXTRA_PATH) ?: ""
        val isForce = intent.getBooleanExtra(EXTRA_FORCE, false)

        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(filepath) || TextUtils.isEmpty(apkHash)) {
            sendDownloadCompletedBroadcast(STATUS_DOWNLOAD_FAILED, filepath, isForce)
            return START_REDELIVER_INTENT
        }

        isDownloading = true

        
        
        
        val channelId = NotificationChannelSpecs.serviceForegroundChannel(this, SERVICE_TAG)
        if (channelId == null) {
            sendDownloadCompletedBroadcast(STATUS_DOWNLOAD_FAILED, filepath, isForce)
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        val started = ForegroundServiceStarter.startForegroundSafely(
            this, AppUpgradeNotifier.NOTIFICATION_ID, notifier.buildProgress(null, channelId), type
        )
        if (!started) {
            
            
            
            L.w { "[AppUpgradeService] startForeground rejected, abort download isForce=$isForce" }
            sendDownloadCompletedBroadcast(STATUS_DOWNLOAD_FAILED, filepath, isForce)
            return START_NOT_STICKY
        }

        
        notifier.cancelTerminal()

        downloadApkAndInstall(url, filepath, apkHash, isForce)
        return START_REDELIVER_INTENT
    }

    private fun removeForegroundNotification() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun downloadApkAndInstall(url: String, filepath: String, apkHash: String, isForce: Boolean = false){
        
        
        val previousJob = downloadJob

        ToastUtil.showLong(R.string.status_upgrade_downloading)

        val newFile = File(filepath)

        downloadJob = appScope.launch {
            previousJob?.cancelAndJoin()
            
            
            fun handleFailure(status: Int) {
                if (newFile.exists()) {
                    newFile.delete()
                }
                removeForegroundNotification()
                notifier.showFailed(url, apkHash, filepath, isForce)
                sendDownloadCompletedBroadcast(status, filepath, isForce)
            }

            try {
                notifier.showProgress(0)

                
                withContext(Dispatchers.IO) {
                    entryPoint.noHeaderHttpClient().httpService.getResponseBodyStreaming(url, emptyMap(), emptyMap())
                        .use { responseBody ->
                            saveResponseToFile(responseBody, filepath) { percent ->
                                notifier.showProgress(percent)
                            }
                        }
                }

                
                
                notifier.showVerifying()
                val isValid = withContext(Dispatchers.IO) {
                    ApkUpdateFiles.verifyApk(applicationContext, filepath, apkHash)
                }

                
                if (isValid) {
                    L.i { "[AppUpgradeService] verifyApk success" }
                    removeForegroundNotification()
                    notifier.showComplete(newFile)
                    sendDownloadCompletedBroadcast(STATUS_DOWNLOAD_SUCCESS, filepath, isForce)
                } else {
                    L.i { "[AppUpgradeService] verifyApk failed" }
                    
                    
                    handleFailure(STATUS_VERIFY_FAILED)
                }
            } catch (error: Exception) {
                if (error is CancellationException) {
                    L.i { "[AppUpgradeService] download cancelled" }
                    throw error 
                }

                L.w { "[AppUpgradeService] download error: ${error.stackTraceToString()}" }
                handleFailure(STATUS_DOWNLOAD_FAILED)
            }
        }
    }

    






    private suspend fun saveResponseToFile(
        response: ResponseBody,
        filepath: String,
        onProgress: (Int?) -> Unit
    ) {
        val total = response.contentLength()
        if (total <= 0) onProgress(null)
        val throttle = ProgressThrottle()
        var downloaded = 0L
        response.byteStream().use { inputStream ->
            FileOutputStream(filepath).use { outputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    coroutineContext.ensureActive()
                    outputStream.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    if (total > 0) {
                        val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                        if (throttle.shouldEmit(percent, System.currentTimeMillis())) {
                            onProgress(percent)
                        }
                    }
                }
                outputStream.flush()
            }
        }
    }

    private fun sendDownloadCompletedBroadcast(result: Int, filepath: String, isForce: Boolean = false) {
        L.i { "AppUpgradeService sendDownloadCompletedBroadcast action:${ACTION_DOWNLOAD_COMPLETED}" }
        val intent = Intent(ACTION_DOWNLOAD_COMPLETED)
        intent.setPackage(ApplicationHelper.instance.packageName)
        intent.putExtra(EXTRA_STATUS, result)
        intent.putExtra(EXTRA_PATH, filepath)
        intent.putExtra(EXTRA_FORCE, isForce)
        this.sendBroadcast(intent)
        isDownloading = false
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadJob?.cancel()
        downloadJob = null
        isDownloading = false
        
        notifier.cancel()
    }

    
    companion object {
        @Volatile
        var isDownloading: Boolean = false

        const val EXTRA_URL = "apk_download_url"
        const val EXTRA_HASH = "apk_verify_hash"
        const val EXTRA_PATH = "apk_store_path"
        const val EXTRA_FORCE = "apk_force_upgrade"
        const val EXTRA_STATUS = "apk_download_status"

        const val STATUS_DOWNLOAD_SUCCESS = 1
        const val STATUS_DOWNLOAD_FAILED = 0
        const val STATUS_VERIFY_FAILED = -1

        val ACTION_DOWNLOAD_COMPLETED: String by lazy { "${application.packageName}.APK_DOWNLOAD_COMPLETED" }

        private const val SERVICE_TAG = "AppUpgradeService"

        





        fun start(
            context: Context,
            url: String,
            apkHash: String,
            filePath: String,
            isForce: Boolean,
            asForeground: Boolean = false,
        ): Boolean {
            if (NotificationChannelSpecs.preflightForegroundChannel(context, SERVICE_TAG) == null) return false
            val intent = startIntent(context, url, apkHash, filePath, isForce)
            return try {
                if (asForeground) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
                true
            } catch (e: Exception) {
                L.e { "[AppUpgradeService] start failed foreground=$asForeground error=${e.stackTraceToString()}" }
                false
            }
        }

        fun startIntent(context: Context, url: String, apkHash: String, filePath: String, isForce: Boolean): Intent =
            Intent(context, AppUpgradeService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_HASH, apkHash)
                .putExtra(EXTRA_PATH, filePath)
                .putExtra(EXTRA_FORCE, isForce)
    }

}
