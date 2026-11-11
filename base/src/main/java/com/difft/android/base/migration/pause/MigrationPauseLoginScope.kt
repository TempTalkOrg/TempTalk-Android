package com.difft.android.base.migration.pause

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.user.CredentialChangeSignal
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.EnvironmentHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton





@Singleton
class MigrationPauseLoginScope @Inject constructor(
    private val userManager: UserManager,
    private val environmentHelper: EnvironmentHelper,
    private val pause: MigrationMessagePause,
    private val credentialSignal: CredentialChangeSignal,
    private val heldSends: HeldSendJobs,
) {
    private val watching = AtomicBoolean(false)

    data class Scope(val accountScope: String, val credentialHash: String)

    




    fun watchCredentialChanges(scope: CoroutineScope) {
        if (!watching.compareAndSet(false, true)) return
        scope.launch {
            credentialSignal.credentialHash.filterNotNull().collect { discardIfOutOfScope("credential_installed") }
        }
    }

    
    fun current(): Scope? = userManager.getUserData()?.let(::scopeOf)

    





    suspend fun discardIfOutOfScope(trigger: String) {
        if (pause.state !is MessagingPauseState.Paused && !pause.hasRecord) return
        val user = userManager.getUserData()
        if (user == null) {
            L.w { "[MigrationPause] event=scope_check_skipped trigger=$trigger reason=user_not_loaded" }
            return
        }
        val scope = scopeOf(user)
        
        val record = pause.record
        val corrupt = pause.hasRecord && record == null
        if (scope != null && corrupt) {
            L.w { "[MigrationPause] event=scope_check_kept trigger=$trigger reason=corrupt_record" }
            return
        }
        val outOfScope = scope == null || record?.matchesScope(scope.accountScope, scope.credentialHash) != true
        
        if (outOfScope && pause.state is MessagingPauseState.Paused) heldSends.dropAll("login_changed", keepLogin = scope?.credentialHash)
        if (scope == null) {
            pause.clear(owner = null, reason = "signed_out")
        } else {
            pause.discardIfOutOfScope(scope.accountScope, scope.credentialHash)
        }
    }

    private fun scopeOf(user: UserData): Scope? {
        val account = user.account?.takeIf { it.isNotBlank() } ?: return null
        val credential = user.baseAuth?.takeIf { it.isNotBlank() } ?: return null
        return Scope(
            accountScope = MigrationPauseScope.accountScope(environmentHelper.currentEnvironment, account),
            credentialHash = MigrationPauseScope.credentialHash(credential),
        )
    }
}
