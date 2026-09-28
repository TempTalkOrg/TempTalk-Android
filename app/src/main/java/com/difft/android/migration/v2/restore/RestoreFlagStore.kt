package com.difft.android.migration.v2.restore

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.model.RestoreInProgressFlag
import com.difft.android.migration.model.RestorePhase
import com.google.gson.Gson
import java.io.File

/**
 * Atomic, phase-aware on-disk store for the restore sentinel flag. Writes go to a `.tmp` and are then
 * renamed, so a crash mid-write can never leave a half-flag that cold-start recovery would misread.
 * Owns the phase lifecycle the [RestoreOrchestrator] and the login continuation share
 * (`IMPORTING → PROMOTING → SIGNING_IN → COMPLETED`).
 */
class RestoreFlagStore(private val context: Context) {

    private val gson = Gson()

    private val flagFile: File get() = File(context.filesDir, FLAG_FILE_NAME)
    private val tmpFile: File get() = File(context.filesDir, TMP_FILE_NAME)

    /** True if a restore is in progress (flag present). A single `File.exists()` — zero side effects. */
    fun exists(): Boolean = flagFile.exists()

    /**
     * Write the initial flag at [RestorePhase.IMPORTING] for [sourceBackupUri], bound to [account] —
     * the account whose data the restore is about to put on disk (already matched against the
     * container manifest by the caller).
     */
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
        // Log the account's kind only: L masks phone numbers, not e-mails.
        L.i { "[MigrationV2][RestoreFlag] begin phase=IMPORTING accountKind=${if ('@' in account) "email" else "phone"}" }
    }

    /**
     * Advance the flag to [phase], preserving everything else. With no flag on disk a fresh one is
     * created, so the phase is never lost.
     */
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

    /**
     * Record that publishing the installed identity failed during the restore sign-in. The flag stays
     * at its phase (the data is still on disk and still belongs to the same account); only the next
     * sign-in's identity choice changes — it generates a fresh one. No flag → nothing to mark.
     */
    fun markIdentityPublishFailed() {
        val existing = read() ?: run {
            L.w { "[MigrationV2][RestoreFlag] identity publish failed but no flag on disk — nothing to mark" }
            return
        }
        writeAtomic(existing.copy(identityPublishFailed = true))
        L.w { "[MigrationV2][RestoreFlag] installed identity marked unusable — next restore sign-in generates a fresh one" }
    }

    /** Read the current phase, or null if no flag is on disk / it is unparseable. */
    fun currentPhase(): RestorePhase? = read()?.let { RestorePhase.fromName(it.phase) }

    /**
     * True exactly once after a restore-path sign-in succeeded: the flag reads [RestorePhase.COMPLETED]
     * and is cleared here, so the success page it gates shows on one cold start only — a second launch,
     * or a later re-login on this install, never sees it again.
     */
    fun consumeCompleted(): Boolean {
        if (currentPhase() != RestorePhase.COMPLETED) return false
        clear()
        return true
    }

    /**
     * True exactly once when the flag is stuck at [RestorePhase.SIGNING_IN] for [account] — and the
     * flag is cleared here.
     *
     * A credential-handoff restore writes the session directly instead of going through sign-in, so
     * nothing advances the flag to COMPLETED if the process dies between the credential write and
     * that advance. A device that is ALREADY logged in as [account] with the flag still at
     * SIGNING_IN for the SAME account has, by definition, completed its restore — so the caller can
     * treat this as COMPLETED, show the success page once, and clear the orphaned flag file.
     *
     * Cannot fire on the normal login path: there the account only becomes established after
     * `markRestoreSignInComplete()` has already advanced the phase to COMPLETED.
     */
    fun consumeSigningInFor(account: String): Boolean {
        if (account.isBlank()) return false
        val flag = read() ?: return false
        if (RestorePhase.fromName(flag.phase) != RestorePhase.SIGNING_IN) return false
        if (flag.account?.trim() != account.trim()) return false
        clear()
        L.i { "[MigrationV2][RestoreFlag] SIGNING_IN flag for an already-logged-in account consumed as COMPLETED" }
        return true
    }

    /** Read the flag, or null when none / unparseable. */
    fun read(): RestoreInProgressFlag? {
        if (!flagFile.exists()) return null
        return try {
            gson.fromJson(flagFile.readText(Charsets.UTF_8), RestoreInProgressFlag::class.java)
        } catch (e: Throwable) {
            L.e { "[MigrationV2][RestoreFlag] flag unreadable: ${e.stackTraceToString()}" }
            null
        }
    }

    /** Delete the flag. Idempotent. Called when restore completes, fails, or is cleaned up. */
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

        /** Machine-readable status string, always the same; kept for the on-disk shape. */
        const val FLAG_STATE_RESTORING = "RESTORING"
    }
}
