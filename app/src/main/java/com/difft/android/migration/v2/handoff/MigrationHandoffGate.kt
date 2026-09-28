package com.difft.android.migration.v2.handoff

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.PeerApps
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.user.UserData
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single owner of "may this install take part in the same-device no-login migration, and in
 * which role" — read by the entry screen, the send screen, the status provider and the reconciler,
 * so all four can never disagree. [localState] reports what account material is here; [canHandOver]
 * and [canReceive] are the role gates, each also requiring the right package family.
 *
 * Every read goes through the live snapshot, never a cached copy: a kick-out can clear the
 * credential at any moment.
 */
@Singleton
class MigrationHandoffGate @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userManager: StorageBoundUserManager,
) {

    /**
     * What this install holds, in the tri-state the peer is allowed to see. [HandoffState.READY] also
     * requires the ACI identity key pair — a credential without it would land the new app with a
     * valid-looking session and no keys, which the startup guard turns into a forced sign-out.
     */
    fun localState(userData: UserData? = userManager.getUserData()): HandoffState {
        val account = userData?.account
        if (account.isNullOrEmpty()) return HandoffState.NONE
        val credentialComplete = !userData.baseAuth.isNullOrEmpty() &&
            !userData.microToken.isNullOrEmpty() &&
            !userData.signalingKey.isNullOrEmpty()
        val identityPresent = !userData.aciIdentityPublicKey.isNullOrEmpty() &&
            !userData.aciIdentityPrivateKey.isNullOrEmpty()
        return if (credentialComplete && identityPresent) HandoffState.READY else HandoffState.DATA_ONLY
    }

    /** The retiring package may hand its session over only while a complete session lives here. */
    fun canHandOver(): Boolean {
        if (!PeerApps.isLegacy(context)) return false
        return localState() == HandoffState.READY
    }

    /** The current package may adopt a session only while nothing is here yet. */
    fun canReceive(): Boolean {
        if (PeerApps.isLegacy(context)) return false
        val state = localState()
        if (state != HandoffState.NONE) L.i { "[MigrationV2][Handoff] receive blocked: this install already holds account material state=$state" }
        return state == HandoffState.NONE
    }
}
