package com.difft.android.setting

import android.content.Context
import com.difft.android.BuildConfig
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import android.view.View
import androidx.lifecycle.Lifecycle
import com.difft.android.base.utils.pane.PageWait
import com.difft.android.base.utils.pane.detailBackOwner
import com.difft.android.base.utils.pane.isVisibleTopNow
import com.difft.android.base.utils.pane.dismissWithView
import com.difft.android.base.utils.pane.dismissWhenLeavingVisibleTop
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import com.difft.android.base.widget.DialogCloseReason
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

    /**
     * Called on every delivery hand-off; the startup update checker ignores it unless a forced offer
     * currently blocks the app, and then lets that gate expire (#1355).
     */
    var onForcedHandOff: (() -> Unit)? = null


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
                    } else {
                        L.w { "[UpdateManager] check update failed status=${result.status}" }
                        if (isFromSetting) {
                            ToastUtil.show(result.reason?.takeIf { it.isNotBlank() } ?: context.getString(com.difft.android.chat.R.string.chat_net_error))
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

    /** Page-owned settings check. The caller binds busy/wait once with waitWhileVisibleTop. */
    fun checkUpdate(page: Fragment, busy: MutableStateFlow<Boolean>, wait: PageWait): Job {
        val originalView = page.requireView()
        return page.viewLifecycleOwner.lifecycleScope.launch {
            if (busy.value || !page.isVisibleTopNow() || !page.detailBackOwner().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            busy.value = true
            try {
                userManager.update { lastCheckUpdateTime = System.currentTimeMillis() }
                if (environmentHelper.isInsiderChannel()) {
                    val configs = withContext(Dispatchers.IO) {
                        globalConfigHttpClient.httpService.getAppVersionConfigs(urlManager.appVersionConfigUrl)
                    }
                    busy.value = false
                    wait.dismiss()
                    if (!awaitSettingsPageVisible(page, originalView)) return@launch
                    if (configs.versionCode > PackageUtil.getAppVersionCode()) {
                        page.context?.let { delivery.deliver(it, UpdateOffer(configs.url, configs.hash), false, ::handOff) }
                    } else {
                        delay(500)
                        if (awaitSettingsPageVisible(page, originalView)) ToastUtil.showLong(page.getString(R.string.settings_version_is_latest))
                    }
                } else {
                    val result = withContext(Dispatchers.IO) {
                        settingRepo.checkUpdate(PackageUtil.getAppVersionName().orEmpty(), BuildConfig.APP_CHANNEL)
                    }
                    busy.value = false
                    wait.dismiss()
                    if (!awaitSettingsPageVisible(page, originalView)) return@launch
                    if (result.status == 0) {
                        result.data?.let { response ->
                            if (response.update) showPageUpdateDialog(page, response)
                            else {
                                delay(500)
                                if (awaitSettingsPageVisible(page, originalView)) ToastUtil.showLong(page.getString(R.string.settings_version_is_latest))
                            }
                        }
                    } else {
                        ToastUtil.show(result.reason?.takeIf { it.isNotBlank() }
                            ?: page.getString(com.difft.android.chat.R.string.chat_net_error))
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                busy.value = false
                wait.dismiss()
                L.w { "[UpdateManager] page_check_failed error=${e.javaClass.simpleName}" }
                if (awaitSettingsPageVisible(page, originalView)) {
                    ToastUtil.show(e.message ?: page.getString(com.difft.android.chat.R.string.chat_net_error))
                }
            } finally {
                busy.value = false
                wait.dismiss()
            }
        }
    }

    private fun showPageUpdateDialog(page: Fragment, response: CheckUpdateResponse) {
        val context = page.context ?: return
        var dialogReference: java.lang.ref.WeakReference<ComposeDialog>? = null
        val dialog = page.dismissWithView(ComposeDialogManager.showMessageDialog(
            context = context,
            title = context.getString(R.string.settings_check_new_version),
            message = response.notes,
            confirmText = context.getString(R.string.settings_dialog_update),
            cancelText = context.getString(R.string.settings_dialog_cancel),
            cancelable = !response.force,
            showCancel = !response.force,
            autoDismiss = !response.force,
            onConfirm = {
                if (page.isVisibleTopNow() && page.detailBackOwner().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) page.context?.let {
                    if (delivery.isInProgress) ToastUtil.show(R.string.status_upgrade_downloading)
                    else delivery.deliver(it, response.toOffer(), response.force) { dialogReference?.get()?.dismiss(); handOff() }
                }
            },
        ))
        dialogReference = dialog?.let { java.lang.ref.WeakReference(it) }
        page.dismissWhenLeavingVisibleTop { dialog?.dismiss() }
    }

    private fun showLatestDialog(context: FragmentActivity) {
        context.lifecycleScope.launch {
            delay(500)
            ToastUtil.showLong(ResUtils.getString(R.string.settings_version_is_latest))
        }
    }

    /** Hooks the delivery's completion events to [activity]; call once from the host's onCreate. */
    fun attach(activity: FragmentActivity) = delivery.attach(activity, ::handOff)

    /**
     * The optional-update dialog. The startup prompt host passes [onClosed] and runs the update
     * itself through [confirmOptionalUpdate] once its outcome is recorded; other callers keep the
     * direct confirm.
     */
    fun showUpdateDialog(
        context: Context,
        response: CheckUpdateResponse,
        onClosed: ((DialogCloseReason) -> Unit)? = null,
    ): ComposeDialog? = ComposeDialogManager.showMessageDialog(
        context = context,
        title = context.getString(R.string.settings_check_new_version),
        message = response.notes,
        confirmText = context.getString(R.string.settings_dialog_update),
        cancelText = context.getString(R.string.settings_dialog_cancel),
        onConfirm = { if (onClosed == null) confirmOptionalUpdate(context, response) },
        onClosed = onClosed,
    )

    fun confirmOptionalUpdate(context: Context, response: CheckUpdateResponse) =
        confirmUpdate(context, response.toOffer(), isForce = false)

    /**
     * Non-dismissable; closed when the delivery hands off (see [attach]) or by
     * [dismissForceUpdateDialog]. A new offer replaces the one on screen, never stacks on it.
     */
    fun showForceUpdateDialog(context: FragmentActivity, response: CheckUpdateResponse) {
        closeForceUpdateDialog()
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
        delivery.deliver(context, offer, isForce, ::handOff)
    }

    private fun handOff() {
        closeForceUpdateDialog()
        onForcedHandOff?.invoke()
    }

    /** The forced offer was withdrawn: take its dialog down. */
    fun dismissForceUpdateDialog() = closeForceUpdateDialog()

    private fun closeForceUpdateDialog() {
        foreUpdateMessageDialog?.dismiss()
        foreUpdateMessageDialog = null
    }
}

/** Waits only for the original retained view; DESTROYED is never treated as a visible shell. */
internal suspend fun awaitSettingsPageVisible(page: Fragment, originalView: View): Boolean {
    if (page.view !== originalView) return false
    val state = page.detailBackOwner().lifecycle.currentStateFlow.first {
        it == Lifecycle.State.DESTROYED || it.isAtLeast(Lifecycle.State.STARTED)
    }
    return state.isAtLeast(Lifecycle.State.STARTED) && page.view === originalView && page.isVisibleTopNow()
}
