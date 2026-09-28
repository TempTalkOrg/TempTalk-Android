package com.difft.android.login.entry

/**
 * What a verified sign-in does about the account already on this device — decided right after the
 * code is verified and before the transfer token is redeemed, because redeeming it (or registering)
 * is what revokes the account's other device. Mirrors iOS `DTMigrationV2AccountSwitchPolicy`, in
 * this order, so the two platforms make the same call in every state.
 */
object AccountSwitchPolicy {

    enum class Decision {
        /** Nothing established here: go on as a fresh install. */
        PROCEED_FRESH,

        /** The same account is coming back (a kicked or re-verifying device): keep its data, sign in. */
        PROCEED_SAME_ACCOUNT,

        /** A restore for this account already promoted its data; only the sign-in is missing. */
        RESUME_PENDING_RESTORE,

        /** Another account's data is on this device: the user must agree to wipe it first. */
        REQUIRE_REPLACEMENT,
    }

    /**
     * @param pendingRestoreAccount the account whose promoted restore is still waiting for its
     *   sign-in, if any (the restore flag at SIGNING_IN).
     * @param localAccount the account established on this device, if any; written at sign-in and
     *   kept across a passive kick, so a kicked device is not fresh.
     * @param newAccount the account the code was just verified for.
     */
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

/**
 * Whether a verified sign-in offers a migration first. Mirrors iOS `DTMigrationV2LoginEntryPolicy`
 * line for line: the server has to have offered a transfer (a token came back — that is the only
 * signal that the account has another device to take the history from), a promoted restore is
 * finished before anything else, a re-registering install never migrates, and only a fresh install
 * is offered the import. Everything else is a plain sign-in.
 */
object LoginEntryPolicy {

    enum class Decision {
        /** No transfer offered by the server: plain sign-in, no migration UI. */
        UNSUPPORTED,

        /** Data already promoted here: finish the sign-in, never re-import. */
        RESUME_PENDING_RESTORE,

        /** A used install that is re-registering: plain sign-in. */
        SKIP_REREGISTRATION,

        /** Fresh install with a transfer on offer: show the migration chooser. */
        OFFER_FRESH_INSTALL,

        /** Local state exists (same account coming back): plain sign-in, data kept. */
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
