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


data class PendingVerdict(
    val session: SenderSessionRef,
    
    val bodySentAt: Long,
)



















@Singleton
class PendingVerdictStore @Inject constructor(
    @AppStateDataStore private val store: DataStore<Preferences>,
) {
    private val gson = Gson()

    suspend fun save(pending: PendingVerdict) {
        runCatching { store.edit { saveEdit(pending).apply(it) } }
            .onFailure { L.w { "[MigrationV2][P2P][Tx] pending verdict not saved: ${it.message}" } }
    }

    
    fun saveEdit(pending: PendingVerdict): PendingVerdictEdit = PendingVerdictEdit { prefs ->
        prefs[AppStateKeys.MIGRATION_PENDING_VERDICT] = gson.toJson(pending)
    }

    
    fun deleteIfSession(sessionId: String): PendingVerdictEdit = PendingVerdictEdit { prefs ->
        val raw = prefs[AppStateKeys.MIGRATION_PENDING_VERDICT] ?: return@PendingVerdictEdit
        if (decode(raw)?.session?.sessionId == sessionId) {
            prefs.remove(AppStateKeys.MIGRATION_PENDING_VERDICT)
        } else {
            L.i { "[MigrationPause] event=pending_verdict_kept reason=other_session" }
        }
    }

    
    suspend fun load(now: Long = System.currentTimeMillis()): PendingVerdict? = try {
        loadOrThrow(now)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        L.w { "[MigrationV2][P2P][Tx] event=pending_verdict_read_failed error=${e.javaClass.simpleName}" }
        null
    }

    



    suspend fun loadOrThrow(now: Long = System.currentTimeMillis()): PendingVerdict? {
        val raw = store.data.first()[AppStateKeys.MIGRATION_PENDING_VERDICT] ?: return null
        val pending = decode(raw)
        if (pending == null || now - pending.bodySentAt > MAX_AGE_MS) {
            
            runCatching {
                store.edit { prefs ->
                    if (prefs[AppStateKeys.MIGRATION_PENDING_VERDICT] == raw) prefs.remove(AppStateKeys.MIGRATION_PENDING_VERDICT)
                }
            }.onFailure { L.w { "[MigrationV2][P2P][Tx] event=pending_verdict_clear_failed error=${it.javaClass.simpleName}" } }
            return null
        }
        return pending
    }

    
    @Suppress("SENSELESS_COMPARISON")
    private fun decode(raw: String): PendingVerdict? =
        runCatching { gson.fromJson(raw, PendingVerdict::class.java) }.getOrNull()
            ?.takeIf { it.session != null && it.session.sessionId != null }

    companion object {
        
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}


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
        
        
        if (terminal == null) Verdict.Unknown(Verdict.Unknown.Reason.TIMED_OUT) else VerdictWatch.verdictOf(terminal)
    }
}
