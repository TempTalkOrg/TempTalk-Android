package com.difft.android.login

/**
 * Budget for the as-you-type passcode check on the unlock screen (#1412).
 *
 * Every edit that leaves 4..10 digits in the field is verified so the correct passcode unlocks
 * without a submit. Wrong values on that path are not unlock attempts (a 6-digit passcode is
 * tested at 4 and 5 digits on the way), so they never touch the manual retry ladder; instead they
 * spend this separate budget. Once it is used up, or the manual ladder has started, only a manual
 * submit is verified. The budget is persisted and refilled only by a successful unlock.
 */
internal object PasscodeAutoVerifyPolicy {

    /** Failed manual submits from which the as-you-type check stops. */
    const val MANUAL_ATTEMPTS_LIMIT = 5

    /** Wrong values the as-you-type check may test; entering a 10-digit passcode spends 6. */
    const val AUTO_MISS_LIMIT = 20

    fun allowsAutoVerify(manualAttempts: Int, autoMisses: Int): Boolean =
        manualAttempts < MANUAL_ATTEMPTS_LIMIT && autoMisses < AUTO_MISS_LIMIT
}
