package com.difft.android.app.startup

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.dependencies.ApplicationDependencyProvider
import dagger.hilt.android.EntryPointAccessors

/**
 * Pure helpers for the legacy SQLCipher cleanup + stale-Sending sweep that run
 * during `TempTalkApplication.onCreate` via `AppStartup.addNonBlocking` (each
 * task is dispatched on `Dispatchers.IO` — see
 * `AppStartup.executeNonBlockingTasks`).
 *
 * Two operations are performed here, both part of the SQLCipher-removal effort:
 * - [cleanupLegacySqlCipherArtifacts] — deletes the two legacy DB files
 *   (`signal-key-value.db` from PR 1, `signal-jobmanager.db` from PR 2) and
 *   the SharedPreferences SQLCipher secret keys.
 * - [sweepStaleSendingMessages] — flips orphaned `message.sendType == Sending`
 *   rows to `SentFailed` so the UI surfaces the retry button.
 *
 * Top-level `fun`s (not an object/class) so unit tests can exercise them
 * without Robolectric.
 */

/**
 * Deletes the two legacy SQLCipher database files and removes the
 * SharedPreferences SQLCipher secret keys that are no longer referenced after
 * PR 1 + PR 2 land.
 *
 * No idempotency flag — `Context.deleteDatabase()` returns `false` on a
 * non-existent file with no I/O cost beyond an inode stat (sub-millisecond),
 * and `SharedPreferences.edit { remove(absentKey) }` is a no-op against
 * already-empty state. Running on every cold start is cheaper than maintaining
 * a one-shot flag in `UserData`.
 *
 * Logging:
 * - `L.i` only when at least one file was actually present and deleted (signals
 *   that a real legacy cleanup happened; silent otherwise to avoid per-boot noise).
 * - `L.w` on any exception (non-fatal: leaving a file on disk costs ~tens of
 *   KB and does not affect correctness).
 */
fun cleanupLegacySqlCipherArtifacts(ctx: Context) {
    try {
        val keyValueDbDeleted = ctx.deleteDatabase("signal-key-value.db")
        val jobDbDeleted = ctx.deleteDatabase("signal-jobmanager.db")
        if (keyValueDbDeleted || jobDbDeleted) {
            L.i {
                "[LegacyCleanup] deleted: signal-key-value.db=$keyValueDbDeleted, " +
                    "signal-jobmanager.db=$jobDbDeleted"
            }
        }
        // Remove SQLCipher secret keys (set by the deleted DatabaseSecretProvider).
        // Default-SP path matches `TextSecurePreferences.java:97`
        // (`PreferenceManager.getDefaultSharedPreferences`); inlined here so `:app`
        // does not need the `androidx.preference` dependency.
        ctx.getSharedPreferences("${ctx.packageName}_preferences", Context.MODE_PRIVATE)
            .edit {
                remove("pref_database_encrypted_secret")
                remove("pref_database_unencrypted_secret")
            }
    } catch (e: Exception) {
        // Non-fatal: worst case the old DB stays on disk until user reinstalls.
        // Don't crash startup over cleanup.
        L.w { "[LegacyCleanup] cleanupLegacySqlCipherArtifacts failed: ${e.stackTraceToString()}" }
    }
}

/**
 * Flips any `message.sendType == Sending` rows to `SentFailed` via
 * [WcdbJobStorage.sweepStaleSendingMessages][com.difft.android.chat.jobs.WcdbJobStorage.sweepStaleSendingMessages].
 *
 * Called from `AppStartup.addNonBlocking`, which already launches each task on
 * `Dispatchers.IO` (see `AppStartup.executeNonBlockingTasks`). No extra
 * `runBlocking` / dispatcher switch needed.
 *
 * Resolves `WcdbJobStorage` via `EntryPointAccessors.fromApplication` +
 * `ApplicationDependencyProvider.DepsEntryPoint` — the Hilt bridge added by
 * Task 2. Must be called AFTER `ApplicationDependencies.init(...)` so the
 * Hilt graph is initialized.
 *
 * Concurrency note: the sweep races loosely against fresh user-initiated
 * Sending writes (the narrow cold-start window before the sweep completes).
 * A misflagged fresh row is recoverable — the UI surfaces a retry button and
 * a user resend goes through the normal `PushTextSendJob` path.
 *
 * Logging is handled inside
 * [WcdbJobStorage.sweepStaleSendingMessages][com.difft.android.chat.jobs.WcdbJobStorage.sweepStaleSendingMessages]
 * (`[JobStorage] sweepStaleSendingMessages ...`).
 */
fun sweepStaleSendingMessages(app: Application) {
    val wcdbJobStorage = EntryPointAccessors
        .fromApplication(app, ApplicationDependencyProvider.DepsEntryPoint::class.java)
        .wcdbJobStorage()
    wcdbJobStorage.sweepStaleSendingMessages()
}
