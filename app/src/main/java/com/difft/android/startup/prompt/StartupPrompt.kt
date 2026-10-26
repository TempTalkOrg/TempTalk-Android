package com.difft.android.startup.prompt

import com.difft.android.base.notification.ReadinessIssue

/**
 * Cancelable main-screen prompts (#1355). Declaration order is the priority: the first eligible
 * entry wins the one prompt a foreground session may show.
 */
enum class PromptId {
    /** Retiring package only: the one-time "move to the new app" guide page. */
    LEGACY_MIGRATION,

    /** Notification permission, app switch or message category off. */
    NOTIFICATIONS,

    /** No FCM and the background connection cannot run on its own. */
    BACKGROUND_CONNECTION,

    /** Full-screen intent (incoming-call) permission denied, API 34+. */
    FULL_SCREEN,

    /** Optional (not forced) app update. */
    OPTIONAL_UPDATE,
}

/** Mandatory dialogs. While any is active no cancelable prompt is chosen or shown. */
enum class GateId { INACTIVE_DEVICE, FORCED_UPDATE, SECURITY, NETWORK_RISK }

/** Three-state answer of a prompt's provider: only a confirmed [NOT_ELIGIBLE] removes a record. */
enum class Eligibility { ELIGIBLE, NOT_ELIGIBLE, UNKNOWN }

/** What the user (or the system) did with a prompt. */
sealed interface PromptOutcome {
    /** "Not now", outside tap, back, or a visible system denial: one ladder step. */
    data object Declined : PromptOutcome

    /** Primary action: the result is checked when the user is back. */
    data object ActionTaken : PromptOutcome

    /** The provider confirmed there is nothing to fix: the record is removed. */
    data object Resolved : PromptOutcome

    /** A gate took over: nothing recorded, the session keeps its budget. */
    data object Preempted : PromptOutcome
}

/** A provider's answer, with what the host needs to render the prompt. */
data class ProviderAnswer(
    val eligibility: Eligibility,
    /** NOTIFICATIONS / FULL_SCREEN: the readiness issue that decides the fix page. */
    val issue: ReadinessIssue? = null,
    /** OPTIONAL_UPDATE: identity of the offer, see [updateOfferKey]. */
    val offerKey: String? = null,
    /** NOTIFICATIONS: API 33+ and POST_NOTIFICATIONS not granted, so the system dialog may ask. */
    val permissionRequestable: Boolean = false,
) {
    companion object {
        val NOT_ELIGIBLE = ProviderAnswer(Eligibility.NOT_ELIGIBLE)
        val UNKNOWN = ProviderAnswer(Eligibility.UNKNOWN)
    }
}

/**
 * The prompt the host should render now. [seq] changes every time the coordinator chooses again, so
 * a recreated host re-renders the same ticket while a stale report for an older one is ignored.
 */
data class PromptTicket(
    val id: PromptId,
    val seq: Long,
    val issue: ReadinessIssue? = null,
    /** NOTIFICATIONS only: ask through the system permission dialog instead of the app dialog. */
    val viaSystemRequest: Boolean = false,
    /** OPTIONAL_UPDATE only: the offer shown, stored on the record from its first write. */
    val offerKey: String? = null,
)
