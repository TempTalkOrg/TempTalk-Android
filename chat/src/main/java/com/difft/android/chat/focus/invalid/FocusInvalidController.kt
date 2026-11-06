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

/**
 * The persisted "answered" marks of the invalid focus state and the two UI states derived from them:
 * the forced dialog ([pendingDialog]) and the home status strip ([stripVisible]). Both are pure
 * functions of the effective focus plus a mark ([FocusInvalidRules]); only the marks are stored
 * (app_state DataStore), so a pending dialog survives a process restart without a copy of the state.
 * A mark is the invalid episode ([FocusInvalidRules.episodeKey]), not the version, and is forgotten once
 * the focus is no longer invalid, so a later invalidation always starts a new episode.
 * Both flows stay hidden until the marks are loaded, so a handled dialog never flashes. Marks are stamped
 * with the account ([FocusOwnedMark]): another account's mark is ignored on load.
 */
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

    /**
     * False while signed out: a passive logout keeps app_state (and so a pending invalid state), but "End Focus" and the
     * picker need credentials, so neither the dialog nor the strip may show until the user is signed in again.
     * Read at presentation time; the presenter retries on every resumed host, so a later sign-in shows it.
     */
    val isSignedIn: Boolean get() = signedIn()

    // null = not loaded yet; "" = nothing answered.
    private val handledEpisode = MutableStateFlow<String?>(null)
    private val dismissedEpisode = MutableStateFlow<String?>(null)

    /** The dialog the user still owes an answer, or null. */
    val pendingDialog: StateFlow<PendingFocusDialog?> =
        combine(repository.effective, handledEpisode) { focus, handled ->
            handled?.let { FocusInvalidRules.pendingDialog(focus, it) }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** True while the home status strip is shown. */
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
            // Judge only the loaded focus: before the cache loads, `effective` is INACTIVE.
            repository.ensureLoaded()
            repository.effective.map { it.invalid }.distinctUntilChanged().collect { invalid -> if (!invalid) forgetMarks() }
        }
    }

    /**
     * The user chose to keep the invalid state (picker cancelled): the dialog of [episode] is not shown again.
     * Written only if the effective focus is still invalid in that very episode for this account right now;
     * otherwise (another device ended or changed the focus while the picker was open) it is a no-op.
     */
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

    /** The user closed the status strip for [episode]; a different invalid episode shows it again. */
    fun dismissStrip(episode: String): Job {
        val owner = selfId()
        L.i { "[GroupFocus] invalid strip dismissed" }
        dismissedEpisode.value = episode
        // Without a known account the mark stays in memory only: nothing to stamp.
        if (owner == null) return Job().apply { complete() }
        return persist { it[AppStateKeys.FOCUS_INVALID_STRIP_DISMISSED_EPISODE] = FocusOwnedMark.encode(owner, episode) }
    }

    private fun isCurrentInvalidEpisode(episode: String, owner: String): Boolean {
        val focus = repository.effectiveNow()
        val cacheOwner = repository.state.value.ownerUid
        return focus.invalid && FocusInvalidRules.episodeKey(focus) == episode && (cacheOwner == null || cacheOwner == owner)
    }

    /** The episode of the strip's current focus, for [dismissStrip]. */
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
