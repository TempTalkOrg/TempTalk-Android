package com.difft.android.setting

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.TextUtils
import androidx.core.app.ServiceCompat
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.ForegroundServiceStarter
import com.difft.android.base.utils.appScope
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

/**
 * APK download service for app upgrades.
 */
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

        val url = intent.getStringExtra(UpdateManager.INTENT_PARAM_APK_DOWNLOAD_URL) ?: ""
        val apkHash = intent.getStringExtra(UpdateManager.INTENT_PARAM_APK_VERIFY_HASH) ?: ""
        val filepath = intent.getStringExtra(UpdateManager.INTENT_PARAM_APK_STORE_PATH) ?: ""
        val isForce = intent.getBooleanExtra(UpdateManager.INTENT_PARAM_APK_FORCE_UPGRADE, false)

        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(filepath) || TextUtils.isEmpty(apkHash)) {
            sendDownloadCompletedBroadcast(UpdateManager.STATUS_DOWNLOAD_FAILED, filepath, isForce)
            return START_REDELIVER_INTENT
        }

        isDownloading = true

        // Promote to a foreground service so the download survives OEM background kill (retry is
        // typically triggered from background). The retry PendingIntent uses getForegroundService,
        // which grants the background-start exemption. startForegroundSafely absorbs FGSNAE.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        val started = ForegroundServiceStarter.startForegroundSafely(
            this, AppUpgradeNotifier.NOTIFICATION_ID, notifier.buildProgress(null), type
        )
        if (!started) {
            // Signal failure like every other path: the broadcast reaches the in-app UI and also
            // resets isDownloading + stops the service. The stale failed notification (not yet
            // cancelled here) keeps its retry intent as the recovery entry point.
            L.w { "[AppUpgradeService] startForeground rejected, abort download isForce=$isForce" }
            sendDownloadCompletedBroadcast(UpdateManager.STATUS_DOWNLOAD_FAILED, filepath, isForce)
            return START_NOT_STICKY
        }

        // Retry started: clear any stale terminal (failed/complete) notification.
        notifier.cancelTerminal()

        downloadApkAndInstall(url, filepath, apkHash, isForce)
        return START_REDELIVER_INTENT
    }

    private fun removeForegroundNotification() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun downloadApkAndInstall(url: String, filepath: String, apkHash: String, isForce: Boolean = false){
        // cancel() alone is async: the old writer can stay mid-write for as long as its in-flight
        // blocking read() takes, so the new job must join it before touching the same file.
        val previousJob = downloadJob

        ToastUtil.showLong(R.string.status_upgrade_downloading)

        val newFile = File(filepath)

        downloadJob = appScope.launch {
            previousJob?.cancelAndJoin()
            // Shared terminal-failure sequence (verify-failed and download-error paths only
            // differ in the broadcast status code); captures the enclosing call's params.
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

                // 1. Stream the APK to disk chunk by chunk: real progress, no full-body buffering
                withContext(Dispatchers.IO) {
                    entryPoint.noHeaderHttpClient().httpService.getResponseBodyStreaming(url, emptyMap(), emptyMap())
                        .use { responseBody ->
                            saveResponseToFile(responseBody, filepath) { percent ->
                                notifier.showProgress(percent)
                            }
                        }
                }

                // 2. Switch to "verifying" state: streamed SHA256 + signature check over a large APK
                //    takes seconds; a progress bar stuck at 100% would look frozen
                notifier.showVerifying()
                val isValid = withContext(Dispatchers.IO) {
                    UpdateManager.verifyApk(applicationContext, filepath, apkHash)
                }

                // 3. Terminal notifications use their own id, so remove the FGS progress notification first
                if (isValid) {
                    L.i { "[AppUpgradeService] verifyApk success" }
                    removeForegroundNotification()
                    notifier.showComplete(newFile)
                    sendDownloadCompletedBroadcast(UpdateManager.STATUS_DOWNLOAD_SUCCESS, filepath, isForce)
                } else {
                    L.i { "[AppUpgradeService] verifyApk failed" }
                    // Delete the bad file (hash mismatch or signature not whitelisted) so it is never
                    // reused and a compromised server can't trigger an endless re-download loop.
                    handleFailure(UpdateManager.STATUS_VERIFY_FAILED)
                }
            } catch (error: Exception) {
                if (error is CancellationException) {
                    L.i { "[AppUpgradeService] download cancelled" }
                    throw error // preserve coroutine cancellation semantics
                }

                L.w { "[AppUpgradeService] download error: ${error.stackTraceToString()}" }
                handleFailure(UpdateManager.STATUS_DOWNLOAD_FAILED)
            }
        }
    }

    /**
     * Streams the response body to disk. The copy loop is blocking IO with no suspension point,
     * so ensureActive() every iteration is the only thing that makes downloadJob.cancel() actually
     * stop the old writer — without it a quick retry can race two coroutines onto the same file.
     * total <= 0 (no Content-Length) reports null progress (indeterminate). Caller owns closing
     * the ResponseBody.
     */
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
        downloadJob?.cancel()
        downloadJob = null
        isDownloading = false
        // Clear the progress (FGS) notification only; a terminal one lives under its own id.
        notifier.cancel()
    }

    companion object{
        @Volatile
        var isDownloading: Boolean = false
    }

}
