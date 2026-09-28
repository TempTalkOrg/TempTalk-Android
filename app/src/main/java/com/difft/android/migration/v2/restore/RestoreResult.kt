package com.difft.android.migration.v2.restore

import com.difft.android.migration.model.MigrationError

/**
 * Typed outcome of [RestoreOrchestrator.restore]. The orchestrator returns this and never signs in or
 * restarts; the caller drives the next step from the variant.
 */
sealed interface RestoreResult {

    /**
     * The restored data is on disk — DB promoted, attachments in place — and the flag is at
     * [com.difft.android.migration.model.RestorePhase.SIGNING_IN]. The caller must run its sign-in
     * continuation and then restart the process, so the lazy WCDB singleton cold-opens the promoted DB.
     */
    data object ReadyForSignIn : RestoreResult

    /**
     * Validation rejected the archive. No staging, promote or DB write happened and the target is
     * untouched, so the caller shows the mapped message and stays on the picker. [error] is one of
     * [MigrationError.PassphraseInvalid] (which a wrong passphrase and a tampered file are
     * indistinguishable as), [MigrationError.FileCorrupted], [MigrationError.UnsupportedVersion],
     * [MigrationError.AccountMismatch], or [MigrationError.TargetNotEmpty]. Both the file and P2P paths
     * return the same code for the same condition, so a caller can branch on one result per seam.
     */
    data class Rejected(val error: MigrationError) : RestoreResult

    /**
     * Import or promote failed after validation passed. The staging products were discarded, rolling
     * back to the empty state; a promote interrupted mid-critical-section leaves the [PromoteStep]
     * marker for cold-start recovery. The caller surfaces [error] and lets the user retry.
     */
    data class Failed(val error: MigrationError) : RestoreResult
}

/**
 * Restore progress signal for the caller's blocking progress UI.
 *
 * @param stage opaque stage id (`"validating"` / `"importing"` / `"promoting"` / `"ready"`), resolved
 *   to a localized label at the UI boundary.
 */
data class RestoreProgress(
    val stage: String,
    val percent: Int,
)
