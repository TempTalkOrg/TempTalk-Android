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





data class PromptRecord(
    
    @SerializedName("shownCount") val shownCount: Int = 0,
    
    @SerializedName("declines") val declines: Int = 0,
    @SerializedName("snoozeUntil") val snoozeUntil: Long = 0,
    @SerializedName("lastShownAt") val lastShownAt: Long = 0,
    
    @SerializedName("showingSince") val showingSince: Long = 0,
    
    @SerializedName("pendingActionAt") val pendingActionAt: Long = 0,
    



    @SerializedName("systemDenials") val systemDenials: Int = 0,
    
    @SerializedName("rationaleBefore") val rationaleBefore: Boolean = false,
    
    @SerializedName("offerKey") val offerKey: String? = null,
    
    @SerializedName("floorUntil") val floorUntil: Long = 0,
    
    @SerializedName("switchedOff") val switchedOff: Boolean = false,
)






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

    



    suspend fun recordBackgroundConnectionSwitch(on: Boolean, now: Long) {
        update { records ->
            val current = records[PromptId.BACKGROUND_CONNECTION]
            when {
                !on -> records.edit(PromptId.BACKGROUND_CONNECTION) { PromptBackoffPolicy.switchedOff(it, now) }
                current?.switchedOff == true -> records + (PromptId.BACKGROUND_CONNECTION to current.copy(switchedOff = false))
                else -> records
            }
        }
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
                
                val id = ids[name] ?: continue
                gson.fromJson(value, PromptRecord::class.java)?.let { put(id, it) }
            }
        }
    }
}
