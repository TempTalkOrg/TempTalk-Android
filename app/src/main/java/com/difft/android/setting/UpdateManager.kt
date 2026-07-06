package com.difft.android.setting

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.UrlManager
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.security.SecurityLib
import com.difft.android.setting.data.CheckUpdateResponse
import com.difft.android.setting.repo.SettingRepo
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ComposeDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import util.ScreenLockUtil
import com.difft.android.websocket.api.crypto.CryptoUtil
import java.io.File
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil

class UpdateManager @Inject constructor(
    private val settingRepo: SettingRepo,
    private val environmentHelper: EnvironmentHelper,
    @param:ChativeHttpClientModule.NoHeader
    private val globalConfigHttpClient: ChativeHttpClient,
    private val urlManager: UrlManager,
    private val userManager: UserManager
) {
    private var foreUpdateMessageDialog: ComposeDialog? = null

    private var installMessageDialog: ComposeDialog? = null


    fun checkUpdate(context: FragmentActivity, isFromSetting: Boolean) {
        if (isFromSetting) {
            ComposeDialogManager.showWait(context, "")
        }
        context.lifecycleScope.launch {
            try {
                if (environmentHelper.isInsiderChannel()) {
                    val configs = withContext(Dispatchers.IO) {
                        globalConfigHttpClient.httpService.getAppVersionConfigs(urlManager.appVersionConfigUrl)
                    }
                    L.d { "[UpdateManager] get App Version Configs success:$configs" }
                    if (isFromSetting) {
                        ComposeDialogManager.dismissWait()
                    }
                    if (configs.versionCode > PackageUtil.getAppVersionCode()) {
                        appInnerToInstall(context, configs.url, configs.hash)
                    } else {
                        if (isFromSetting) {
                            showLatestDialog(context)
                        }
                    }
                } else {
                    val result = withContext(Dispatchers.IO) {
                        settingRepo.checkUpdate((userManager.getUserData()?.microToken ?: ""), PackageUtil.getAppVersionName() ?: "")
                    }
                    if (isFromSetting) {
                        ComposeDialogManager.dismissWait()
                    }
                    if (result.status == 0) {
                        result.data?.let { response ->
                            if (response.update) {
                                if (response.force) {
                                    showForceUpdateDialog(context, response)
                                } else {
                                    showUpdateDialog(context, response)
                                }
                            } else {
                                if (isFromSetting) {
                                    showLatestDialog(context)
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[UpdateManager] checkUpdate error: ${e.stackTraceToString()}" }
                if (isFromSetting) {
                    ComposeDialogManager.dismissWait()
                    e.message?.let { message -> ToastUtil.show(message) }
                }
            }
        }
        userManager.update {
            this.lastCheckUpdateTime = System.currentTimeMillis()
        }
    }

    private fun showLatestDialog(context: FragmentActivity) {
        context.lifecycleScope.launch {
            delay(500)
            ToastUtil.showLong(ResUtils.getString(R.string.settings_version_is_latest))
        }
    }

    private fun showUpdateDialog(context: Context, response: CheckUpdateResponse) {
        ComposeDialogManager.showMessageDialog(
            context = context,
            title = context.getString(R.string.settings_check_new_version),
            message = response.notes,
            confirmText = context.getString(R.string.settings_dialog_update),
            cancelText = context.getString(R.string.settings_dialog_cancel),
            onConfirm = {
                if (AppUpgradeService.isDownloading) {
                    ToastUtil.show(R.string.status_upgrade_downloading)
                } else {
                    update(context, response.url, response.updateWithApk, response.apkHash)
                }
            }
        )
    }


    private fun showForceUpdateDialog(context: FragmentActivity, response: CheckUpdateResponse) {
        foreUpdateMessageDialog = ComposeDialogManager.showMessageDialog(
            context = context,
            title = context.getString(R.string.settings_check_new_version),
            message = response.notes,
            confirmText = context.getString(R.string.settings_dialog_update),
            cancelable = false,
            showCancel = false,
            autoDismiss = false,
            onConfirm = {
                if (AppUpgradeService.isDownloading) {
                    ToastUtil.show(R.string.status_upgrade_downloading)
                } else {
                    update(context, response.url, response.updateWithApk, response.apkHash, true)
                }
            }
        )
    }

    fun showInstallDialog(context: Context, apkFile: File, isForce: Boolean) {
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
                            val authority: String = context.applicationContext.packageName + ".provider"
                            val uri = FileProvider.getUriForFile(context, authority, apkFile)
                            // 仅在 FileProvider 成功、真正调起系统安装器（会切到后台）前才豁免锁屏；
                            // 放在 getUriForFile 之后，避免它抛异常时误留豁免位导致一次锁屏被跳过。
                            ScreenLockUtil.temporarilyDisabled = true
                            installAPK(context, uri, apkFile)
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

    fun closeForceUpdateDialog() {
        foreUpdateMessageDialog?.dismiss()
        foreUpdateMessageDialog = null
    }

    private fun update(context: Context, url: String, updateWithApk: Boolean, apkHash: String, isForce: Boolean = false) {
        // 根据渠道判断是跳转市场还是进行下载逻辑，可以打包时配置在bulidconfig中
        if (updateWithApk) {
//            openBrowserToInstall(context, url)
            appInnerToInstall(context, url, apkHash, isForce)
        } else {
            if (environmentHelper.isGoogleChannel()) {
                goGooglePlay(context)
            } else {
                appInnerToInstall(context, url, apkHash, isForce)
//                openBrowserToInstall(context, url)
            }
        }
    }

//    private fun openBrowserToInstall(context: Context, url: String) {
//        if (!TextUtils.isEmpty(url)) {
//            context.openExternalBrowser(url)
//        }
//    }

    private fun appInnerToInstall(context: Context, url: String, apkHash: String, isForce: Boolean = false) {
        if (!TextUtils.isEmpty(url) && !TextUtils.isEmpty(apkHash)) {
            upgradeApk(context, url, apkHash, isForce)
        } else {
            ToastUtil.showLong(getString(R.string.status_upgrade_param_exception))
        }
    }

    /**
     * 如果googleplay没有则跳转到系统自带浏览器，打开googleplay
     */
    private fun goGooglePlay(context: Context) {
        val playPackage = "com.android.vending"
        try {
            PackageUtil.getAppVersionName()
            val currentPackageUri: Uri = ("market://details?id=" + context.packageName).toUri()
            val intent = Intent(Intent.ACTION_VIEW, currentPackageUri)
            intent.setPackage(playPackage)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            L.w { "[UpdateManager] openGooglePlay error: ${e.stackTraceToString()}" }
            val currentPackageUri: Uri = ("https://play.google.com/store/apps/details?id=" + context.packageName).toUri()
            val intent = Intent(Intent.ACTION_VIEW, currentPackageUri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun upgradeApk(context: Context, url: String, apkHash: String, isForce: Boolean = false) {
        val fileDir = FileUtil.getFilePath(FileUtil.FILE_DIR_UPGRADE)

        val filename = url.toUri().lastPathSegment?.let {
            if (it.endsWith(".apk")) it else null
        } ?: "${CryptoUtil.bytesToHex(CryptoUtil.sha256(url.toByteArray(Charsets.UTF_8)))}.apk"

        // Verify file names to avoid path traversal
        if (filename.contains("../")) {
            ToastUtil.showLong(getString(R.string.status_upgrade_filename_error))
            L.i { "UpdateManager filename Verify error:${filename}" }
            return
        }

        // Delete the old version apk file downloaded before
        if(installMessageDialog == null){
            clearDownloadedApk(fileDir, filename)
        }

        val file = File(fileDir, filename)
        val filePath = file.absolutePath
        // Determine whether the file exists and verify the file hash value
        if (file.exists()) {
            // 使用协程在后台线程执行 APK 验证，避免 ANR
            appScope.launch {
                try {
                    // 在 IO 线程执行验证
                    val isValid = withContext(Dispatchers.IO) {
                        verifyApk(context, filePath, apkHash)
                    }
                    
                    // 切换到主线程处理结果
                    withContext(Dispatchers.Main) {
                        if (isValid) {
                            L.i { "UpdateManager upgradeApk install downloaded apk package." }
                            if (isForce) {
                                closeForceUpdateDialog()
                                showInstallDialog(context, file, true)
                            } else {
                                showInstallDialog(context, file, false)
                            }
                        } else {
                            // If the hash verification code fails, it will be downloaded.
                            L.i { "UpdateManager upgradeApk verify failed, invoke service download and install." }
                            // 删除校验失败（hash 不符或签名不在白名单）的旧文件，避免带病复用并触发无谓的重下循环。
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
                    // If verification fails, download the APK
                    withContext(Dispatchers.Main) {
                        startDownloadService(context, url, apkHash, filePath, isForce)
                    }
                }
            }
        } else {
            // If the local update package does not exist, it will be downloaded.
            L.i { "UpdateManager upgradeApk invoke service download and install." }
            startDownloadService(context, url, apkHash, filePath, isForce)
        }
    }

    private fun startDownloadService(context: Context, url: String, apkHash: String, filePath: String, isForce: Boolean) {
        val intent = Intent(context, AppUpgradeService::class.java)
        intent.putExtra(INTENT_PARAM_APK_DOWNLOAD_URL, url)
        intent.putExtra(INTENT_PARAM_APK_VERIFY_HASH, apkHash)
        intent.putExtra(INTENT_PARAM_APK_STORE_PATH, filePath)
        intent.putExtra(INTENT_PARAM_APK_FORCE_UPGRADE, isForce)
        context.startService(intent)
    }

    private fun clearDownloadedApk(dirPath: String, excludeFileName: String) {
        val dir = File(dirPath)
        if (!dir.listFiles().isNullOrEmpty()) {
            dir.listFiles()?.forEach {
                if (!it.name.equals(excludeFileName)) it.delete()
            }
        }
    }


    companion object {

        const val INTENT_PARAM_APK_DOWNLOAD_URL = "apk_download_url"
        const val INTENT_PARAM_APK_VERIFY_HASH = "apk_verify_hash"
        const val INTENT_PARAM_APK_STORE_PATH = "apk_store_path"
        const val INTENT_PARAM_APK_DOWNLOAD_STATUS = "apk_download_status"
        const val INTENT_PARAM_APK_FORCE_UPGRADE = "apk_force_upgrade"

        const val STATUS_DOWNLOAD_SUCCESS = 1
        const val STATUS_DOWNLOAD_FAILED = 0
        const val STATUS_VERIFY_FAILED = -1

        val ACTION_APK_DOWNLOAD_COMPLETED: String by lazy { "${application.packageName}.APK_DOWNLOAD_COMPLETED" }

        fun verifyApk(context: Context, apkPath: String, apkHash: String): Boolean {
            if (TextUtils.isEmpty(apkPath)) {
                return false
            }
            try {
                // 1. 完整性校验：流式计算 SHA256，避免一次性读取整个文件到内存
                val hash = calculateFileSha256(File(apkPath))
                if (!hash.equals(apkHash, ignoreCase = true)) {
                    L.w { "[UpdateManager] verifyApk hash mismatch." }
                    return false
                }
                // 2. 来源校验：强制校验下载 APK 的签名证书是否在硬编码白名单内。
                if (!SecurityLib.checkApkFileSign(context, apkPath, context.packageName)) {
                    L.e { "[UpdateManager] verifyApk signature not in whitelist, reject install." }
                    return false
                }
                return true
            } catch (e: Exception) {
                L.w { "[UpdateManager] verifyApk error: ${e.stackTraceToString()}" }
            }
            return false
        }

        /**
         * 流式计算文件的 SHA256 哈希值，避免一次性加载整个文件到内存
         * 参考 FileUtils.getFileMD5 和 PushTextSendJob 的实现方式
         */
        private fun calculateFileSha256(file: File): String {
            return file.inputStream().use { inputStream ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
                CryptoUtil.bytesToHex(digest.digest())
            }
        }

        fun installAPK(context: Context, apkUri: Uri, apkFile: File?) {
            val intent = Intent()
            intent.action = Intent.ACTION_VIEW
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                L.i { "UpdateManager installAPK error:${e.message}" }
                ToastUtil.showLong(getString(R.string.status_upgrade_install_failed) + ":" + e.message)
            }
        }
    }


}