package com.difft.android.setting

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationFixJump
import com.difft.android.base.notification.NotificationTestRepository
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.messages.MessageForegroundService
import com.difft.android.chat.util.CriticalAlertBypass
import com.difft.android.chat.util.MessageNotificationUtil
import com.difft.android.chat.util.NotificationChannelManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one state read behind the notification settings page and its Calls and Critical Alerts pages. */
class NotificationSettingsStateReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val messageNotificationUtil: MessageNotificationUtil,
    private val notificationTestRepository: NotificationTestRepository,
    private val channelManager: NotificationChannelManager,
) {

    /** Reads every status on IO; the critical-alert bypass is reconciled first so its read-back is current. */
    internal suspend fun read(): NotificationSettingsUiState = withContext(Dispatchers.IO) {
        val bypass = try {
            channelManager.reconcileCriticalAlertBypass()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[NotificationSettings] bypass reconcile failed error=${e.javaClass.simpleName}" }
            CriticalAlertBypass.NOT_APPLICABLE
        }
        NotificationSettingsUiState(
            readiness = notificationTestRepository.readReadiness(),
            sdkInt = Build.VERSION.SDK_INT,
            dndAccessGranted = messageNotificationUtil.isNotificationPolicyAccessGranted(),
            bypass = bypass,
            backgroundConnectionRunning = MessageForegroundService.isRunning,
            oemPopupDirect = NotificationFixJump.opensOemPopupDirectly(context),
        )
    }

    /** Records [fix]'s category as seen when its page [opened]; a failed write only keeps the reminder. */
    internal suspend fun recordFixOpened(fix: NotificationFix, opened: Intent?) {
        val channelId = NotificationFixJump.seenCategory(fix, opened) ?: return
        try {
            notificationTestRepository.markCategorySeen(channelId)
            L.i { "[NotificationSettings] category seen channel=$channelId" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[NotificationSettings] category seen write failed channel=$channelId error=${e.javaClass.simpleName}" }
        }
    }

    /** [read] with failures logged; null keeps the page's last state. */
    internal suspend fun readOrNull(page: String): NotificationSettingsUiState? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        L.w { "[NotificationSettings] refresh failed page=$page error=${e.javaClass.simpleName}" }
        null
    }
}

/**
 * Opens [fix]'s settings page from a notification settings page, with the shared failure toast; an
 * opened category page is recorded through [stateReader].
 */
internal fun Fragment.openNotificationFix(fix: NotificationFix, stateReader: NotificationSettingsStateReader) {
    viewLifecycleOwner.lifecycleScope.launch {
        val appContext = requireContext().applicationContext
        val groupBlocked = fix == NotificationFix.MESSAGE_CATEGORY &&
            withContext(Dispatchers.IO) { NotificationFixJump.isMessageGroupBlocked(appContext) }
        val opened = NotificationFixJump.openTracking(requireActivity(), fix, groupBlocked)
        if (opened == null) {
            L.w { "[NotificationSettings] fix jump failed fix=$fix" }
            ToastUtil.show(R.string.notification_settings_open_fail)
        }
        stateReader.recordFixOpened(fix, opened)
    }
}
