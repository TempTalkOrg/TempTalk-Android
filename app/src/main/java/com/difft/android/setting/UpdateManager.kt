package com.difft.android.setting

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.difft.android.BuildConfig
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.setting.repo.SettingRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class UpdateManager @Inject constructor(
    private val settingRepo: SettingRepo,
    private val userManager: UserManager
) {
    fun checkUpdate(context: FragmentActivity, isFromSetting: Boolean) {
        if (isFromSetting) {
            ComposeDialogManager.showWait(context, "")
        }
        context.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    settingRepo.checkUpdate(SecureSharedPrefsUtil.getToken(), PackageUtil.getAppVersionName() ?: "", BuildConfig.APP_CHANNEL)
                }
                if (isFromSetting) {
                    ComposeDialogManager.dismissWait()
                }
                if (result.status == 0) {
                    result.data?.let { response ->
                        if (response.update) {
                            if (response.force) {
                                showForceUpdateDialog(context, response.notes)
                            } else {
                                showUpdateDialog(context, response.notes)
                            }
                        } else {
                            if (isFromSetting) {
                                showLatestDialog(context)
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

    private fun showUpdateDialog(context: Context, notes: String) {
        ComposeDialogManager.showMessageDialog(
            context = context,
            title = context.getString(R.string.settings_check_new_version),
            message = notes,
            confirmText = context.getString(R.string.settings_dialog_update),
            cancelText = context.getString(R.string.settings_dialog_cancel),
            onConfirm = { goFdroid(context) }
        )
    }

    private fun showForceUpdateDialog(context: FragmentActivity, notes: String) {
        ComposeDialogManager.showMessageDialog(
            context = context,
            title = context.getString(R.string.settings_check_new_version),
            message = notes,
            confirmText = context.getString(R.string.settings_dialog_update),
            cancelable = false,
            showCancel = false,
            onConfirm = { goFdroid(context) }
        )
    }

    private fun goFdroid(context: Context) {
        val fdroidPackage = "org.fdroid.fdroid"
        try {
            val uri = Uri.parse("market://details?id=" + context.packageName)
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.setPackage(fdroidPackage)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            L.w { "[UpdateManager] goFdroid error: ${e.stackTraceToString()}" }
            val uri = Uri.parse("https://f-droid.org/packages/" + context.packageName)
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
