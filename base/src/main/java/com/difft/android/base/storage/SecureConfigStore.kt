package com.difft.android.base.storage

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.di.SecureConfigDataStore
import com.difft.android.base.storage.schema.GlobalConfigData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin facade over the encrypted `secure_config.pb` `DataStore<GlobalConfigData>`
 * (issue #725, Task 4). Replaces the two-instance
 * [androidx.security.crypto.EncryptedSharedPreferences] collision between
 * `:network/GlobalConfigsManager` and `:call/CallServiceUrlManager` that both
 * opened the same `secure_global_config.xml` file.
 *
 * **Why per-field flows instead of `Flow<GlobalConfigData>`**: callers only
 * observe a single field at a time. Per-field `.distinctUntilChanged()` narrows
 * the recomputation surface — a write to `config` does not trigger downstream
 * recomputation of `callServiceUrlStateV3` observers.
 *
 * **Why `.catch` BEFORE `.map`**: the catch handler must operate on the upstream
 * `Flow<GlobalConfigData>` so it can `emit(EMPTY)` and let the downstream `.map`
 * extract the empty string. Placing `.catch` after `.map` would force an
 * `UNCHECKED_CAST` because the emission type would be `String` whereas the
 * upstream error site emits `GlobalConfigData`.
 *
 * **Corruption recovery**: `CorruptionException` from
 * [com.difft.android.base.storage.EncryptedSerializer] is mapped to
 * [GlobalConfigData.EMPTY]. Other throwables (cancellation, IO) propagate.
 */
@Singleton
class SecureConfigStore @Inject constructor(
    @param:SecureConfigDataStore private val dataStore: DataStore<GlobalConfigData>,
) {

    /** `GlobalConfigsManager`'s `NewGlobalConfig` Gson blob; empty string = no cached config. */
    val configFlow: Flow<String> = dataStore.data
        .catch { e -> handleReadError(e, "configFlow") }
        .map { it.config }
        .distinctUntilChanged()

    /** `CallServiceUrlManager`'s `CallServiceUrlDiskState` JSON; empty string = no cached state. */
    val callServiceUrlStateV3Flow: Flow<String> = dataStore.data
        .catch { e -> handleReadError(e, "callServiceUrlStateV3Flow") }
        .map { it.callServiceUrlStateV3 }
        .distinctUntilChanged()

    suspend fun saveConfig(config: String) {
        dataStore.updateData { it.copy(config = config) }
    }

    suspend fun saveCallServiceUrlStateV3(state: String) {
        dataStore.updateData { it.copy(callServiceUrlStateV3 = state) }
    }

    private suspend fun FlowCollector<GlobalConfigData>.handleReadError(
        e: Throwable,
        site: String,
    ) {
        when (e) {
            is CorruptionException -> {
                L.w { "[Storage][secure_config] $site decrypt/decode failed: ${e.javaClass.simpleName}" }
                emit(GlobalConfigData.EMPTY)
            }
            else -> throw e // CancellationException + IOException propagate.
        }
    }
}
