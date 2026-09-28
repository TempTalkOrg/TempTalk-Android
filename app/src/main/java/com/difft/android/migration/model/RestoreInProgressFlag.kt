package com.difft.android.migration.model

/**
 * Restore phase carried by [RestoreInProgressFlag]. It tells `RestoreColdStartRecovery` how far an
 * interrupted restore got, so it can finish or roll back:
 *
 *  - [IMPORTING]  — staging DB is being written, no promote marker yet; the real DB is untouched,
 *    so recovery discards staging and clears the flag.
 *  - [PROMOTING]  — the promote critical section is running; the `PromoteMarker` is authoritative
 *    and drives idempotent finish/rollback.
 *  - [SIGNING_IN] — data is promoted and only sign-in remains; recovery must not re-import.
 *  - [COMPLETED]  — sign-in succeeded; the first cold start shows the one-shot completion page and
 *    consumes the flag. Nothing for recovery to do.
 *
 * Stored as the string [name], so adding phases stays additive — unknown strings map back to
 * [IMPORTING], the most conservative interpretation.
 */
enum class RestorePhase {
    IMPORTING,
    PROMOTING,
    SIGNING_IN,
    COMPLETED;

    companion object {
        /** Parse a persisted phase name; unknown/blank → [IMPORTING] (most conservative). */
        fun fromName(name: String?): RestorePhase =
            entries.firstOrNull { it.name == name } ?: IMPORTING
    }
}

/**
 * Sentinel-flag payload written to `filesDir/migration_restore_in_progress.flag` while a restore is
 * running. `RestoreColdStartRecovery` reads [phase] to decide how to finish or roll back; the login
 * continuation reads [account] and [phase].
 *
 * @param state machine-readable status string (always `"RESTORING"`).
 * @param sourceBackupUri string form of the SAF URI of the backup being restored.
 * @param account the account whose data this restore puts on disk; login compares it with the
 *   account signing in, and any other account is a different-account login that must fully clear
 *   first. Nullable because Gson skips constructors, and such a flag binds no login.
 * @param identityPublishFailed set once publishing the INSTALLED identity failed during restore
 *   sign-in, so the next attempt generates fresh identity material instead of retrying the same.
 *
 * Serialized to JSON via Gson and written atomically (temp file + rename + fsync), so a partial
 * flag is impossible. Being key-based, adding a field stays backward compatible with flags already
 * on disk — a missing key leaves the constructor default.
 */
data class RestoreInProgressFlag(
    val state: String,
    val startedAt: Long,
    val sourceBackupUri: String,
    val phase: String = RestorePhase.IMPORTING.name,
    val account: String? = null,
    val identityPublishFailed: Boolean = false,
)
