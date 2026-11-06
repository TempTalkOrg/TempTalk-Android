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

/**
 * The login a migration message pause belongs to (#1382): a pause started under one account,
 * environment and credential ends when that login is no longer the current one.
 */
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

    /**
     * Runs the scope check whenever a credential is installed in this process, by any path. Losing
     * the credential (logout) is not acted on here: a logout keeps messaging closed until its
     * process exits, and cold start clears a signed-out pause.
     */
    fun watchCredentialChanges(scope: CoroutineScope) {
        if (!watching.compareAndSet(false, true)) return
        scope.launch {
            credentialSignal.credentialHash.filterNotNull().collect { discardIfOutOfScope("credential_installed") }
        }
    }

    /** The signed-in login's scope; null when signed out or before the user data has loaded. */
    fun current(): Scope? = userManager.getUserData()?.let(::scopeOf)

    /**
     * The scope check at cold start and at every login: clears a pause that the current login did
     * not start, in any phase. Signed out clears it too, since the login that started it is gone;
     * the sends held by such a pause are dropped first. A user snapshot that has not loaded yet
     * proves nothing, so it is kept, and so is a corrupt record while signed in (#1382 A08).
     */
    suspend fun discardIfOutOfScope(trigger: String) {
        if (pause.state !is MessagingPauseState.Paused && !pause.hasRecord) return
        val user = userManager.getUserData()
        if (user == null) {
            L.w { "[MigrationPause] event=scope_check_skipped trigger=$trigger reason=user_not_loaded" }
            return
        }
        val scope = scopeOf(user)
        // Sends held for the ended login go first, while messaging is still closed.
        val record = pause.record
        val corrupt = pause.hasRecord && record == null
        if (scope != null && corrupt) {
            L.w { "[MigrationPause] event=scope_check_kept trigger=$trigger reason=corrupt_record" }
            return
        }
        val outOfScope = scope == null || record?.matchesScope(scope.accountScope, scope.credentialHash) != true
        // The current login's own sends stay: it may have queued them already.
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
