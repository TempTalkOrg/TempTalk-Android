package com.difft.android.base.storage.user

import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.storage.schema.UserAuthData
import com.difft.android.base.user.UserManager

/**
 * Additive sub-interface of [UserManager] for callers that want direct,
 * suspending access to the underlying DataStore namespaces without going
 * through the legacy [UserManager.update] `UserData` blob façade
 * (issue #725, Task 7).
 *
 * **Why this exists**: [UserManager.update] stays non-suspend
 * (binary-compatible with 157 existing call sites). New code that is already
 * in coroutine context can call:
 *
 *  - [updateAuth]     to mutate just the encrypted auth fields, without
 *                     copying a 43-field `UserData` snapshot.
 *  - [updateAppState] to mutate just one UX preference key, without going
 *                     through the `UserData` façade at all.
 *
 * Both routes converge on the same underlying DataStore instances and the
 * same in-memory snapshot the legacy API exposes — see
 * [com.difft.android.base.storage.user.StorageBoundUserManagerImpl].
 *
 * **Hilt binding**: provided as both [UserManager] AND [StorageBoundUserManager]
 * from [com.difft.android.base.di.module.UserInfoModule]. The same `@Singleton`
 * instance backs both.
 *
 * **Lifecycle helpers** ([warmUp], [clearAuthOnly], [clearAll]) are the entry
 * points used by `TempTalkApplication.initUserData()` and
 * `LogoutManagerImpl` respectively. See design report §7, §8.2 (Island 1).
 */
interface StorageBoundUserManager : UserManager {

    /**
     * Atomically read-modify-write the encrypted [UserAuthData] in
     * `secure_user.pb`.
     *
     * The block receives the current [UserAuthData] and must return a new one
     * (typically via `.copy(...)`). No-op writes (block returns the unchanged
     * value) skip disk I/O.
     *
     * Updates the in-memory snapshot before returning so subsequent
     * non-suspend reads via [getUserData] see the new auth fields immediately.
     */
    suspend fun updateAuth(block: UserAuthData.() -> UserAuthData)

    /**
     * Atomically write a single typed [Preferences.Key] in
     * `app_state.preferences_pb`.
     *
     * Updates the in-memory snapshot before returning. The snapshot mirror is
     * best-effort: if the [key] does not correspond to a UserData field, the
     * snapshot is unchanged (e.g., for keyboard heights or call counters that
     * never lived in UserData).
     *
     * For hot-write fields (`AppStateKeys.LAST_USE_TIME`, image-editor
     * seekbar keys), callers should use the throttling helpers from Task 5
     * (`PendingLastUseTime`, image-editor `onStopTrackingTouch`) instead.
     */
    suspend fun <T> updateAppState(key: Preferences.Key<T>, value: T)

    /**
     * Pre-warm the in-memory snapshot from both DataStores. Called from
     * `TempTalkApplication.initUserData()`. Bounded by the caller's
     * `runBlocking { withTimeoutOrNull(2000) { ... } }` envelope.
     */
    suspend fun warmUp()

    /**
     * Clears the encrypted auth DataStore and mirrors EMPTY to legacy SP under
     * the implementation's `writeMutex`. Used by
     * `LogoutManagerImpl.doLogoutWithoutRemoveData()` — auth-only branch.
     *
     * Acquires `writeMutex.withLock` before touching any storage. Concurrent
     * `userManager.update {}` calls also block on the same mutex — either side
     * sees a consistent snapshot.
     */
    suspend fun clearAuthOnly()

    /**
     * Clears the encrypted auth DataStore, the plain app-state DataStore, and
     * the legacy SP mirror under the implementation's `writeMutex`. Used by
     * `LogoutManagerImpl.doLogout()` — full-clear branch.
     *
     * Acquires `writeMutex.withLock` before touching any storage. The two
     * DataStore clears run in parallel under the mutex.
     */
    suspend fun clearAll()
}
