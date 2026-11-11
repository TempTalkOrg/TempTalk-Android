package com.difft.android.startup.prompt






sealed interface NotificationRequestOutcome {
    data object Granted : NotificationRequestOutcome

    
    data class Declined(val countsAsSystemDenial: Boolean) : NotificationRequestOutcome

    




    data object ShowAppDialog : NotificationRequestOutcome

    companion object {
        
        const val PERMANENT_AFTER_DENIALS = 2

        fun map(granted: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean): NotificationRequestOutcome = when {
            granted -> Granted
            
            rationaleBefore || rationaleAfter -> Declined(countsAsSystemDenial = true)
            else -> ShowAppDialog
        }

        
        fun useSystemRequest(permissionRequestable: Boolean, rationaleNow: Boolean, systemDenials: Int): Boolean =
            permissionRequestable && (rationaleNow || systemDenials < PERMANENT_AFTER_DENIALS)
    }
}
