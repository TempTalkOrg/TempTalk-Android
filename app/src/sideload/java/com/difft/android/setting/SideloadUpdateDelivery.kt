package com.difft.android.setting

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.TextUtils
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.FilePathManager
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.base.utils.appScope
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.security.SecurityLib
import com.difft.android.websocket.api.crypto.CryptoUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject






class SideloadUpdateDelivery @Inject constructor() : UpdateDelivery {

    private var installMessageDialog: ComposeDialog? = null

    override val isInProgress: Boolean
        get() = AppUpgradeService.isDownloading

    override fun deliver(context: Context, offer: UpdateOffer, isForce: Boolean, onForceHandOff: () -> Unit) {
        if (!TextUtils.isEmpty(offer.url) && !TextUtils.isEmpty(offer.apkHash)) {
            upgradeApk(context, offer.url, offer.apkHash, isForce, onForceHandOff)
        } else {
            ToastUtil.showLong(getString(R.string.status_upgrade_param_exception))
        }
    }

    override fun attach(activity: FragmentActivity, onForceHandOff: () -> Unit) {
        val receiver = downloadResultReceiver(activity, onForceHandOff)
        registerReceiver(activity, receiver)
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                activity.unregisterReceiver(receiver)
            }
        })
    }

    @SuppressLint("WrongConstant")
    private fun registerReceiver(activity: FragmentActivity, receiver: BroadcastReceiver) {
        val filter = IntentFilter()
        filter.addAction(AppUpgradeService.ACTION_DOWNLOAD_COMPLETED)
        filter.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun downloadResultReceiver(activity: FragmentActivity, onForceHandOff: () -> Unit) =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let {
                    if (it.`package` == ApplicationHelper.instance.packageName) {
                        if (AppUpgradeService.ACTION_DOWNLOAD_COMPLETED == intent.action) {
                            val status = intent.getIntExtra(AppUpgradeService.EXTRA_STATUS, -1)
                            val path = intent.getStringExtra(AppUpgradeService.EXTRA_PATH) ?: ""
                            val isForce = intent.getBooleanExtra(AppUpgradeService.EXTRA_FORCE, false)
                            handleApkDownloadStatus(activity, status, path, isForce, onForceHandOff)
                        }
                    }
                }
            }
        }

    private fun handleApkDownloadStatus(
        activity: FragmentActivity,
        status: Int,
        apkFilePath: String,
        isForce: Boolean,
        onForceHandOff: () -> Unit
    ) {
        when (status) {
            AppUpgradeService.STATUS_DOWNLOAD_SUCCESS -> {
                if (!TextUtils.isEmpty(apkFilePath)) {
                    val file = File(apkFilePath)
                    if (isForce) {
                        onForceHandOff()
                        showInstallDialog(activity, file, true)
                    } else {
                        showInstallDialog(activity, file, false)
                    }
                } else {
                    ToastUtil.showLong(getString(R.string.status_upgrade_install_failed))
                }
            }

            AppUpgradeService.STATUS_DOWNLOAD_FAILED -> {
                if (!TextUtils.isEmpty(apkFilePath) && File(apkFilePath).exists()) {
                    File(apkFilePath).delete()
                }
                ToastUtil.showLong(getString(R.string.status_upgrade_downolad_failed))
            }

            AppUpgradeService.STATUS_VERIFY_FAILED -> {
                if (!TextUtils.isEmpty(apkFilePath) && File(apkFilePath).exists()) {
                    File(apkFilePath).delete()
                }
                ToastUtil.showLong(getString(R.string.status_upgrade_verify_failed))
            }
        }
    }

    private fun showInstallDialog(context: Context, apkFile: File, isForce: Boolean) {
        installMessageDialog?.dismiss()
        installMessageDialog = null

        installMessageDialog = ComposeDialogManager.showMessageDialog(
            context = context,
            title = context.getString(R.string.status_upgrade_title),
            message = context.getString(R.string.status_upgrade_download_success),
            confirmText = context.getString(R.string.status_upgrade_button_install),
            cancelText = if (isForce) "" else context.getString(R.string.status_upgrade_button_cancle),
            showCancel = !isForce,
            cancelable = !isForce,
            onConfirm = {
                appScope.launch {
                    val errorRes = withContext(Dispatchers.IO) {
                        if (!apkFile.exists()) {
                            L.e { "APK file does not exist: ${apkFile.absolutePath}" }
                            R.string.status_upgrade_install_failed
                        } else if (!SecurityLib.checkApkFileSign(context, apkFile.absolutePath, context.packageName)) {
                            L.e { "[UpdateManager] install rejected: apk signature not in whitelist." }
                            apkFile.delete()
                            R.string.status_upgrade_verify_failed
                        } else {
                            0
                        }
                    }
                    withContext(Dispatchers.Main) {
                        if (errorRes != 0) {
                            ToastUtil.show(errorRes)
                            return@withContext
                        }
                        try {
                            val uri = ApkUpdateFiles.contentUri(context, apkFile)
                            ApkUpdateFiles.installApk(context, uri)
                        } catch (e: IllegalArgumentException) {
                            L.e { "FileProvider failed to find configured root for: ${apkFile.absolutePath}" }
                            L.e { "Error: ${e.message}" }
                            ToastUtil.show(R.string.status_upgrade_install_failed)
                        }
                    }
                }
            }
        )
    }

    private fun upgradeApk(context: Context, url: String, apkHash: String, isForce: Boolean, onForceHandOff: () -> Unit) {
        val fileDir = FileUtil.getFilePath(FileUtil.FILE_DIR_UPGRADE)

        val filename = url.toUri().lastPathSegment?.let {
            if (it.endsWith(".apk")) it else null
        } ?: "${CryptoUtil.bytesToHex(CryptoUtil.sha256(url.toByteArray(Charsets.UTF_8)))}.apk"

        
        if (filename.contains("../")) {
            ToastUtil.showLong(getString(R.string.status_upgrade_filename_error))
            L.i { "UpdateManager filename Verify error:${filename}" }
            return
        }

        
        if (installMessageDialog == null) {
            clearDownloadedApk(fileDir, filename)
        }
        appScope.launch(Dispatchers.IO) { clearLegacyExternalApks() }

        val file = File(fileDir, filename)
        val filePath = file.absolutePath
        
        if (file.exists()) {
            
            appScope.launch {
                try {

                    val isValid = withContext(Dispatchers.IO) {
                        ApkUpdateFiles.verifyApk(context, filePath, apkHash)
                    }

                    withContext(Dispatchers.Main) {
                        if (isValid) {
                            L.i { "UpdateManager upgradeApk install downloaded apk package." }
                            if (isForce) {
                                onForceHandOff()
                                showInstallDialog(context, file, true)
                            } else {
                                showInstallDialog(context, file, false)
                            }
                        } else {
                            
                            L.i { "UpdateManager upgradeApk verify failed, invoke service download and install." }
                            
                            if (file.exists()) {
                                file.delete()
                            }
                            startDownloadService(context, url, apkHash, filePath, isForce)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Exception) {
                    L.e { "UpdateManager upgradeApk verify error: ${error.message}" }
                    
                    withContext(Dispatchers.Main) {
                        startDownloadService(context, url, apkHash, filePath, isForce)
                    }
                }
            }
        } else {
            
            L.i { "UpdateManager upgradeApk invoke service download and install." }
            startDownloadService(context, url, apkHash, filePath, isForce)
        }
    }

    private fun startDownloadService(context: Context, url: String, apkHash: String, filePath: String, isForce: Boolean) {
        if (!AppUpgradeService.start(context, url, apkHash, filePath, isForce)) {
            ToastUtil.showLong(getString(R.string.status_upgrade_downolad_failed))
        }
    }

    
    private fun clearLegacyExternalApks() {
        try {
            val legacyDir = File(FilePathManager.getBaseDirFile(), FileUtil.FILE_DIR_UPGRADE)
            if (legacyDir.canonicalPath == FilePathManager.upgradeDir.canonicalPath) return
            val deleted = legacyDir.listFiles().orEmpty().count { it.isFile && it.name.endsWith(".apk") && it.delete() }
            if (deleted > 0) L.i { "[UpdateManager] legacy external apks deleted=$deleted" }
        } catch (e: Exception) {
            L.w { "[UpdateManager] legacy external apk cleanup failed: ${e.stackTraceToString()}" }
        }
    }

    private fun clearDownloadedApk(dirPath: String, excludeFileName: String) {
        val dir = File(dirPath)
        if (!dir.listFiles().isNullOrEmpty()) {
            dir.listFiles()?.forEach {
                if (!it.name.equals(excludeFileName)) it.delete()
            }
        }
    }
}
