package com.difft.android.setting

import android.content.Context
import com.difft.android.BuildConfig
import androidx.fragment.app.FragmentActivity
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.UrlManager
import com.difft.android.network.di.ChativeHttpClientModule
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
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil

class UpdateManager @Inject constructor(
    private val settingRepo: SettingRepo,
    private val environmentHelper: EnvironmentHelper,
    @param:ChativeHttpClientModule.NoHeader
    private val globalConfigHttpClient: ChativeHttpClient,
    private val urlManager: UrlManager,
    private val userManager: UserManager,
    private val delivery: UpdateDelivery
) {
    private var foreUpdateMessageDialog: ComposeDialog? = null


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
                        delivery.deliver(context, UpdateOffer(configs.url, configs.hash), isForce = false, ::closeForceUpdateDialog)
                    } else {
                        if (isFromSetting) {
                            showLatestDialog(context)
                        }
                    }
                } else {
                    val result = withContext(Dispatchers.IO) {
                        settingRepo.checkUpdate(PackageUtil.getAppVersionName() ?: "", BuildConfig.APP_CHANNEL)
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

    /** Hooks the delivery's completion events to [activity]; call once from the host's onCreate. */
    fun attach(activity: FragmentActivity) = delivery.attach(activity, ::closeForceUpdateDialog)

    private fun showUpdateDialog(context: Context, response: CheckUpdateResponse) {
        ComposeDialogManager.showMessageDialog(
            context = context,
            title = context.getString(R.string.settings_check_new_version),
            message = response.notes,
            confirmText = context.getString(R.string.settings_dialog_update),
            cancelText = context.getString(R.string.settings_dialog_cancel),
            onConfirm = { confirmUpdate(context, response.toOffer(), isForce = false) }
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
            onConfirm = { confirmUpdate(context, response.toOffer(), isForce = true) }
        )
    }

    private fun confirmUpdate(context: Context, offer: UpdateOffer, isForce: Boolean) {
        if (delivery.isInProgress) {
            ToastUtil.show(R.string.status_upgrade_downloading)
            return
        }
        delivery.deliver(context, offer, isForce, ::closeForceUpdateDialog)
    }

    private fun closeForceUpdateDialog() {
        foreUpdateMessageDialog?.dismiss()
        foreUpdateMessageDialog = null
    }
}
