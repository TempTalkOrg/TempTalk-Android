package com.difft.android.migration.v2.handoff

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.PeerApps
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.user.UserData
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton










@Singleton
class MigrationHandoffGate @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userManager: StorageBoundUserManager,
) {

    




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

    
    fun canHandOver(): Boolean {
        if (!PeerApps.isLegacy(context)) return false
        return localState() == HandoffState.READY
    }

    
    fun canReceive(): Boolean {
        if (PeerApps.isLegacy(context)) return false
        val state = localState()
        if (state != HandoffState.NONE) L.i { "[MigrationV2][Handoff] receive blocked: this install already holds account material state=$state" }
        return state == HandoffState.NONE
    }
}
