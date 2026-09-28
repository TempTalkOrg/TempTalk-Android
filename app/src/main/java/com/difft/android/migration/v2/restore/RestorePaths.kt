package com.difft.android.migration.v2.restore

import android.content.Context
import com.difft.android.base.utils.FilePathManager
import org.difft.app.database.WCDB
import java.io.File

/**
 * The on-disk destinations a [RestoreOrchestrator] promote targets, plus the working area it imports
 * into. Bundled so the orchestrator takes one injectable seam; resolving these is pure path arithmetic,
 * with no DB open and no IO beyond `File` construction.
 *
 * @param containerWorkFile a private temp file the SAF source is materialized into, because the
 *   verify-before-trust two-pass read needs random access and a SAF input stream is forward-only.
 * @param attachmentsStagingDir sits beside the real attachment dir, under the same
 *   [FilePathManager.getBaseDirFile] root, so promote's P3 is a rename and not a byte copy of the
 *   whole attachment set. `renameTo` fails across mounts, and the real attachment dir lives on the
 *   external files mount while `filesDir` does not.
 */
data class RestorePaths(
    val realDbPath: String,
    val realAttachmentDir: String,
    val stagingDbPath: String,
    val attachmentsStagingDir: String,
    val containerWorkFile: String,
) {
    companion object {
        /** Sub-directory name that holds all transient restore working files. */
        const val WORK_DIR_NAME = "migration_restore_work"

        /** Attachment staging sub-directory inside [WORK_DIR_NAME]. */
        const val ATTACHMENTS_DIR_NAME = "attachments"

        /**
         * Production paths, with the staging products under a dedicated working directory the
         * orchestrator clears on success and on failure alike. The container copy and the staging DB
         * stay private under `filesDir`; only the attachments stage on the attachment mount.
         */
        fun forContext(context: Context): RestorePaths {
            val work = File(context.filesDir, WORK_DIR_NAME)
            val attachmentWork = File(FilePathManager.getBaseDirFile(), WORK_DIR_NAME)
            return RestorePaths(
                realDbPath = context.getDatabasePath(WCDB.DATABASE_NAME).absolutePath,
                realAttachmentDir = FilePathManager.attachmentDir.absolutePath,
                stagingDbPath = File(work, "staging_$STAGING_DB_FILE").absolutePath,
                attachmentsStagingDir = File(attachmentWork, ATTACHMENTS_DIR_NAME).absolutePath,
                containerWorkFile = File(work, "container.ttmig").absolutePath,
            )
        }

        private const val STAGING_DB_FILE = "tt_wcdb_database.db"
    }
}
