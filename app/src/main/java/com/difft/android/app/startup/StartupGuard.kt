package com.difft.android.app.startup

import com.difft.android.base.user.UserData

/**
 * Pure predicate used by the cold-start guard in `MainActivity.processIntent()`.
 *
 * Kept as a top-level `fun` (not an object/class) so unit tests can exercise it
 * without Robolectric.
 *
 * The associated cleanup helper (`cleanupLegacySqlCipherArtifacts`) lives in
 * `StartupCleanup.kt` — it has no UI dependency and runs in
 * `TempTalkApplication.onCreate` alongside the other cleanup tasks. Only this
 * predicate stays in MainActivity scope, because the guard outcome
 * (`doLogoutWithoutRemoveData`) restarts the process and is only acceptable UX
 * once an Activity is on screen.
 */

/**
 * Returns true iff the user is logged in (baseAuth present) but identity keys are
 * missing in UserData — the pre-1.8.1 straggler scenario that PR 1's KeyValue-DB
 * cleanup exposes.
 *
 * Uses `||` so either key missing triggers the guard — paranoid but cheap.
 *
 * Early-returns false for `userData == null` (fresh install / corrupted state —
 * login flow drives) and for empty baseAuth (not logged in — nothing to guard).
 */
fun needsIdentityKeyRelogin(userData: UserData?): Boolean {
    if (userData == null) return false
    if (userData.baseAuth.isNullOrEmpty()) return false

    return userData.aciIdentityPublicKey.isNullOrEmpty()
        || userData.aciIdentityPrivateKey.isNullOrEmpty()
}
