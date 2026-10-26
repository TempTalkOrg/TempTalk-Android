package com.difft.android.startup.prompt

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted back-off state of one prompt. Times are trusted-clock millis; 0 means "not set". The
 * JSON names are pinned so a minified build keeps reading existing state.
 */
data class PromptRecord(
    /** Times the prompt was rendered. */
    @SerializedName("shownCount") val shownCount: Int = 0,
    /** Consecutive declines while the problem persists; the ladder step. */
    @SerializedName("declines") val declines: Int = 0,
    @SerializedName("snoozeUntil") val snoozeUntil: Long = 0,
    @SerializedName("lastShownAt") val lastShownAt: Long = 0,
    /** Set on mount, cleared by any outcome; left over only when the process died while it showed. */
    @SerializedName("showingSince") val showingSince: Long = 0,
    /** Primary action taken, result not yet seen. */
    @SerializedName("pendingActionAt") val pendingActionAt: Long = 0,
    /**
     * NOTIFICATIONS only: denials returned by the system permission dialog; 2 or more means the
     * system route is exhausted and the app dialog is used.
     */
    @SerializedName("systemDenials") val systemDenials: Int = 0,
    /** NOTIFICATIONS only: the rationale flag read before the pending system request. */
    @SerializedName("rationaleBefore") val rationaleBefore: Boolean = false,
    /** OPTIONAL_UPDATE only: the offer this record refers to. */
    @SerializedName("offerKey") val offerKey: String? = null,
)

/**
 * The startup prompt records under one app_state key (#1355). Every mutation is a single
 * read-modify-write inside `DataStore.edit`, so concurrent writers never lose each other's changes.
 * An absent or blank value is an empty map.
 */
@Singleton
class StartupPromptStore @Inject constructor(
    @param:AppStateDataStore private val dataStore: DataStore<Preferences>,
    private val gson: Gson,
) {

    suspend fun read(): Map<PromptId, PromptRecord> = decodeState(dataStore.data.first())

    suspend fun update(transform: (Map<PromptId, PromptRecord>) -> Map<PromptId, PromptRecord>): Map<PromptId, PromptRecord> {
        var result: Map<PromptId, PromptRecord> = emptyMap()
        dataStore.edit { prefs ->
            val next = transform(decodeState(prefs))
            prefs[AppStateKeys.STARTUP_PROMPT_STATE] = encode(next)
            result = next
        }
        return result
    }

    private fun encode(records: Map<PromptId, PromptRecord>): String =
        gson.toJson(records.mapKeys { (id, _) -> id.name })

    private fun decodeState(prefs: Preferences): Map<PromptId, PromptRecord> {
        val json = prefs[AppStateKeys.STARTUP_PROMPT_STATE]
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching { decode(json) }.getOrElse {
            L.w { "[StartupPrompt] state unreadable, starting clean error=${it.javaClass.simpleName}" }
            emptyMap()
        }
    }

    private fun decode(json: String): Map<PromptId, PromptRecord> {
        val root = JsonParser.parseString(json).asJsonObject
        val ids = PromptId.entries.associateBy { it.name }
        return buildMap {
            for ((name, value) in root.entrySet()) {
                // Ids removed in a later version are dropped.
                val id = ids[name] ?: continue
                gson.fromJson(value, PromptRecord::class.java)?.let { put(id, it) }
            }
        }
    }
}
