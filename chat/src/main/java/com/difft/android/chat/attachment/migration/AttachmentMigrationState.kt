package com.difft.android.chat.attachment.migration

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.storage.AppStateDefaults
import com.difft.android.base.storage.AppStateKeys
import kotlinx.coroutines.flow.first

/**
 * Where the attachment migration has got to, persisted in the plain `app_state` DataStore.
 *
 * Two levels, following the versioned-marker shape used by `LegacyPlaintextAttachmentMigration` and
 * the `syncedContactsVn` contact refresh:
 *  - [completedVersion] — the last migration version whose every stage finished. Durable; the only
 *    thing later builds consult.
 *  - [stage] / [watermark] — how far the version currently being migrated has got. Transient, scoped
 *    to that version, and dropped by [stampVersion] once it completes.
 *
 * Every writer here is monotonic: an interrupted run resumes, and a run that somehow reports less
 * progress than is already recorded cannot walk it back — replaying work is cheap and idempotent,
 * un-migrating is not.
 *
 * Holds no WCDB type so the progress rules — the ones that decide whether an interrupted migration
 * resumes or restarts — are unit-testable without the native library.
 */
internal class AttachmentMigrationState(private val store: DataStore<Preferences>) {

    suspend fun completedVersion(): Int =
        store.data.first()[AppStateKeys.ATTACHMENT_MIGRATION_VERSION]
            ?: AppStateDefaults.ATTACHMENT_MIGRATION_VERSION

    suspend fun stage(): Int =
        store.data.first()[AppStateKeys.ATTACHMENT_MIGRATION_STAGE]
            ?: AppStateDefaults.ATTACHMENT_MIGRATION_STAGE

    suspend fun watermark(): Int =
        store.data.first()[AppStateKeys.ATTACHMENT_MIGRATION_WATERMARK]
            ?: AppStateDefaults.ATTACHMENT_MIGRATION_WATERMARK

    suspend fun fileAttempts(): Int =
        store.data.first()[AppStateKeys.ATTACHMENT_MIGRATION_FILE_ATTEMPTS]
            ?: AppStateDefaults.ATTACHMENT_MIGRATION_FILE_ATTEMPTS

    /** Records one more completed file-migration pass. Returns the count in force afterwards. */
    suspend fun recordFileAttempt(): Int =
        advance(
            AppStateKeys.ATTACHMENT_MIGRATION_FILE_ATTEMPTS,
            AppStateDefaults.ATTACHMENT_MIGRATION_FILE_ATTEMPTS,
            fileAttempts() + 1
        )

    suspend fun sweepAttempts(): Int =
        store.data.first()[AppStateKeys.ATTACHMENT_MIGRATION_SWEEP_ATTEMPTS]
            ?: AppStateDefaults.ATTACHMENT_MIGRATION_SWEEP_ATTEMPTS

    /** Records one more completed sweep pass. Returns the count in force afterwards. */
    suspend fun recordSweepAttempt(): Int =
        advance(
            AppStateKeys.ATTACHMENT_MIGRATION_SWEEP_ATTEMPTS,
            AppStateDefaults.ATTACHMENT_MIGRATION_SWEEP_ATTEMPTS,
            sweepAttempts() + 1
        )

    suspend fun normalAttempts(): Int =
        store.data.first()[AppStateKeys.ATTACHMENT_MIGRATION_NORMAL_ATTEMPTS]
            ?: AppStateDefaults.ATTACHMENT_MIGRATION_NORMAL_ATTEMPTS

    /** Records one more completed normal-attachment migration pass. Returns the count afterwards. */
    suspend fun recordNormalAttempt(): Int =
        advance(
            AppStateKeys.ATTACHMENT_MIGRATION_NORMAL_ATTEMPTS,
            AppStateDefaults.ATTACHMENT_MIGRATION_NORMAL_ATTEMPTS,
            normalAttempts() + 1
        )

    /**
     * Records [next] as the last finished stage, dropping the watermark that stage left behind.
     *
     * The watermark is how far the stage IN FLIGHT got, so it must not survive into the next one: a
     * version with two paged stages would otherwise start the second at the row id the first stopped
     * at and silently skip everything below it. Both writes land in one edit, so a crash between
     * them is not a state a later run can observe.
     *
     * Returns the stage in force afterwards.
     */
    suspend fun advanceStage(next: Int): Int {
        var effective = next
        store.edit {
            val current = it[AppStateKeys.ATTACHMENT_MIGRATION_STAGE]
                ?: AppStateDefaults.ATTACHMENT_MIGRATION_STAGE
            effective = maxOf(current, next)
            if (effective != current) {
                it[AppStateKeys.ATTACHMENT_MIGRATION_STAGE] = effective
                it.remove(AppStateKeys.ATTACHMENT_MIGRATION_WATERMARK)
            }
        }
        return effective
    }

    /** Records [next] as the last processed row. Returns the watermark in force afterwards. */
    suspend fun advanceWatermark(next: Int): Int =
        advance(AppStateKeys.ATTACHMENT_MIGRATION_WATERMARK, AppStateDefaults.ATTACHMENT_MIGRATION_WATERMARK, next)

    /**
     * Marks [version] fully migrated and forgets the in-flight progress that got it there — a later
     * version runs its own stage list from the start.
     */
    suspend fun stampVersion(version: Int) {
        store.edit {
            val current = it[AppStateKeys.ATTACHMENT_MIGRATION_VERSION]
                ?: AppStateDefaults.ATTACHMENT_MIGRATION_VERSION
            if (version > current) it[AppStateKeys.ATTACHMENT_MIGRATION_VERSION] = version
            it.remove(AppStateKeys.ATTACHMENT_MIGRATION_STAGE)
            it.remove(AppStateKeys.ATTACHMENT_MIGRATION_WATERMARK)
            it.remove(AppStateKeys.ATTACHMENT_MIGRATION_FILE_ATTEMPTS)
            it.remove(AppStateKeys.ATTACHMENT_MIGRATION_SWEEP_ATTEMPTS)
            it.remove(AppStateKeys.ATTACHMENT_MIGRATION_NORMAL_ATTEMPTS)
        }
    }

    private suspend fun advance(
        key: Preferences.Key<Int>,
        default: Int,
        next: Int
    ): Int {
        var effective = next
        store.edit {
            val current = it[key] ?: default
            effective = maxOf(current, next)
            if (effective != current) it[key] = effective
        }
        return effective
    }
}
