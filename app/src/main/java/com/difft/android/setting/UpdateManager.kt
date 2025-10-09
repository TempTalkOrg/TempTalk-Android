package com.difft.android.setting

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.application
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.UrlManager
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.setting.data.CheckUpdateResponse
import com.difft.android.setting.repo.SettingRepo
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ComposeDialog
import io.reactivex.rxjava3.core.Observable
import util.ScreenLockUtil
import com.difft.android.websocket.api.crypto.CryptoUtil
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
class UpdateManager @Inject constructor(
    private val settingRepo: SettingRepo,
    private val environmentHelper: EnvironmentHelper,
    @ChativeHttpClientModule.NoHeader
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
        if (environmentHelper.isInsiderChannel()) { //内部版本通过配置文件检查更新，有新版本自动进行下载更新
            globalConfigHttpClient.httpService.getAppVersionConfigs(urlManager.appVersionConfigUrl)
                .compose(RxUtil.getSchedulerComposer())
                .to(RxUtil.autoDispose(context))
                .subscribe({
                    L.d { "[UpdateManager] get App Version Configs success:$it" }
                    if (isFromSetting) {
                        ComposeDialogManager.dismissWait()
                    }
                    if (it.versionCode > PackageUtil.getAppVersionCode()) {
                        appInnerToInstall(context, it.url, it.hash)
                    } else {
                        if (isFromSetting) {
                            showLatestDialog(context)
                        }
                    }
                }, {
                    L.e { "[UpdateManager] get App Version Configs error:" + it.stackTraceToString() }
                    it.printStackTrace()
                    if (isFromSetting) {
                        ComposeDialogManager.dismissWait()
                    }
                })
        } else {
            settingRepo.checkUpdate(SecureSharedPrefsUtil.getToken(), PackageUtil.getAppVersionName() ?: "")
                .compose(RxUtil.getSingleSchedulerComposer())
                .to(RxUtil.autoDispose(context))
                .subscribe({
                    if (isFromSetting) {
                        ComposeDialogManager.dismissWait()
                    }
                    if (it.status == 0) {
                        it.data?.let { response ->
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
                }) {
                    it.printStackTrace()
                    if (isFromSetting) {
                        ComposeDialogManager.dismissWait()
                        it.message?.let { message -> ToastUtil.show(message) }
                    }
                }
        }
        userManager.update {
            this.lastCheckUpdateTime = System.currentTimeMillis()
        }
    }

    private fun showLatestDialog(context: FragmentActivity) {
        Observable.timer(500, TimeUnit.MILLISECONDS)
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(context))
            .subscribe({
                ToastUtil.showLong(ResUtils.getString(R.string.settings_version_is_latest))
            }, { it.printStackTrace() })
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
                ScreenLockUtil.noNeedShowScreenLock = true
                val authority: String = context.applicationContext.packageName + ".provider"
                val uri = FileProvider.getUriForFile(context, authority, apkFile)
                installAPK(context, uri, apkFile)
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
            val currentPackageUri: Uri = Uri.parse("market://details?id=" + context.packageName)
            val intent = Intent(Intent.ACTION_VIEW, currentPackageUri)
            intent.setPackage(playPackage)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            val currentPackageUri: Uri = Uri.parse("https://play.google.com/store/apps/details?id=" + context.packageName)
            val intent = Intent(Intent.ACTION_VIEW, currentPackageUri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun upgradeApk(context: Context, url: String, apkHash: String, isForce: Boolean = false) {
        val fileDir = FileUtil.getFilePath(FileUtil.FILE_DIR_UPGRADE)

        val filename = Uri.parse(url).lastPathSegment?.let {
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
        if (file.exists() && verifyApk(filePath, apkHash)) {
            L.i { "UpdateManager upgradeApk install downloaded apk package." }
            if (isForce) {
                closeForceUpdateDialog()
                showInstallDialog(context, file, true)
            } else {
                showInstallDialog(context, file, false)
            }
        } else {
            // If the local update package does not exist or the hash verification code fails, it will be downloaded.
            L.i { "UpdateManager upgradeApk invoke service download and install." }
            val intent = Intent(context, AppUpgradeService::class.java)
            intent.putExtra(INTENT_PARAM_APK_DOWNLOAD_URL, url)
            intent.putExtra(INTENT_PARAM_APK_VERIFY_HASH, apkHash)
            intent.putExtra(INTENT_PARAM_APK_STORE_PATH, filePath)
            intent.putExtra(INTENT_PARAM_APK_FORCE_UPGRADE, isForce)
            context.startService(intent)
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

        fun verifyApk(apkPath: String, apkHash: String): Boolean {
            if (TextUtils.isEmpty(apkPath)) {
                return false
            }
            try {
                val hash = CryptoUtil.bytesToHex(CryptoUtil.sha256(FileUtil.readFile(File(apkPath))))
                if (hash.equals(apkHash)) {
                    return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return false
        }

        fun installAPK(context: Context, apkUri: Uri, apkFile: File?) {
            val intent = Intent()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                //安卓7.0版本以上安装
                intent.action = Intent.ACTION_VIEW
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            } else {
                //安卓6.0-7.0版本安装
                intent.action = Intent.ACTION_DEFAULT
                intent.addCategory(Intent.CATEGORY_DEFAULT)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                apkFile?.let {
                    intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
                }
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                L.i { "UpdateManager installAPK error:${e.message}" }
                e.printStackTrace()
                ToastUtil.showLong(getString(R.string.status_upgrade_install_failed) + ":" + e.message)
            }
        }
    }


}