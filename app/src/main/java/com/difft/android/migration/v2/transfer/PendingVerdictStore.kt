package com.difft.android.migration.v2.transfer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.PendingVerdictEdit
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
    /** Epoch millis the last body byte left (or the commit marker was written); the age is measured from here. */
    val bodySentAt: Long,
)

/**
 * The one send awaiting a verdict, kept on disk so the question "did that migration go through?"
 * survives this process. Deliberately kept while the outcome is not known for certain (a wait that
 * ended as unknown, or a process that died during it) and deleted once it is.
 *
 * For a device-replacement export the record is written in the same app_state edit as the pause's
 * commit marker ([saveEdit]); a settlement deletes it only for that same session
 * ([deleteIfSession]), inside the pause's own edit or through
 * `MigrationMessagePause.editPendingUnlessHeld`, so a late cleanup of one export never deletes
 * another's (#1382 A01/A11). A Desktop link has no pause and saves it once the body is fully sent
 * ([save]). No record is written for a receiver that does not answer `/status`, and a paused
 * credential handoff writes none with its commit marker: the handoff reconciler decides its
 * outcome. [loadOrThrow] also deletes a record that is too old or does not decode.
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
        runCatching { store.edit { saveEdit(pending).apply(it) } }
            .onFailure { L.w { "[MigrationV2][P2P][Tx] pending verdict not saved: ${it.message}" } }
    }

    /** Writes [pending] inside another app_state edit. */
    fun saveEdit(pending: PendingVerdict): PendingVerdictEdit = PendingVerdictEdit { prefs ->
        prefs[AppStateKeys.MIGRATION_PENDING_VERDICT] = gson.toJson(pending)
    }

    /** Deletes the stored record inside another app_state edit, only when it belongs to [sessionId]. */
    fun deleteIfSession(sessionId: String): PendingVerdictEdit = PendingVerdictEdit { prefs ->
        val raw = prefs[AppStateKeys.MIGRATION_PENDING_VERDICT] ?: return@PendingVerdictEdit
        if (decode(raw)?.session?.sessionId == sessionId) {
            prefs.remove(AppStateKeys.MIGRATION_PENDING_VERDICT)
        } else {
            L.i { "[MigrationPause] event=pending_verdict_kept reason=other_session" }
        }
    }

    /** The pending send, or null when there is none or it is too old to still be answerable. */
    suspend fun load(now: Long = System.currentTimeMillis()): PendingVerdict? = try {
        loadOrThrow(now)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        L.w { "[MigrationV2][P2P][Tx] event=pending_verdict_read_failed error=${e.javaClass.simpleName}" }
        null
    }

    /**
     * As [load], but a failed read of the store throws instead of reading as "no receipt", for a
     * caller that must not give up on a record it merely could not read this time.
     */
    suspend fun loadOrThrow(now: Long = System.currentTimeMillis()): PendingVerdict? {
        val raw = store.data.first()[AppStateKeys.MIGRATION_PENDING_VERDICT] ?: return null
        val pending = decode(raw)
        if (pending == null || now - pending.bodySentAt > MAX_AGE_MS) {
            // Compare-and-delete: a record written since this read belongs to a newer send and stays.
            runCatching {
                store.edit { prefs ->
                    if (prefs[AppStateKeys.MIGRATION_PENDING_VERDICT] == raw) prefs.remove(AppStateKeys.MIGRATION_PENDING_VERDICT)
                }
            }.onFailure { L.w { "[MigrationV2][P2P][Tx] event=pending_verdict_clear_failed error=${it.javaClass.simpleName}" } }
            return null
        }
        return pending
    }

    // Gson leaves a field missing from the JSON null despite its Kotlin type.
    @Suppress("SENSELESS_COMPARISON")
    private fun decode(raw: String): PendingVerdict? =
        runCatching { gson.fromJson(raw, PendingVerdict::class.java) }.getOrNull()
            ?.takeIf { it.session != null && it.session.sessionId != null }

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
        // Live and still working: the receiver has not decided, so neither can this device. Any
        // terminal outside the agreed vocabulary is unknown too (see VerdictWatch.verdictOf).
        if (terminal == null) Verdict.Unknown(Verdict.Unknown.Reason.TIMED_OUT) else VerdictWatch.verdictOf(terminal)
    }
}
