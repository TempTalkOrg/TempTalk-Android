@file:Suppress("DEPRECATION") // Reads legacy secure_prefs EncryptedSharedPreferences
                              // for R3 corruption recovery (issue #725).

package com.difft.android.base.storage.user

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.base.storage.di.SecureUserDataStore
import com.difft.android.base.storage.schema.UserAuthData
import com.difft.android.base.storage.schema.UserAuthDataMapper
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.appScope
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implements both the legacy [UserManager] interface and the new
 * suspend-aware [StorageBoundUserManager] sub-interface (issue #725, Task 7).
 *
 * **Concurrency model** (post-perf-fix):
 *  - Memory updates AND the disk-write `launch` enqueue both happen inside
 *    [memLock] (`synchronized`). The lock is held for nanoseconds — `launch`
 *    only enqueues onto [writeDispatcher] and never performs I/O. Holding
 *    both operations atomically is critical: it guarantees that any thread
 *    observing the new in-memory snapshot also sees the corresponding write
 *    already submitted to [writeDispatcher]'s FIFO queue. Without this
 *    atomicity, `warmUp`'s R3 credential recovery could race with a
 *    concurrent `clearAll` and resurrect logged-out credentials on disk
 *    (the wipe enqueues first, then the recovery write lands after).
 *  - Disk writes go through `appScope.launch(writeDispatcher)` —
 *    fire-and-forget by default, matching the old SP `apply()` semantics.
 *    The calling thread (often Main) is not blocked.
 *  - [writeDispatcher] is `Dispatchers.IO.limitedParallelism(1)` — a
 *    single-threaded view that strictly serializes writes in submission
 *    order. Combined with the in-lock enqueue above, this guarantees:
 *      memory-update-order == submission-order == execution-order
 *    so disk always converges to the **last** in-memory snapshot.
 *  - `commit = true` callers (logout, login-critical writes) wrap the
 *    launch with `runBlocking { job.join() }` (non-suspend variants) or
 *    `job.join()` (suspend variants) to wait for durable persistence
 *    before returning.
 *  - We use the standalone [appScope] (process-wide, `Dispatchers.IO +
 *    SupervisorJob`) instead of the Hilt-provided `@Named("application")`
 *    CoroutineScope to avoid triggering `BaseHiltProvider`'s `application`
 *    extension during Singleton eager-init — which runs *before*
 *    `ApplicationHelper.init()` in `TempTalkApplication.onCreate()` and
 *    would otherwise crash with `UninitializedPropertyAccessException`.
 *
 * **Routing rules** (unchanged from initial #725):
 *  - 17 auth fields → [secureUserStore] (`secure_user.pb`, encrypted Tink AEAD).
 *  - Remaining UX/state fields → [appStateStore] (`app_state.preferences_pb`, plain).
 *
 * **R3 recovery** (`readAuthDataOrRecover`): if reading `secure_user`
 * throws [CorruptionException], reset the DataStore to EMPTY, fall back to
 * the legacy SP via [readLegacyUserDataBlob]. The recovered payload is
 * persisted back to `secure_user.pb` by [warmUp] under [memLock] so a
 * concurrent [clearAll] cannot be overwritten by a stale write-back.
 *
 * **Logout islands** (§8.2 Island 1):
 *  - [clearAuthOnly] / [clearAll] — suspend, always await disk completion
 *    (logout flow must guarantee data is wiped before proceeding).
 */
@Singleton
class StorageBoundUserManagerImpl @Inject constructor(
    @param:SecureUserDataStore
    private val secureUserStore: DataStore<UserAuthData>,
    @param:AppStateDataStore
    private val appStateStore: DataStore<Preferences>,
    @param:ApplicationContext
    private val context: Context,
    private val gson: Gson,
) : StorageBoundUserManager {

    @Volatile
    private var inMemorySnapshot: UserData? = null

    /**
     * Lock for in-memory snapshot mutation. Held only for the duration of
     * a copy-on-write assignment — nanoseconds, never holds during I/O.
     * Reentrant (JVM `synchronized`) so nested calls on the same thread
     * cannot deadlock.
     */
    private val memLock = Any()

    /**
     * Single-threaded view of [Dispatchers.IO] used to dispatch all disk
     * writes. Guarantees submission-order == execution-order so concurrent
     * `update()` callers see disk converge to the latest in-memory snapshot
     * (see class KDoc for the rationale).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val writeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    // region UserManager surface (legacy, non-suspend)

    /**
     * Snapshot-first read: returns the warmed snapshot if present, else null.
     * Once [warmUp] has run, all reads hit memory. Pre-warm-up callers get
     * null and should not assume any persisted data is loaded.
     */
    override fun getUserData(): UserData? = inMemorySnapshot

    /**
     * Non-suspend full-blob setter. Memory updated synchronously; disk
     * write is async (`commit = false`) or sync (`commit = true`).
     */
    override fun setUserData(userData: UserData, commit: Boolean) {
        // memLock covers BOTH the memory swap and the launch enqueue so the
        // disk write enters writeDispatcher's queue in the same order memory
        // updates land (see class KDoc).
        val job: Job = synchronized(memLock) {
            val previous = inMemorySnapshot
            inMemorySnapshot = userData
            appScope.launch(writeDispatcher) {
                writeAll(previous, userData)
            }
        }
        if (commit) {
            // Non-suspend bridge: await the async write enqueued above. Only
            // invoked when `commit = true` (logout / login-critical paths).
            // See class KDoc.
            @Suppress("BanRunBlockingOutsideTests")
            runBlocking { job.join() }
        }
    }

    /**
     * Non-suspend incremental update. Memory updated synchronously under
     * [memLock]; disk write fires on [appScope]. `commit = true`
     * blocks the caller until persistence completes (logout / login-critical
     * paths); `commit = false` (default) returns immediately, matching the
     * old SP `apply()` semantics — no main-thread stall on disk IO.
     */
    override fun update(commit: Boolean, config: UserData.() -> Unit) {
        // memLock covers BOTH the memory update and the launch enqueue —
        // see class KDoc. No-op writes (copied == current) skip both.
        val job: Job = synchronized(memLock) {
            val current = inMemorySnapshot ?: UserData()
            val copied = current.copy().apply(config)
            if (copied == current) return  // no-op write — skip disk
            inMemorySnapshot = copied
            appScope.launch(writeDispatcher) {
                writeAll(current, copied)
            }
        }
        if (commit) {
            // Same rationale as setUserData(commit = true) above.
            @Suppress("BanRunBlockingOutsideTests")
            runBlocking { job.join() }
        }
    }

    // endregion

    // region StorageBoundUserManager surface (suspend)

    override suspend fun updateAuth(block: UserAuthData.() -> UserAuthData) {
        // memLock covers BOTH the memory update and the launch enqueue — see class KDoc
        // for the credential-resurrection race this closes vs. concurrent clearAll.
        // The suspend caller awaits via .join() outside the lock; an orphan write on
        // cancellation is benign because memory was already committed atomically and
        // disk will converge to it.
        val job: Job = synchronized(memLock) {
            val previous = inMemorySnapshot ?: UserData()
            val authView = UserAuthDataMapper.fromUserData(previous)
            val updated = authView.block()
            if (updated == authView) return  // no-op write — skip disk
            inMemorySnapshot = UserAuthDataMapper.toUserData(updated, previous)
            appScope.launch(writeDispatcher) {
                runCatching {
                    // Preserve `migrationV1Completed` from the on-disk record —
                    // UserAuthDataMapper.fromUserData() always returns false for this marker,
                    // so a naive `updateData { newAuth }` would reset it on every auth write
                    // and re-trigger SecureUserSpMigration on the next cold start.
                    secureUserStore.updateData { current ->
                        updated.copy(migrationV1Completed = current.migrationV1Completed)
                    }
                }.onFailure {
                    L.e {
                        "[Storage][UserManager] updateAuth secureUserStore write failed: " +
                            it.stackTraceToString()
                    }
                }
            }
        }
        job.join()
    }

    override suspend fun <T> updateAppState(key: Preferences.Key<T>, value: T) {
        // memLock covers BOTH the memory update and the launch enqueue — see updateAuth.
        val job: Job = synchronized(memLock) {
            val previous = inMemorySnapshot ?: UserData()
            val updated = UserDataFieldRouter.applyAppStateChangeToSnapshot(previous, key, value)
            if (updated != previous) {
                inMemorySnapshot = updated
            }
            appScope.launch(writeDispatcher) {
                runCatching {
                    appStateStore.edit { it[key] = value }
                }.onFailure {
                    L.e {
                        "[Storage][UserManager] updateAppState ${key.name} write failed: " +
                            it.stackTraceToString()
                    }
                }
            }
        }
        job.join()
    }

    override suspend fun warmUp() {
        val (auth, needsWriteBack) = readAuthDataOrRecover()
        val appState = readAppStateOrRecover()
        val combined = UserDataFieldRouter.compose(auth, appState, fallback = null)

        // memLock covers BOTH the snapshot population and the R3 write-back launch
        // enqueue. This is the CREDENTIAL-RESURRECTION FIX: previously the launch
        // happened outside the lock, so a concurrent clearAll could (1) acquire
        // memLock, see snapshot already populated, (2) wipe the snapshot, (3)
        // enqueue its wipe write — all BEFORE warmUp got a chance to enqueue
        // its R3 recovery write. The recovery write would then land AFTER the wipe
        // on writeDispatcher's single-threaded queue and resurrect cleared
        // credentials. Holding the lock across the enqueue makes the ordering
        // memory-update == queue-position, closing the race.
        val writeBackJob: Job? = synchronized(memLock) {
            // Skip if a concurrent write (clearAll/update/setUserData) has already populated
            // the snapshot — those writes already reflect the latest state and must not be
            // overwritten by stale disk reads taken before this lock was acquired.
            if (inMemorySnapshot != null) return@synchronized null
            inMemorySnapshot = combined
            if (!needsWriteBack) return@synchronized null
            appScope.launch(writeDispatcher) {
                runCatching { secureUserStore.updateData { auth } }
                    .onFailure {
                        L.e {
                            "[Storage][UserManager][R3] write-back failed: " +
                                it.stackTraceToString()
                        }
                    }
            }
        }

        writeBackJob?.join()

        L.i {
            "[Storage][UserManager] warmUp complete " +
                "hasAuth=${!combined.baseAuth.isNullOrEmpty()} " +
                "appStateKeys=${appState.asMap().size}"
        }
    }

    // endregion

    // region Logout islands

    override suspend fun clearAuthOnly() {
        // memLock covers BOTH the snapshot clear and the launch enqueue —
        // see class KDoc + warmUp for the credential-resurrection rationale.
        val job: Job = synchronized(memLock) {
            val previous = inMemorySnapshot ?: UserData()
            inMemorySnapshot = previous.copy(
                // Session credentials — CLEARED.
                baseAuth = null,
                microToken = null,
                passcode = null,
                passcodeAttempts = 0,
                pattern = null,
                patternAttempts = 0,
                signalingKey = null,
                contactRequestStatus = null,
                aciIdentityPublicKey = null,
                aciIdentityPrivateKey = null,
                aciIdentityOldPublicKey = null,
                aciIdentityOldPrivateKey = null,
                aciIdentityKeyGenTime = 0L,
                // Self-hosted proxy state — CLEARED. The share-link embeds the TURN
                // `static-auth-secret` (when present); that's user-bound credential
                // material and must not survive passive logout on shared devices.
                proxyShareLink = null,
                proxyEnabled = false,
                proxyProtectCallIp = false,
                // Identity fields (`account`, `customUid`, `email`, `phoneNumber`) deliberately
                // preserved so the re-login screen can display "logged out as <account>".
            )
            appScope.launch(writeDispatcher) {
                runCatching {
                    secureUserStore.updateData { current ->
                        // UserAuthData uses non-nullable Strings (proto wire format requires this);
                        // empty string is the "absent" sentinel — converted back to `null` by
                        // UserAuthDataMapper.toUserData when reassembling the UserData view.
                        current.copy(
                            baseAuth = "",
                            microToken = "",
                            passcode = "",
                            pattern = "",
                            signalingKey = "",
                            contactRequestStatus = "",
                            aciIdentityPublicKey = "",
                            aciIdentityPrivateKey = "",
                            aciIdentityOldPublicKey = "",
                            aciIdentityOldPrivateKey = "",
                            aciIdentityKeyGenTime = 0L,
                            // Self-hosted proxy state — cleared on passive logout for
                            // the same reason: the share-link embeds a TURN secret.
                            proxyShareLink = "",
                            proxyEnabled = false,
                            proxyProtectCallIp = false,
                        )
                    }
                }.onFailure {
                    L.e { "[Logout][Island1A] secureUser clear failed: ${it.stackTraceToString()}" }
                }
            }
        }

        // Logout must guarantee on-disk wipe before returning — await the job.
        job.join()

        L.i { "[Logout][Island1A] clearAuthOnly complete" }
    }

    override suspend fun clearAll() {
        // memLock covers BOTH the snapshot wipe and the launch enqueue —
        // see class KDoc + warmUp for the credential-resurrection rationale.
        val job: Job = synchronized(memLock) {
            inMemorySnapshot = UserData()
            // Both stores cleared in parallel inside a single launch.
            // Note: the inner launches inherit writeDispatcher (single-threaded),
            // so they execute sequentially on the dispatcher thread instead of
            // in true parallel. Acceptable overhead for the ordering guarantee.
            appScope.launch(writeDispatcher) {
                coroutineScope {
                    launch {
                        runCatching {
                            // Stamp marker=true so the next cold start treats this empty payload as
                            // already-migrated and skips SecureUserSpMigration.
                            secureUserStore.updateData { UserAuthData(migrationV1Completed = true) }
                        }.onFailure {
                            L.e { "[Logout][Island1B] secureUser clear failed: ${it.stackTraceToString()}" }
                        }
                    }
                    launch {
                        runCatching {
                            appStateStore.edit { mut ->
                                // Preserve MIGRATION_VERSION across the clear — avoids a no-op
                                // re-run of AppStateMigrations on next cold start.
                                val version = mut[AppStateKeys.MIGRATION_VERSION]
                                mut.clear()
                                if (version != null) mut[AppStateKeys.MIGRATION_VERSION] = version
                            }
                        }.onFailure {
                            L.e { "[Logout][Island1B] appState clear failed: ${it.stackTraceToString()}" }
                        }
                    }
                }
            }
        }

        // Logout must guarantee on-disk wipe before returning.
        job.join()

        L.i { "[Logout][Island1B] clearAll complete" }
    }

    // endregion

    // region Internal write/read helpers

    /**
     * Persist [updated] across both DataStores. Called from a coroutine
     * launched on [appScope]; memory snapshot is already set by
     * the caller before this runs.
     */
    private suspend fun writeAll(previous: UserData?, updated: UserData) {
        val diff = UserDataFieldRouter.diff(previous, updated)

        diff.newAuth?.let { newAuth ->
            // Same marker-preservation rationale as updateAuth() above — the diff payload
            // always carries `migrationV1Completed = false` from UserAuthDataMapper.fromUserData().
            runCatching {
                secureUserStore.updateData { current ->
                    newAuth.copy(migrationV1Completed = current.migrationV1Completed)
                }
            }.onFailure {
                L.e {
                    "[Storage][UserManager] secureUserStore write failed: " +
                        it.stackTraceToString()
                }
            }
        }

        if (diff.appStateChanges.isNotEmpty()) {
            runCatching {
                appStateStore.edit { mut ->
                    UserDataFieldRouter.applyAppStateChanges(mut, diff.appStateChanges)
                }
            }.onFailure {
                L.e {
                    "[Storage][UserManager] appStateStore write failed (${diff.appStateChanges.size} keys): " +
                        it.stackTraceToString()
                }
            }
        }
    }

    /**
     * One-shot read of the legacy `secure_prefs` EncryptedSharedPreferences blob.
     * Used by the R3 corruption-recovery path when DataStore reads fail.
     * Returns null on any failure (fresh install, Keystore reset, decrypt fail).
     */
    private fun readLegacyUserDataBlob(): UserData? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                "secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            val key = "com.difft.chative.base.user.SimpleUserManager\$Companion.SHARED_PREFERENCES_KEY_USERDATA"
            val json = prefs.getString(key, null) ?: return null
            gson.fromJson(json, UserData::class.java)
        } catch (e: Throwable) {
            L.e { "[StorageBoundUserManagerImpl][R3] legacy SP read failed: ${e.stackTraceToString()}" }
            null
        }
    }

    /**
     * R3 recovery for `secure_user`: on [CorruptionException], resets the DataStore,
     * falls back to legacy SP. Returns the recovered [UserAuthData] AND a flag
     * indicating whether the caller should persist it back under [memLock].
     */
    private suspend fun readAuthDataOrRecover(): Pair<UserAuthData, Boolean> {
        return try {
            secureUserStore.data.first() to false
        } catch (e: CorruptionException) {
            L.e {
                "[Storage][UserManager][R3] secure_user CorruptionException: " +
                    e.stackTraceToString()
            }
            // Stamp migrationV1Completed=true so the next cold start does NOT treat this empty
            // value as "needs migration" and re-project from the retained legacy SP.
            runCatching {
                secureUserStore.updateData { UserAuthData(migrationV1Completed = true) }
            }

            val legacyAuth = runCatching {
                readLegacyUserDataBlob()?.let { UserAuthDataMapper.fromLegacyComplete(it) }
            }.getOrNull() ?: UserAuthData(migrationV1Completed = true)

            val recovered = legacyAuth.account.isNotEmpty()
            if (recovered) {
                L.i { "[Storage][UserManager][R3] secure_user recovered from legacy SP" }
            } else {
                L.w { "[Storage][UserManager][R3] secure_user no legacy data — user will re-login" }
            }
            legacyAuth to recovered
        } catch (e: Throwable) {
            L.e {
                "[Storage][UserManager] secure_user read failed: " +
                    e.stackTraceToString()
            }
            UserAuthData.EMPTY to false
        }
    }

    /** R3 recovery for app_state — corruption here is non-fatal; UI defaults apply. */
    private suspend fun readAppStateOrRecover(): Preferences {
        return try {
            appStateStore.data.first()
        } catch (e: CorruptionException) {
            L.e {
                "[Storage][UserManager][R3] app_state CorruptionException: " +
                    e.stackTraceToString()
            }
            runCatching { appStateStore.edit { it.clear() } }
            runCatching { appStateStore.data.first() }.getOrDefault(emptyPreferences())
        } catch (e: Throwable) {
            L.e {
                "[Storage][UserManager] app_state read failed: " +
                    e.stackTraceToString()
            }
            emptyPreferences()
        }
    }

    // endregion
}
