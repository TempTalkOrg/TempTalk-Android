package com.difft.android.startup.prompt

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.PeerApps
import com.difft.android.base.network.NetworkRiskNotifier
import com.difft.android.base.session.InactiveDeviceNotifier
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationFixJump
import com.difft.android.base.notification.ReadinessIssue
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.DialogCloseReason
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.setting.BackgroundConnectionSettingsActivity
import com.difft.android.setting.NotificationReadinessText
import com.difft.android.setting.UpdateManager
import com.difft.android.setting.data.CheckUpdateResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.system.exitProcess

/**
 * IndexActivity's renderer for the startup gates and the coordinator's prompt (#1355).
 *
 * Nothing is mounted until the host is presentable: RESUMED, no app-lock decision pending, then a
 * 600 ms settle that a pause cancels (so a lock that starts right after the decision never gets a
 * dialog underneath it). Gates render before prompts: a forced update hides the security warnings,
 * which show one at a time; any gate removes a prompt on screen (the coordinator drops it without
 * recording a decline).
 *
 * Close reasons map to outcomes here; [DialogCloseReason.PROGRAMMATIC] is always the host's own
 * doing and records nothing. Outcomes are recorded before any Activity is started: persisted, or kept
 * in coordinator memory for this process when the write fails.
 */
