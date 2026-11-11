package com.difft.android.migration.v2.restore

import android.content.Context
import com.difft.android.base.utils.FilePathManager
import org.difft.app.database.WCDB
import java.io.File













data class RestorePaths(
    val realDbPath: String,
    val realAttachmentDir: String,
    val stagingDbPath: String,
    val attachmentsStagingDir: String,
    val containerWorkFile: String,
    val spoolDir: String,
) {
    companion object {
        
        const val WORK_DIR_NAME = "migration_restore_work"

        
        const val ATTACHMENTS_DIR_NAME = "attachments"

        
        const val SPOOL_DIR_NAME = "spool"

        




        fun forContext(context: Context): RestorePaths {
            val work = File(context.filesDir, WORK_DIR_NAME)
            val attachmentWork = File(FilePathManager.getBaseDirFile(), WORK_DIR_NAME)
            return RestorePaths(
                realDbPath = context.getDatabasePath(WCDB.DATABASE_NAME).absolutePath,
                realAttachmentDir = FilePathManager.attachmentDir.absolutePath,
                stagingDbPath = File(work, "staging_$STAGING_DB_FILE").absolutePath,
                attachmentsStagingDir = File(attachmentWork, ATTACHMENTS_DIR_NAME).absolutePath,
                containerWorkFile = File(work, "container.ttmig").absolutePath,
                spoolDir = File(attachmentWork, SPOOL_DIR_NAME).absolutePath,
            )
        }

        private const val STAGING_DB_FILE = "tt_wcdb_database.db"
    }
}
