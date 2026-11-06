package com.difft.android.base.storage.user

import com.difft.android.base.migration.pause.MigrationPauseScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The session credential as [StorageBoundUserManagerImpl] holds it in memory, published as a hash
 * on every change. One funnel for every way a credential is installed (login, migration handoff,
 * recovery), so the migration message pause can follow the current login (#1382).
 */
@Singleton
class CredentialChangeSignal @Inject constructor() {

    private val state = MutableStateFlow<String?>(null)

    // Written only under the user manager's memory lock.
    @Volatile
    private var last: String? = null

    /** A hash of the current credential; null when signed out or not yet loaded. Never the credential itself. */
    val credentialHash: StateFlow<String?> = state.asStateFlow()

    private val firstLoadState = CompletableDeferred<String?>()

    /**
     * Completed once, when the stored user data is first read in this process, with the
     * [credentialHash] at that moment (null: signed out). Never changed by a later sign-in or logout.
     */
    val firstLoad: Deferred<String?> get() = firstLoadState

    /** True once the stored user data has been read in this process; see [firstLoad]. */
    val isLoaded: Boolean get() = firstLoadState.isCompleted

    /** [loaded]: the user data is in memory (null before the stored data is read at start). */
    internal fun publish(baseAuth: String?, loaded: Boolean = true) {
        val current = baseAuth?.takeIf { it.isNotEmpty() }
        if (current != last) {
            last = current
            state.value = current?.let(MigrationPauseScope::credentialHash)
        }
        if (loaded) firstLoadState.complete(state.value)
    }
}
