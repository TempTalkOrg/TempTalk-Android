package com.difft.android.base.storage.user

import com.difft.android.base.migration.pause.MigrationPauseScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton






@Singleton
class CredentialChangeSignal @Inject constructor() {

    private val state = MutableStateFlow<String?>(null)

    
    @Volatile
    private var last: String? = null

    
    val credentialHash: StateFlow<String?> = state.asStateFlow()

    private val firstLoadState = CompletableDeferred<String?>()

    



    val firstLoad: Deferred<String?> get() = firstLoadState

    
    val isLoaded: Boolean get() = firstLoadState.isCompleted

    
    internal fun publish(baseAuth: String?, loaded: Boolean = true) {
        val current = baseAuth?.takeIf { it.isNotEmpty() }
        if (current != last) {
            last = current
            state.value = current?.let(MigrationPauseScope::credentialHash)
        }
        if (loaded) firstLoadState.complete(state.value)
    }
}