class IndexStartupPromptHost @Inject constructor(
    private val coordinator: StartupPromptCoordinator,
    private val updateChecker: StartupUpdateChecker,
    private val securityChecks: StartupSecurityChecks,
    private val userManager: UserManager,
) {
    private lateinit var activity: FragmentActivity
    private lateinit var updateManager: UpdateManager
    private lateinit var lockPending: StateFlow<Boolean>
    private var dualPaneConversationOpen: () -> Boolean = { false }
    private var permissionLauncher: ActivityResultLauncher<String>? = null
    private var attached = false

    private var presentable = false
    private var settleJob: Job? = null

    private var promptDialog: ComposeDialog? = null
    private var renderedSeq: Long? = null
    private var securityDialog: ComposeDialog? = null
    private var shownSecurity: SecurityWarning? = null
    private var shownForced: CheckUpdateResponse? = null

    /** Call from onCreate: the permission launcher must be registered before the host is started. */
    fun attach(
        activity: FragmentActivity,
        updateManager: UpdateManager,
        lockPending: StateFlow<Boolean>,
        dualPaneConversationOpen: () -> Boolean,
    ) {
        check(!attached) { "attach once" }
        attached = true
        this.activity = activity
        this.updateManager = updateManager
        this.lockPending = lockPending
        this.dualPaneConversationOpen = dualPaneConversationOpen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission(), ::onPermissionResult)
        }
        updateManager.onForcedHandOff = updateChecker::onForcedHandOff
        coordinator.start()
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_PAUSE -> refreshPresentable()
                Lifecycle.Event.ON_DESTROY -> settleJob?.cancel()
                else -> Unit
            }
        })
        activity.lifecycleScope.launch { lockPending.collect { refreshPresentable() } }
        activity.lifecycleScope.launch {
            merge<Any?>(
                coordinator.current,
                updateChecker.state,
                updateChecker.blockingForced,
                securityChecks.pending,
                InactiveDeviceNotifier.pending,
                NetworkRiskNotifier.warningPending,
            ).collect { render() }
        }
    }

    /** The dual-pane detail pane opened or closed a conversation. */
    fun onDetailPaneChanged() {
        if (attached && presentable) reportHostState()
    }

    private fun refreshPresentable() {
        val ok = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !lockPending.value
        if (ok) {
            if (presentable || settleJob?.isActive == true) return
            settleJob = activity.lifecycleScope.launch {
                delay(SETTLE_MS)
                presentable = true
                reportHostState()
                render()
            }
        } else {
            settleJob?.cancel()
            settleJob = null
            if (presentable) {
                presentable = false
                reportHostState()
            }
        }
    }

    private fun reportHostState() {
        coordinator.setHostState(presentable, dualPaneConversationOpen(), notificationRationale())
    }

    /** Whether the system would still show its POST_NOTIFICATIONS dialog. */
    private fun notificationRationale(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)

    private fun render() {
        if (!attached) return
        val forced = updateChecker.blockingForced.value
        val security = securityChecks.pending.value.firstOrNull()
        val ticket = coordinator.current.value
        // The app-wide gates render themselves; a prompt never stays or mounts under them.
        val appGate = InactiveDeviceNotifier.pending.value || NetworkRiskNotifier.warningPending.value
        // Removals also run while not presentable: a prompt that is gone (answered, preempted,
        // resolved, replaced) or covered by any gate never stays mounted. An unanswered prompt keeps
        // its ticket across a trip to the background, so it stays mounted.
        if (forced != null || security != null || appGate || ticket == null || ticket.seq != renderedSeq) dismissPrompt()
        if (forced == null && shownForced != null) {
            shownForced = null
            updateManager.dismissForceUpdateDialog()
        }
        if (!presentable) return
        if (forced != null) {
            if (shownForced != forced) {
                dismissSecurity()
                shownForced = forced
                L.i { "[StartupPrompt] gate show id=FORCED_UPDATE" }
                updateManager.showForceUpdateDialog(activity, forced)
            }
            return
        }
        if (security != null) {
            if (shownSecurity != security) showSecurity(security)
            return
        }
        if (appGate || ticket == null || renderedSeq == ticket.seq) return
        renderedSeq = ticket.seq
        renderTicket(ticket)
    }

    private fun renderTicket(ticket: PromptTicket) {
        val appName = PackageUtil.getAppName().orEmpty()
        when (ticket.id) {
            PromptId.LEGACY_MIGRATION -> showThenAct(ticket) {
                userManager.update { legacyMigrationGuideShown = true }
                activity.startActivity(Intent().setClassName(activity.packageName, PeerApps.LEGACY_GUIDE_ACTIVITY))
            }
            PromptId.NOTIFICATIONS -> {
                val launcher = permissionLauncher
                if (ticket.viaSystemRequest && launcher != null) {
                    requestPermission(ticket, launcher)
                } else {
                    showPromptDialog(ticket, activity.getString(R.string.notification_no_permission_tip1, appName), cancelable = false) {
                        openFix(ticket.issue ?: ReadinessIssue.APP_NOTIFICATIONS_OFF)
                    }
                }
            }
            PromptId.BACKGROUND_CONNECTION ->
                showPromptDialog(ticket, activity.getString(R.string.notification_no_google_tip), cancelable = false) {
                    BackgroundConnectionSettingsActivity.startActivity(activity)
                }
            PromptId.FULL_SCREEN -> {
                val issue = ticket.issue ?: return
                val message = NotificationReadinessText.promptMessage(activity, issue, appName) ?: return
                showPromptDialog(ticket, message) { openFix(issue) }
            }
            PromptId.OPTIONAL_UPDATE -> {
                val response = (updateChecker.state.value as? UpdateCheckState.Optional)?.response ?: return
                promptDialog = updateManager.showUpdateDialog(activity, response) { reason ->
                    onPromptClosed(ticket, reason) { updateManager.confirmOptionalUpdate(activity, response) }
                }
                markShown(ticket)
            }
        }
    }

    /**
     * Prompts that decide whether messages arrive at all (notifications, background connection) are
     * not cancelable, so a stray outside tap is not taken as a decline; "Not now" is always shown.
     */
    private fun showPromptDialog(ticket: PromptTicket, message: String, cancelable: Boolean = true, action: suspend () -> Unit) {
        promptDialog = ComposeDialogManager.showMessageDialog(
            context = activity,
            title = activity.getString(R.string.tip),
            message = message,
            confirmText = activity.getString(R.string.notification_go_to_settings),
            cancelText = activity.getString(R.string.notification_ignore),
            cancelable = cancelable,
            onClosed = { reason -> onPromptClosed(ticket, reason, action) },
        )
        markShown(ticket)
    }

    private fun onPromptClosed(ticket: PromptTicket, reason: DialogCloseReason, action: suspend () -> Unit) {
        // Programmatic closes are the host's own (preempted, resolved, replaced): already recorded.
        if (reason == DialogCloseReason.PROGRAMMATIC) return
        promptDialog = null
        when (reason) {
            DialogCloseReason.CONFIRM -> activity.lifecycleScope.launch { recordThenAct(ticket, rationaleBefore = false, action) }
            DialogCloseReason.CANCEL, DialogCloseReason.OUTSIDE -> activity.lifecycleScope.launch { coordinator.onDeclined(ticket) }
            DialogCloseReason.PROGRAMMATIC -> Unit
        }
    }

    private fun showThenAct(ticket: PromptTicket, rationaleBefore: Boolean = false, action: suspend () -> Unit) {
        activity.lifecycleScope.launch {
            coordinator.onShown(ticket)
            recordThenAct(ticket, rationaleBefore, action)
        }
    }

    /**
     * Records the primary action, then performs it. If this host is destroyed in between (its scope
     * is cancelled), the action never runs from the dead Activity and the coordinator undoes the
     * recorded trip, so the recreated host shows the prompt again instead of counting a decline.
     * The record is awaited non-cancellably so the undo can never run before it.
     */
    private suspend fun recordThenAct(ticket: PromptTicket, rationaleBefore: Boolean, action: suspend () -> Unit) {
        val recorded = withContext(NonCancellable) { coordinator.onActionTaken(ticket, rationaleBefore) }
        if (!recorded) return
        try {
            currentCoroutineContext().ensureActive()
            action()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { coordinator.onActionAborted(ticket) }
            throw e
        }
    }

    private fun requestPermission(ticket: PromptTicket, launcher: ActivityResultLauncher<String>) {
        showThenAct(ticket, rationaleBefore = notificationRationale()) {
            try {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[StartupPrompt] permission request failed error=${e.javaClass.simpleName}" }
                onPermissionResult(false)
            }
        }
    }

    private fun onPermissionResult(granted: Boolean) {
        // The rationale read before the request is on the record, so a recreation keeps it.
        val after = !granted && notificationRationale()
        activity.lifecycleScope.launch { coordinator.onNotificationRequestResult(granted, after) }
    }

    private fun markShown(ticket: PromptTicket) {
        activity.lifecycleScope.launch { coordinator.onShown(ticket) }
    }

    private suspend fun openFix(issue: ReadinessIssue) {
        val fix = NotificationFixJump.fixFor(issue)
        val appContext = activity.applicationContext
        val groupBlocked = fix == NotificationFix.MESSAGE_CATEGORY &&
            withContext(Dispatchers.IO) { NotificationFixJump.isMessageGroupBlocked(appContext) }
        if (!NotificationFixJump.open(activity, fix, groupBlocked)) {
            ToastUtil.show(R.string.notification_settings_open_fail)
        }
    }

    private fun showSecurity(warning: SecurityWarning) {
        dismissSecurity()
        shownSecurity = warning
        L.i { "[StartupPrompt] gate show id=SECURITY warning=$warning" }
        securityDialog = ComposeDialogManager.showMessageDialog(
            context = activity,
            title = activity.getString(R.string.app_sign_error_title),
            message = activity.getString(warning.messageRes),
            confirmText = activity.getString(R.string.app_close_application),
            cancelText = activity.getString(R.string.app_ignore),
            cancelable = false,
            onConfirm = {
                Process.killProcess(Process.myPid())
                exitProcess(0)
            },
            onClosed = { reason ->
                if (reason == DialogCloseReason.CANCEL) {
                    securityDialog = null
                    shownSecurity = null
                    securityChecks.ignore(warning)
                }
            },
        )
    }

    private fun dismissSecurity() {
        val dialog = securityDialog ?: return
        securityDialog = null
        shownSecurity = null
        dialog.dismiss()
    }

    private fun dismissPrompt() {
        val dialog = promptDialog
        promptDialog = null
        renderedSeq = null
        dialog?.dismiss()
    }

    private companion object {
        const val SETTLE_MS = 600L
    }
}
