package com.difft.android.chat.focus.invalid

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.di.AppCoroutineScope
import com.difft.android.chat.focus.FocusOwnedMark
import com.difft.android.chat.focus.GroupFocusRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton











@Singleton
class FocusInvalidController internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val repository: GroupFocusRepository,
    private val scope: CoroutineScope,
    private val selfId: () -> String?,
    private val signedIn: () -> Boolean = { true },
) {
    @Inject
    constructor(
        @AppStateDataStore dataStore: DataStore<Preferences>,
        repository: GroupFocusRepository,
        @AppCoroutineScope scope: CoroutineScope,
    ) : this(dataStore, repository, scope, { globalServices.myId.takeIf { it.isNotEmpty() } }, ::hasSignedInUser)

    




    val isSignedIn: Boolean get() = signedIn()

    
    private val handledEpisode = MutableStateFlow<String?>(null)
    private val dismissedEpisode = MutableStateFlow<String?>(null)

    
    val pendingDialog: StateFlow<PendingFocusDialog?> =
        combine(repository.effective, handledEpisode) { focus, handled ->
            handled?.let { FocusInvalidRules.pendingDialog(focus, it) }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    
    val stripVisible: StateFlow<Boolean> =
        combine(repository.effective, dismissedEpisode) { focus, dismissed ->
            dismissed?.let { FocusInvalidRules.stripVisible(focus, it) } ?: false
        }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch {
            val prefs = try {
                dataStore.data.first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[GroupFocus] invalid marks read failed error=${e.javaClass.simpleName}" }
                null
            }
            val owner = selfId()
            handledEpisode.value = handledEpisode.value
                ?: FocusOwnedMark.decode(prefs?.get(AppStateKeys.FOCUS_INVALID_DIALOG_HANDLED_EPISODE), owner).orEmpty()
            dismissedEpisode.value = dismissedEpisode.value
                ?: FocusOwnedMark.decode(prefs?.get(AppStateKeys.FOCUS_INVALID_STRIP_DISMISSED_EPISODE), owner).orEmpty()
            
            repository.ensureLoaded()
            repository.effective.map { it.invalid }.distinctUntilChanged().collect { invalid -> if (!invalid) forgetMarks() }
        }
    }

    




    fun markDialogHandled(episode: String): Job {
        val owner = selfId()
        if (owner == null || !isCurrentInvalidEpisode(episode, owner)) {
            L.i { "[GroupFocus] invalid dialog handled skipped: episode no longer current" }
            return Job().apply { complete() }
        }
        L.i { "[GroupFocus] invalid dialog handled" }
        handledEpisode.value = episode
        return persist { it[AppStateKeys.FOCUS_INVALID_DIALOG_HANDLED_EPISODE] = FocusOwnedMark.encode(owner, episode) }
    }

    
    fun dismissStrip(episode: String): Job {
        val owner = selfId()
        L.i { "[GroupFocus] invalid strip dismissed" }
        dismissedEpisode.value = episode
        
        if (owner == null) return Job().apply { complete() }
        return persist { it[AppStateKeys.FOCUS_INVALID_STRIP_DISMISSED_EPISODE] = FocusOwnedMark.encode(owner, episode) }
    }

    private fun isCurrentInvalidEpisode(episode: String, owner: String): Boolean {
        val focus = repository.effectiveNow()
        val cacheOwner = repository.state.value.ownerUid
        return focus.invalid && FocusInvalidRules.episodeKey(focus) == episode && (cacheOwner == null || cacheOwner == owner)
    }

    
    fun currentEpisode(): String = FocusInvalidRules.episodeKey(repository.effectiveNow())

    private fun forgetMarks() {
        if (handledEpisode.value.isNullOrEmpty() && dismissedEpisode.value.isNullOrEmpty()) return
        handledEpisode.value = ""
        dismissedEpisode.value = ""
        persist {
            it.remove(AppStateKeys.FOCUS_INVALID_DIALOG_HANDLED_EPISODE)
            it.remove(AppStateKeys.FOCUS_INVALID_STRIP_DISMISSED_EPISODE)
        }
    }

    private fun persist(edit: (MutablePreferences) -> Unit): Job =
        scope.launch {
            try {
                dataStore.edit { edit(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[GroupFocus] invalid marks write failed error=${e.javaClass.simpleName}" }
            }
        }
}

private fun hasSignedInUser(): Boolean {
    val user = globalServices.userManager.getUserData()
    return !user?.baseAuth.isNullOrEmpty() && !user?.account.isNullOrEmpty()
}
