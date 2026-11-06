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
import com.difft.android.chat.focus.FocusGroupDirectory
import com.difft.android.chat.focus.FocusOwnedMark
import com.difft.android.chat.focus.GroupFocusRepository
import com.difft.android.chat.group.GroupUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** A group's current name as the group layer reports it (gid to name); only an active group with a name is reported. */
typealias GroupNameUpdate = Pair<String, String>

/**
 * Remembers the focus group's display name while the focus is valid, because a dismissed or removed group
 * is deleted locally before its dialog can be shown. Persisted (app_state DataStore, stamped with the
 * account, see [FocusOwnedMark]) for one gid; refreshed when the focus changes and when the focus group is
 * renamed, kept through the invalid state, cleared when focus ends. The name is never logged.
 */
@Singleton
class FocusGroupNameCache internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val repository: GroupFocusRepository,
    private val groups: FocusGroupDirectory,
    private val scope: CoroutineScope,
    private val selfId: () -> String?,
    private val nameUpdates: () -> Flow<GroupNameUpdate> = { emptyFlow() },
) {
    @Inject
    constructor(
        @AppStateDataStore dataStore: DataStore<Preferences>,
        repository: GroupFocusRepository,
        groups: FocusGroupDirectory,
        @AppCoroutineScope scope: CoroutineScope,
        groupUtil: dagger.Lazy<GroupUtil>,
    ) : this(
        dataStore,
        repository,
        groups,
        scope,
        { globalServices.myId.takeIf { it.isNotEmpty() } },
        {
            groupUtil.get().singleGroupsUpdate.mapNotNull { group ->
                val gid = group.gid ?: return@mapNotNull null
                val name = group.name?.takeIf { it.isNotBlank() && group.status == 0 } ?: return@mapNotNull null
                gid to name
            }
        },
    )

    private var started = false

    /** Starts following the effective focus and the focus group's name once, from application startup. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            // Judge only the loaded focus: before the cache loads, `effective` is INACTIVE and would clear the name.
            repository.ensureLoaded()
            repository.effective.collect { focus ->
                when {
                    !focus.active -> clear()
                    !focus.invalid -> focus.focusGid?.let { remember(it, groups.name(it)) }
                }
            }
        }
        scope.launch {
            repository.ensureLoaded()
            nameUpdates().collect { (gid, name) ->
                val focus = repository.effective.value
                if (focus.isFocusedGroup(gid)) remember(gid, name)
            }
        }
    }

    /** The cached name of [gid], or null when none is cached for that group (or it was cached by another account). */
    suspend fun nameFor(gid: String): String? = try {
        val prefs = dataStore.data.first()
        val cachedGid = FocusOwnedMark.decode(prefs[AppStateKeys.FOCUS_GROUP_NAME_GID], selfId())
        prefs[AppStateKeys.FOCUS_GROUP_NAME]?.takeIf { cachedGid == gid && it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        L.w { "[GroupFocus] name cache read failed error=${e.javaClass.simpleName}" }
        null
    }

    private suspend fun remember(gid: String, rawName: String?) {
        val name = rawName?.takeIf { it.isNotBlank() } ?: return
        // Without a known account there is nothing to stamp.
        val owner = selfId() ?: return
        val stampedGid = FocusOwnedMark.encode(owner, gid)
        write { prefs ->
            if (prefs[AppStateKeys.FOCUS_GROUP_NAME_GID] != stampedGid || prefs[AppStateKeys.FOCUS_GROUP_NAME] != name) {
                prefs[AppStateKeys.FOCUS_GROUP_NAME_GID] = stampedGid
                prefs[AppStateKeys.FOCUS_GROUP_NAME] = name
            }
        }
    }

    /** Clears only a name this account cached; another account's mark is left for its owner. */
    private suspend fun clear() = write { prefs ->
        val stamped = prefs[AppStateKeys.FOCUS_GROUP_NAME_GID] ?: return@write
        if (FocusOwnedMark.decode(stamped, selfId()) == null) return@write
        prefs.remove(AppStateKeys.FOCUS_GROUP_NAME_GID)
        prefs.remove(AppStateKeys.FOCUS_GROUP_NAME)
    }

    private suspend fun write(block: (MutablePreferences) -> Unit) {
        try {
            dataStore.edit { block(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[GroupFocus] name cache write failed error=${e.javaClass.simpleName}" }
        }
    }
}
