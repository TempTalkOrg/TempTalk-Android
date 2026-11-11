package com.difft.android.chat.attachment.migration

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.storage.AppStateDefaults
import com.difft.android.base.storage.AppStateKeys
import kotlinx.coroutines.flow.first


















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

    
    suspend fun recordFileAttempt(): Int =
        advance(
            AppStateKeys.ATTACHMENT_MIGRATION_FILE_ATTEMPTS,
            AppStateDefaults.ATTACHMENT_MIGRATION_FILE_ATTEMPTS,
            fileAttempts() + 1
        )

    suspend fun sweepAttempts(): Int =
        store.data.first()[AppStateKeys.ATTACHMENT_MIGRATION_SWEEP_ATTEMPTS]
            ?: AppStateDefaults.ATTACHMENT_MIGRATION_SWEEP_ATTEMPTS

    
    suspend fun recordSweepAttempt(): Int =
        advance(
            AppStateKeys.ATTACHMENT_MIGRATION_SWEEP_ATTEMPTS,
            AppStateDefaults.ATTACHMENT_MIGRATION_SWEEP_ATTEMPTS,
            sweepAttempts() + 1
        )

    suspend fun normalAttempts(): Int =
        store.data.first()[AppStateKeys.ATTACHMENT_MIGRATION_NORMAL_ATTEMPTS]
            ?: AppStateDefaults.ATTACHMENT_MIGRATION_NORMAL_ATTEMPTS

    
    suspend fun recordNormalAttempt(): Int =
        advance(
            AppStateKeys.ATTACHMENT_MIGRATION_NORMAL_ATTEMPTS,
            AppStateDefaults.ATTACHMENT_MIGRATION_NORMAL_ATTEMPTS,
            normalAttempts() + 1
        )

    









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

    
    suspend fun advanceWatermark(next: Int): Int =
        advance(AppStateKeys.ATTACHMENT_MIGRATION_WATERMARK, AppStateDefaults.ATTACHMENT_MIGRATION_WATERMARK, next)

    



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
