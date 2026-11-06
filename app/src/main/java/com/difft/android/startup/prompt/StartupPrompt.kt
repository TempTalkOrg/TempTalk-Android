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

    /** The background connection is off and a [BackgroundTrigger] recommends it. */
    BACKGROUND_CONNECTION,

    /** Full-screen intent (incoming-call) permission denied, API 34+. */
    FULL_SCREEN,

    /** Optional (not forced) app update. */
    OPTIONAL_UPDATE,
}

/**
 * Why the background-connection prompt is due. Declaration order is the copy priority.
 */
enum class BackgroundTrigger {
    /** No FCM, and the background connection cannot start on its own. */
    FCM_UNAVAILABLE,

    /** Late deliveries on enough days of the last 7. */
    LATE_DELIVERY,
}

/** Mandatory dialogs. While any is active no cancelable prompt is chosen or shown. */
enum class GateId { INACTIVE_DEVICE, FORCED_UPDATE, SECURITY, NETWORK_RISK }

/** Three-state answer of a prompt's provider: only a confirmed [NOT_ELIGIBLE] removes a record (except the background-connection history). */
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
    /** BACKGROUND_CONNECTION: every trigger that currently holds; empty for the other prompts. */
    val triggers: Set<BackgroundTrigger> = emptySet(),
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
    /** BACKGROUND_CONNECTION only: the trigger that picks the prompt copy. */
    val backgroundTrigger: BackgroundTrigger? = null,
)

/**
 * The answer as the slot's own history sees it: after a decline, late-delivery evidence waits out
 * [PromptBackoffPolicy.LATE_DELIVERY_FLOOR_MS] while FCM unavailable only follows the ladder; an
 * answer left without a trigger is not eligible. Answers without triggers (every other slot) pass
 * unchanged.
 */
internal fun ProviderAnswer.forRecord(id: PromptId, record: PromptRecord, now: Long): ProviderAnswer {
    if (id != PromptId.BACKGROUND_CONNECTION || eligibility != Eligibility.ELIGIBLE || triggers.isEmpty()) return this
    val lateFloored = record.floorUntil > now &&
        now < PromptBackoffPolicy.floorStart(record) + PromptBackoffPolicy.LATE_DELIVERY_FLOOR_MS
    val remaining = if (lateFloored) triggers - BackgroundTrigger.LATE_DELIVERY else triggers
    return if (remaining.isEmpty()) ProviderAnswer.NOT_ELIGIBLE else copy(triggers = remaining)
}
