package com.difft.android.setting

import android.app.Service
import android.content.Context
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

        val url = intent.getStringExtra(EXTRA_URL) ?: ""
        val apkHash = intent.getStringExtra(EXTRA_HASH) ?: ""
        val filepath = intent.getStringExtra(EXTRA_PATH) ?: ""
        val isForce = intent.getBooleanExtra(EXTRA_FORCE, false)

        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(filepath) || TextUtils.isEmpty(apkHash)) {
            sendDownloadCompletedBroadcast(STATUS_DOWNLOAD_FAILED, filepath, isForce)
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
            sendDownloadCompletedBroadcast(STATUS_DOWNLOAD_FAILED, filepath, isForce)
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
                    ApkUpdateFiles.verifyApk(applicationContext, filepath, apkHash)
                }

                // 3. Terminal notifications use their own id, so remove the FGS progress notification first
                if (isValid) {
                    L.i { "[AppUpgradeService] verifyApk success" }
                    removeForegroundNotification()
                    notifier.showComplete(newFile)
                    sendDownloadCompletedBroadcast(STATUS_DOWNLOAD_SUCCESS, filepath, isForce)
                } else {
                    L.i { "[AppUpgradeService] verifyApk failed" }
                    // Delete the bad file (hash mismatch or signature not whitelisted) so it is never
                    // reused and a compromised server can't trigger an endless re-download loop.
                    handleFailure(STATUS_VERIFY_FAILED)
                }
            } catch (error: Exception) {
                if (error is CancellationException) {
                    L.i { "[AppUpgradeService] download cancelled" }
                    throw error // preserve coroutine cancellation semantics
                }

                L.w { "[AppUpgradeService] download error: ${error.stackTraceToString()}" }
                handleFailure(STATUS_DOWNLOAD_FAILED)
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
        // Clear the progress (FGS) notification only; a terminal one lives under its own id.
        notifier.cancel()
    }

    /** Owner of the intent contract shared by the start/retry intents and the completion broadcast. */
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

        fun startIntent(context: Context, url: String, apkHash: String, filePath: String, isForce: Boolean): Intent =
            Intent(context, AppUpgradeService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_HASH, apkHash)
                .putExtra(EXTRA_PATH, filePath)
                .putExtra(EXTRA_FORCE, isForce)
    }

}
