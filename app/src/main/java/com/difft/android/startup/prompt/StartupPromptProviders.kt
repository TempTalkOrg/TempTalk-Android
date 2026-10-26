package com.difft.android.startup.prompt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.difft.android.base.migration.LegacyMigrationGate
import com.difft.android.base.migration.PeerApps
import com.difft.android.base.network.NetworkRiskNotifier
import com.difft.android.base.notification.NotificationReadiness
import com.difft.android.base.notification.NotificationReadinessProvider
import com.difft.android.base.notification.ReadinessIssue
import com.difft.android.base.notification.ReadinessItemId
import com.difft.android.base.notification.ReadinessStatus
import com.difft.android.base.session.InactiveDeviceNotifier
import com.difft.android.base.user.UserManager
import com.difft.android.chat.messages.MessageForegroundService
import com.difft.android.chat.messages.MessageServiceManager
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.push.FcmInitResult
import com.difft.android.push.PushUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Eligibility of every startup prompt and the state of the gates. Reads only: system state,
 * readiness, the FCM result, the update result and the service state. Never touches channel code.
 */
@Singleton
class StartupPromptProviders @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val readinessProvider: NotificationReadinessProvider,
    private val pushUtil: PushUtil,
    private val userManager: UserManager,
    private val messageServiceManager: MessageServiceManager,
    private val updateChecker: StartupUpdateChecker,
    private val securityChecks: StartupSecurityChecks,
    private val globalConfigsManager: GlobalConfigsManager,
) {

    /** Starts the checks whose results gate or feed the prompts. */
    fun onSessionStart() {
        securityChecks.ensureChecked()
        updateChecker.onSessionStart()
    }

    suspend fun evaluate(id: PromptId): ProviderAnswer = withContext(Dispatchers.IO) {
        when (id) {
            PromptId.LEGACY_MIGRATION -> legacyMigrationAnswer()
            PromptId.NOTIFICATIONS -> notificationsAnswer(readinessProvider.readBlocking(), isPermissionRequestable())
            PromptId.BACKGROUND_CONNECTION -> ProviderAnswer(
                backgroundConnectionEligibility(
                    fcm = pushUtil.fcmInitResult.value,
                    serviceRunning = MessageForegroundService.isRunning,
                    autoStart = userManager.getUserData()?.autoStartMessageService ?: true,
                    requirementsMet = messageServiceManager::checkBackgroundConnectionRequirements,
                )
            )
            PromptId.FULL_SCREEN -> fullScreenAnswer(readinessProvider.readBlocking())
            PromptId.OPTIONAL_UPDATE -> optionalUpdateAnswer(updateChecker.state.value)
        }
    }

    /** Active gates. Forced update and security warnings are rendered by the host, the others app-wide. */
    fun gates(): Flow<Set<GateId>> = combine(
        InactiveDeviceNotifier.pending,
        NetworkRiskNotifier.warningPending,
        updateChecker.blockingForced,
        securityChecks.pending,
    ) { inactive, networkRisk, forced, security ->
        buildSet {
            if (inactive) add(GateId.INACTIVE_DEVICE)
            if (forced != null) add(GateId.FORCED_UPDATE)
            if (security.isNotEmpty()) add(GateId.SECURITY)
            if (networkRisk) add(GateId.NETWORK_RISK)
        }
    }.distinctUntilChanged()

    /** Sources whose change can turn an UNKNOWN answer definite (late FCM or update results). */
    fun changes(): Flow<Unit> = merge(
        pushUtil.fcmInitResult.map { },
        updateChecker.state.map { },
    )

    private fun legacyMigrationAnswer(): ProviderAnswer {
        if (!PeerApps.isLegacy(context)) return ProviderAnswer.NOT_ELIGIBLE
        if (userManager.getUserData()?.legacyMigrationGuideShown == true) return ProviderAnswer.NOT_ELIGIBLE
        return if (LegacyMigrationGate.isOpen(context, globalConfigsManager)) {
            ProviderAnswer(Eligibility.ELIGIBLE)
        } else {
            ProviderAnswer.NOT_ELIGIBLE
        }
    }

    private fun isPermissionRequestable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
}

/**
 * Notifications slot: eligible exactly where `MessageNotificationUtil.canShowNotifications()` is
 * false (permission or app switch off = N1; MESSAGE group blocked, category missing or NONE = N2 off).
 * An unreadable category (unspecified importance) is UNKNOWN, never a reason to prompt.
 */
fun notificationsAnswer(readiness: NotificationReadiness, permissionRequestable: Boolean): ProviderAnswer {
    val n1 = readiness.item(ReadinessItemId.N1)
    val n2 = readiness.item(ReadinessItemId.N2)
    return when {
        n1?.status == ReadinessStatus.PROBLEM ->
            ProviderAnswer(Eligibility.ELIGIBLE, ReadinessIssue.APP_NOTIFICATIONS_OFF, permissionRequestable = permissionRequestable)
        n2?.status == ReadinessStatus.PROBLEM && n2.issue == ReadinessIssue.MESSAGE_CATEGORY_OFF ->
            ProviderAnswer(Eligibility.ELIGIBLE, ReadinessIssue.MESSAGE_CATEGORY_OFF, permissionRequestable = permissionRequestable)
        n1 == null || n2 == null || n2.status == ReadinessStatus.UNKNOWN -> ProviderAnswer.UNKNOWN
        else -> ProviderAnswer.NOT_ELIGIBLE
    }
}

/** Full-screen slot: eligible on API 34+ when the full-screen intent permission is denied (readiness C2). */
fun fullScreenAnswer(readiness: NotificationReadiness): ProviderAnswer =
    readiness.callPromptIssue()?.let { ProviderAnswer(Eligibility.ELIGIBLE, it) } ?: ProviderAnswer.NOT_ELIGIBLE

/**
 * Background-connection slot. Eligible only when FCM is unavailable, the service is not running and
 * [FcmFallbackStarter] cannot start it (auto-start off, or a requirement not met).
 */
fun backgroundConnectionEligibility(
    fcm: FcmInitResult,
    serviceRunning: Boolean,
    autoStart: Boolean,
    requirementsMet: () -> Boolean,
): Eligibility = when (fcm) {
    is FcmInitResult.Success -> Eligibility.NOT_ELIGIBLE
    FcmInitResult.Idle, FcmInitResult.Loading -> if (serviceRunning) Eligibility.NOT_ELIGIBLE else Eligibility.UNKNOWN
    is FcmInitResult.PlayServicesUnavailable, is FcmInitResult.Failure -> when {
        serviceRunning -> Eligibility.NOT_ELIGIBLE
        autoStart && requirementsMet() -> Eligibility.NOT_ELIGIBLE
        else -> Eligibility.ELIGIBLE
    }
}

fun optionalUpdateAnswer(state: UpdateCheckState): ProviderAnswer = when (state) {
    is UpdateCheckState.Optional -> ProviderAnswer(
        Eligibility.ELIGIBLE,
        offerKey = updateOfferKey(state.response.apkHash, state.response.url),
    )
    UpdateCheckState.NoUpdate -> ProviderAnswer.NOT_ELIGIBLE
    // A forced offer is a gate, not this prompt; an unchecked or failed check is unknown.
    is UpdateCheckState.Forced, UpdateCheckState.Unknown -> ProviderAnswer.UNKNOWN
}
