package com.difft.android.startup.prompt

import com.difft.android.base.notification.ReadinessIssue





enum class PromptId {
    
    LEGACY_MIGRATION,

    
    NOTIFICATIONS,

    
    BACKGROUND_CONNECTION,

    
    BACKGROUND_RESTRICTED,

    
    FULL_SCREEN,

    
    OPTIONAL_UPDATE,
}




enum class BackgroundTrigger {
    
    FCM_UNAVAILABLE,

    
    LATE_DELIVERY,
}


enum class GateId { INACTIVE_DEVICE, FORCED_UPDATE, SECURITY, NETWORK_RISK }


enum class Eligibility { ELIGIBLE, NOT_ELIGIBLE, UNKNOWN }


sealed interface PromptOutcome {
    
    data object Declined : PromptOutcome

    
    data object ActionTaken : PromptOutcome

    
    data object Resolved : PromptOutcome

    
    data object Preempted : PromptOutcome
}


data class ProviderAnswer(
    val eligibility: Eligibility,
    
    val issue: ReadinessIssue? = null,
    
    val offerKey: String? = null,
    
    val permissionRequestable: Boolean = false,
    
    val triggers: Set<BackgroundTrigger> = emptySet(),
) {
    companion object {
        val NOT_ELIGIBLE = ProviderAnswer(Eligibility.NOT_ELIGIBLE)
        val UNKNOWN = ProviderAnswer(Eligibility.UNKNOWN)
    }
}





data class PromptTicket(
    val id: PromptId,
    val seq: Long,
    val issue: ReadinessIssue? = null,
    
    val viaSystemRequest: Boolean = false,
    
    val offerKey: String? = null,
    
    val backgroundTrigger: BackgroundTrigger? = null,
)







internal fun ProviderAnswer.forRecord(id: PromptId, record: PromptRecord, now: Long): ProviderAnswer {
    if (id != PromptId.BACKGROUND_CONNECTION || eligibility != Eligibility.ELIGIBLE || triggers.isEmpty()) return this
    val lateFloored = record.floorUntil > now &&
        now < PromptBackoffPolicy.floorStart(record) + PromptBackoffPolicy.LATE_DELIVERY_FLOOR_MS
    val remaining = if (lateFloored) triggers - BackgroundTrigger.LATE_DELIVERY else triggers
    return if (remaining.isEmpty()) ProviderAnswer.NOT_ELIGIBLE else copy(triggers = remaining)
}
