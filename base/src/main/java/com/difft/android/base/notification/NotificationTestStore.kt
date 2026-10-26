package com.difft.android.base.notification

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The notification test results in app_state, one key per test (#1350). Malformed values read as absent. */
@Singleton
class NotificationTestStore @Inject constructor(
    @param:AppStateDataStore private val dataStore: DataStore<Preferences>,
) {

    suspend fun read(): Map<NotificationTestKind, NotificationTestResult> {
        val prefs = try {
            dataStore.data.first()
        } catch (e: IOException) {
            L.w { "[NotificationTest] store read failed error=${e.javaClass.simpleName}" }
            return emptyMap()
        }
        return buildMap {
            NotificationTestKind.entries.forEach { kind ->
                NotificationTestResult.decode(prefs[key(kind)])?.let { put(kind, it) }
            }
        }
    }

    suspend fun write(kind: NotificationTestKind, result: NotificationTestResult) {
        dataStore.edit { it[key(kind)] = result.encode() }
    }

    internal fun key(kind: NotificationTestKind): Preferences.Key<String> = when (kind) {
        NotificationTestKind.BANNER -> AppStateKeys.NOTIF_TEST_BANNER
        NotificationTestKind.LOCKSCREEN -> AppStateKeys.NOTIF_TEST_LOCKSCREEN
        NotificationTestKind.CALL -> AppStateKeys.NOTIF_TEST_CALL
    }
}
