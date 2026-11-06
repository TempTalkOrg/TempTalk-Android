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

    suspend fun prepareCall(ticket: NotificationTestCallTicket) {
        require(NotificationTestCallTicket.decode(ticket.encode()) == ticket)
        dataStore.edit { it[AppStateKeys.NOTIF_TEST_CALL_TICKET] = ticket.encode() }
    }

    /** The condition and result update share the durable transaction, including after process death. */
    suspend fun updateCall(runId: String, nowMs: Long, shown: Boolean): NotificationTestCallUpdate {
        var result = NotificationTestCallUpdate.REJECTED
        dataStore.edit { prefs ->
            val ticket = NotificationTestCallTicket.decode(prefs[AppStateKeys.NOTIF_TEST_CALL_TICKET])
            if (ticket == null || !ticket.isValidAt(nowMs)) {
                prefs.remove(AppStateKeys.NOTIF_TEST_CALL_TICKET)
            } else if (ticket.runId == runId) {
                if (ticket.status == NotificationTestCallTicket.Status.SHOWN) {
                    result = NotificationTestCallUpdate.ALREADY_SHOWN
                } else {
                    val status = if (shown) NotificationTestCallTicket.Status.SHOWN else NotificationTestCallTicket.Status.TIMEOUT
                    prefs[AppStateKeys.NOTIF_TEST_CALL_TICKET] = ticket.copy(status = status).encode()
                    prefs[key(NotificationTestKind.CALL)] = NotificationTestResult(
                        if (shown) NotificationTestOutcome.OK else NotificationTestOutcome.FAIL,
                        nowMs,
                        ticket.fingerprint,
                    ).encode()
                    result = NotificationTestCallUpdate.RECORDED
                }
            }
        }
        return result
    }

    suspend fun cancelCall(runId: String) {
        dataStore.edit { prefs ->
            if (NotificationTestCallTicket.decode(prefs[AppStateKeys.NOTIF_TEST_CALL_TICKET])?.runId == runId) {
                prefs.remove(AppStateKeys.NOTIF_TEST_CALL_TICKET)
            }
        }
    }

    suspend fun readCall(nowMs: Long): NotificationTestCallTicket? {
        var ticket: NotificationTestCallTicket? = null
        dataStore.edit { prefs ->
            ticket = NotificationTestCallTicket.decode(prefs[AppStateKeys.NOTIF_TEST_CALL_TICKET])?.takeIf { it.isValidAt(nowMs) }
            if (ticket == null) prefs.remove(AppStateKeys.NOTIF_TEST_CALL_TICKET)
        }
        return ticket
    }

    /** Channel ids the user opened the system settings page of; empty when unreadable. */
    suspend fun readSeenCategories(): Set<String> = try {
        dataStore.data.first()[AppStateKeys.NOTIF_CATEGORY_SEEN].orEmpty()
    } catch (e: IOException) {
        L.w { "[NotificationTest] seen read failed error=${e.javaClass.simpleName}" }
        emptySet()
    }

    suspend fun markCategorySeen(channelId: String) {
        dataStore.edit { it[AppStateKeys.NOTIF_CATEGORY_SEEN] = it[AppStateKeys.NOTIF_CATEGORY_SEEN].orEmpty() + channelId }
    }

    internal fun key(kind: NotificationTestKind): Preferences.Key<String> = when (kind) {
        NotificationTestKind.BANNER -> AppStateKeys.NOTIF_TEST_BANNER
        NotificationTestKind.LOCKSCREEN -> AppStateKeys.NOTIF_TEST_LOCKSCREEN
        NotificationTestKind.CALL -> AppStateKeys.NOTIF_TEST_CALL
    }
}
