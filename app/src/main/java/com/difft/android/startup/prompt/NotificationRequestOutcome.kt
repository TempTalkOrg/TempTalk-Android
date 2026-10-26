package com.difft.android.startup.prompt

/**
 * Result of the system POST_NOTIFICATIONS request. The request always passes through the system
 * permission activity, even when it auto-denies, so a pause says nothing; the rationale flag read
 * before (`rb`) and after (`ra`) a denial tells the cases apart (#1355 design §4.2).
 */
sealed interface NotificationRequestOutcome {
    data object Granted : NotificationRequestOutcome

    /** A decline; [countsAsSystemDenial] when the system dialog was visibly answered or dismissed. */
    data class Declined(val countsAsSystemDenial: Boolean) : NotificationRequestOutcome

    /**
     * Denied with the rationale false before and after: the system did not ask (permanent denial,
     * also one that predates this state) or a first dialog was dismissed. The system route is treated
     * as exhausted and the app dialog takes the same slot.
     */
    data object ShowAppDialog : NotificationRequestOutcome

    companion object {
        /** From this many denials on the system route is exhausted. */
        const val PERMANENT_AFTER_DENIALS = 2

        fun map(granted: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean): NotificationRequestOutcome = when {
            granted -> Granted
            // First visible denial, second (now permanent) denial, or dismissed without answering.
            rationaleBefore || rationaleAfter -> Declined(countsAsSystemDenial = true)
            else -> ShowAppDialog
        }

        /** Ask the system only while it can still show its dialog. */
        fun useSystemRequest(permissionRequestable: Boolean, rationaleNow: Boolean, systemDenials: Int): Boolean =
            permissionRequestable && (rationaleNow || systemDenials < PERMANENT_AFTER_DENIALS)
    }
}
