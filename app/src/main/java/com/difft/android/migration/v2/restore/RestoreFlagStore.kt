package com.difft.android.migration.v2.restore

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.model.RestoreInProgressFlag
import com.difft.android.migration.model.RestorePhase
import com.google.gson.Gson
import java.io.File







class RestoreFlagStore(private val context: Context) {

    private val gson = Gson()

    private val flagFile: File get() = File(context.filesDir, FLAG_FILE_NAME)
    private val tmpFile: File get() = File(context.filesDir, TMP_FILE_NAME)

    
    fun exists(): Boolean = flagFile.exists()

    




    fun begin(sourceBackupUri: String, account: String) {
        writeAtomic(
            RestoreInProgressFlag(
                state = FLAG_STATE_RESTORING,
                startedAt = System.currentTimeMillis(),
                sourceBackupUri = sourceBackupUri,
                phase = RestorePhase.IMPORTING.name,
                account = account,
            )
        )
        
        L.i { "[MigrationV2][RestoreFlag] begin phase=IMPORTING accountKind=${if ('@' in account) "email" else "phone"}" }
    }

    



    fun advance(phase: RestorePhase) {
        val existing = read()
        writeAtomic(
            RestoreInProgressFlag(
                state = FLAG_STATE_RESTORING,
                startedAt = existing?.startedAt ?: System.currentTimeMillis(),
                sourceBackupUri = existing?.sourceBackupUri.orEmpty(),
                phase = phase.name,
                account = existing?.account,
                identityPublishFailed = existing?.identityPublishFailed ?: false,
            )
        )
        L.i { "[MigrationV2][RestoreFlag] advance phase=${phase.name}" }
    }

    




    fun markIdentityPublishFailed() {
        val existing = read() ?: run {
            L.w { "[MigrationV2][RestoreFlag] identity publish failed but no flag on disk — nothing to mark" }
            return
        }
        writeAtomic(existing.copy(identityPublishFailed = true))
        L.w { "[MigrationV2][RestoreFlag] installed identity marked unusable — next restore sign-in generates a fresh one" }
    }

    
    fun currentPhase(): RestorePhase? = read()?.let { RestorePhase.fromName(it.phase) }

    




    fun consumeCompleted(): Boolean {
        if (currentPhase() != RestorePhase.COMPLETED) return false
        clear()
        return true
    }

    












    fun consumeSigningInFor(account: String): Boolean {
        if (account.isBlank()) return false
        val flag = read() ?: return false
        if (RestorePhase.fromName(flag.phase) != RestorePhase.SIGNING_IN) return false
        if (flag.account?.trim() != account.trim()) return false
        clear()
        L.i { "[MigrationV2][RestoreFlag] SIGNING_IN flag for an already-logged-in account consumed as COMPLETED" }
        return true
    }

    
    fun read(): RestoreInProgressFlag? {
        if (!flagFile.exists()) return null
        return try {
            gson.fromJson(flagFile.readText(Charsets.UTF_8), RestoreInProgressFlag::class.java)
        } catch (e: Throwable) {
            L.e { "[MigrationV2][RestoreFlag] flag unreadable: ${e.stackTraceToString()}" }
            null
        }
    }

    
    fun clear() {
        runCatching { flagFile.delete() }
        runCatching { tmpFile.delete() }
        L.i { "[MigrationV2][RestoreFlag] cleared" }
    }

    private fun writeAtomic(flag: RestoreInProgressFlag) {
        val json = gson.toJson(flag)
        try {
            tmpFile.outputStream().use { os ->
                os.write(json.toByteArray(Charsets.UTF_8))
                os.flush()
                os.fd.sync()
            }
            if (!tmpFile.renameTo(flagFile)) {
                flagFile.delete()
                if (!tmpFile.renameTo(flagFile)) {
                    error("rename $TMP_FILE_NAME → $FLAG_FILE_NAME failed")
                }
            }
        } catch (e: Throwable) {
            tmpFile.delete()
            throw e
        }
    }

    companion object {
        const val FLAG_FILE_NAME = "migration_restore_in_progress.flag"
        const val TMP_FILE_NAME = "migration_restore_in_progress.flag.tmp"

        
        const val FLAG_STATE_RESTORING = "RESTORING"
    }
}
