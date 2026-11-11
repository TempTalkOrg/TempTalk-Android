package com.difft.android.login.entry







object AccountSwitchPolicy {

    enum class Decision {
        
        PROCEED_FRESH,

        
        PROCEED_SAME_ACCOUNT,

        
        RESUME_PENDING_RESTORE,

        
        REQUIRE_REPLACEMENT,
    }

    






    fun decide(pendingRestoreAccount: String?, localAccount: String?, newAccount: String): Decision {
        val pending = pendingRestoreAccount?.takeIf { it.isNotBlank() }
        if (pending != null) {
            return if (sameAccount(pending, newAccount)) Decision.RESUME_PENDING_RESTORE else Decision.REQUIRE_REPLACEMENT
        }
        val local = localAccount?.takeIf { it.isNotBlank() } ?: return Decision.PROCEED_FRESH
        return if (sameAccount(local, newAccount)) Decision.PROCEED_SAME_ACCOUNT else Decision.REQUIRE_REPLACEMENT
    }

    fun sameAccount(a: String, b: String): Boolean = a.isNotBlank() && a.trim() == b.trim()
}








object LoginEntryPolicy {

    enum class Decision {
        
        UNSUPPORTED,

        
        RESUME_PENDING_RESTORE,

        
        SKIP_REREGISTRATION,

        
        OFFER_FRESH_INSTALL,

        
        SKIP_LOCAL_STATE_EXISTS,
    }

    fun decide(
        hasTransferContract: Boolean,
        hasPendingRestore: Boolean,
        isFreshTarget: Boolean,
        isReregistering: Boolean,
    ): Decision {
        if (!hasTransferContract) return Decision.UNSUPPORTED
        if (hasPendingRestore) return Decision.RESUME_PENDING_RESTORE
        if (isReregistering) return Decision.SKIP_REREGISTRATION
        return if (isFreshTarget) Decision.OFFER_FRESH_INSTALL else Decision.SKIP_LOCAL_STATE_EXISTS
    }
}
