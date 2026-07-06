package com.difft.android.base.storage

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Degraded, in-memory-only [DataStore] returned by the storage providers when the Tink AEAD
 * can't be built — the Android Keystore failed to load the existing keyset (crash 8d61a948).
 *
 * Returning this stub instead of crashing during Hilt injection lets the app boot
 * "logged-out / empty-config" ([data] always emits [empty]) and route to login. It does
 * **zero I/O** — never touches the keyset, Keystore, AEAD, or `*.pb` files — so a *transient*
 * Keystore failure self-heals on the next cold start, and [updateData] never throws (callers
 * like `SecureConfigStore.saveConfig` have no inner try/catch and must not gain a crash source).
 *
 * @param empty immutable default emitted by [data] and fed to [updateData]
 *   (e.g. `UserAuthData.EMPTY` / `GlobalConfigData.EMPTY`).
 */
internal class UnavailableDataStore<T>(private val empty: T) : DataStore<T> {

    /**
     * Hot, never-completing flow that always holds [empty] — matches the real `DataStore.data`
     * contract (a persistent collector must not finish), unlike a completing `flowOf`. Zero I/O.
     */
    override val data: Flow<T> = MutableStateFlow(empty)

    /** Applies [transform] to [empty] in memory only — nothing persisted, no AEAD/keyset touched. */
    override suspend fun updateData(transform: suspend (T) -> T): T = transform(empty)
}
