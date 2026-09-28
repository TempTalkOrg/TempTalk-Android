package com.difft.android.migration.v2.transfer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.p2p.android.client.SenderHttpClient
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** A send whose body is on the wire and whose outcome this device has not yet learned for certain. */
data class PendingVerdict(
    val session: SenderSessionRef,
    /** Epoch millis the last body byte left; the total wait is measured from here, not from a timer. */
    val bodySentAt: Long,
)

/**
 * The one send awaiting a verdict, kept on disk so the question "did that migration go through?"
 * survives this process. Written when the body is fully sent, cleared as soon as the outcome is
 * known for certain (confirmed, refused, failed, cancelled), and deliberately kept while it is not —
 * a wait that ended as unknown, or a process that died during it. On the next open of the send
 * screen that record is what lets the screen ask `/status` first instead of guessing.
 *
 * Holds the session token in the app's private state store; it is a short-lived credential that
 * only ever answers "how did my own upload go" on a LAN peer, and the record itself is dropped
 * once it is older than the receiver would remember the session anyway.
 */
@Singleton
class PendingVerdictStore @Inject constructor(
    @AppStateDataStore private val store: DataStore<Preferences>,
) {
    private val gson = Gson()

    suspend fun save(pending: PendingVerdict) {
        runCatching { store.edit { it[AppStateKeys.MIGRATION_PENDING_VERDICT] = gson.toJson(pending) } }
            .onFailure { L.w { "[MigrationV2][P2P][Tx] pending verdict not saved: ${it.message}" } }
    }

    /** The pending send, or null when there is none or it is too old to still be answerable. */
    suspend fun load(now: Long = System.currentTimeMillis()): PendingVerdict? {
        val raw = runCatching { store.data.first()[AppStateKeys.MIGRATION_PENDING_VERDICT] }.getOrNull() ?: return null
        val pending = runCatching { gson.fromJson(raw, PendingVerdict::class.java) }.getOrNull()
        if (pending == null || now - pending.bodySentAt > MAX_AGE_MS) {
            clear()
            return null
        }
        return pending
    }

    suspend fun clear() {
        runCatching { store.edit { it.remove(AppStateKeys.MIGRATION_PENDING_VERDICT) } }
            .onFailure { L.w { "[MigrationV2][P2P][Tx] pending verdict not cleared: ${it.message}" } }
    }

    companion object {
        /** Matches the receiver's terminal-record retention: after this nobody can answer anyway. */
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}

/** One `/status` question to a receiver about a session this device opened — the "check again" of the unconfirmed page. */
object VerdictProbe {
    suspend fun check(session: SenderSessionRef): Verdict = withContext(Dispatchers.IO) {
        val token = session.sessionToken ?: return@withContext Verdict.Unknown(Verdict.Unknown.Reason.SESSION_UNKNOWN)
        val response = try {
            SenderHttpClient(session.ip, session.port, session.fingerprint).status(session.sessionId, token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[MigrationV2][P2P][Tx] verdict probe unreachable: ${e.message}" }
            return@withContext Verdict.Unknown(Verdict.Unknown.Reason.UNREACHABLE)
        } ?: return@withContext Verdict.Unknown(Verdict.Unknown.Reason.SESSION_UNKNOWN)
        val terminal = response.terminal
        // Live and still working: the receiver has not decided, so neither can this device.
        if (terminal == null) Verdict.Unknown(Verdict.Unknown.Reason.TIMED_OUT) else VerdictWatch.verdictOf(terminal)
    }
}
