package com.difft.android.chat.translate.model

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.utils.appScope
import com.difft.android.chat.translate.ModelId
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


internal data class ModelFailureRecord(
    val failCount: Int = 0,
    val lastAttemptAtMs: Long = 0,
    val lastReason: String? = null,
    val nextRetryAtMs: Long = 0,
)

private const val MINUTE_MS = 60_000L






internal fun backoffDelayMs(failCount: Int): Long = when {
    failCount <= 1 -> MINUTE_MS
    failCount == 2 -> 5 * MINUTE_MS
    failCount == 3 -> 30 * MINUTE_MS
    failCount == 4 -> 2 * 60 * MINUTE_MS
    else -> 24 * 60 * MINUTE_MS
}












internal class TranslateModelFailureState(
    private val appState: DataStore<Preferences>,
    private val gson: Gson,
) {
    @Volatile
    private var records: Map<String, ModelFailureRecord> = emptyMap()

    @Volatile
    private var loaded = false

    private val loadMutex = Mutex()

    
    suspend fun ensureLoaded() {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return
            records = readFromDisk()
            loaded = true
        }
    }

    fun failCount(model: ModelId): Int = records[model.code]?.failCount ?: 0

    fun nextRetryAtMs(model: ModelId): Long = records[model.code]?.nextRetryAtMs ?: 0

    fun onAttempt(model: ModelId) {
        val current = records[model.code] ?: ModelFailureRecord()
        put(model, current.copy(lastAttemptAtMs = System.currentTimeMillis()))
    }

    
    fun onFailure(model: ModelId, reason: FailureReason): Long {
        val current = records[model.code] ?: ModelFailureRecord()
        val failCount = current.failCount + 1
        val nextRetryAtMs = System.currentTimeMillis() + backoffDelayMs(failCount)
        put(
            model,
            current.copy(
                failCount = failCount,
                lastReason = reason.name,
                nextRetryAtMs = nextRetryAtMs,
            ),
        )
        return nextRetryAtMs
    }

    
    fun onStuck(model: ModelId) {
        val current = records[model.code] ?: ModelFailureRecord()
        put(model, current.copy(lastReason = FailureReason.STUCK_NO_PROGRESS.name))
    }

    fun onSuccess(model: ModelId) = reset(model)

    fun reset(model: ModelId) {
        if (records[model.code] == null) return
        records = records - model.code
        persist()
    }

    fun clear() {
        records = emptyMap()
        loaded = true
        persist()
    }

    private fun put(model: ModelId, record: ModelFailureRecord) {
        records = records + (model.code to record)
        persist()
    }

    private fun persist() {
        val snapshot = records
        appScope.launch {
            runCatching {
                appState.edit { it[AppStateKeys.TRANSLATE_MODEL_DOWNLOAD_STATE] = gson.toJson(snapshot) }
            }.onFailure {
                L.w { "[TranslateModel] persist failure state failed: ${it.stackTraceToString()}" }
            }
        }
    }

    private suspend fun readFromDisk(): Map<String, ModelFailureRecord> {
        val json = runCatching {
            appState.data.first()[AppStateKeys.TRANSLATE_MODEL_DOWNLOAD_STATE]
        }.getOrNull().orEmpty()
        if (json.isEmpty()) return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, ModelFailureRecord>>() {}.type
            gson.fromJson<Map<String, ModelFailureRecord>>(json, type).orEmpty()
        }.getOrElse {
            L.w { "[TranslateModel] failure state unreadable, starting clean: ${it.stackTraceToString()}" }
            emptyMap()
        }
    }
}
