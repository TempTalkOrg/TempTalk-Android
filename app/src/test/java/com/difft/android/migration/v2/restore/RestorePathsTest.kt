package com.difft.android.migration.v2.restore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.FilePathManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals







@RunWith(RobolectricTestRunner::class)
@Config(application = RestorePathsTestApplication::class, sdk = [33])
class RestorePathsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `attachment staging shares the attachment dir's root so promote renames`() {
        val paths = RestorePaths.forContext(context)

        val base = FilePathManager.getBaseDirFile()
        assertEquals(base, File(paths.realAttachmentDir).parentFile)
        assertEquals(
            File(base, RestorePaths.WORK_DIR_NAME),
            File(paths.attachmentsStagingDir).parentFile,
        )
    }

    @Test
    fun `the receive spool sits on the attachment mount, away from the staging db`() {
        val paths = RestorePaths.forContext(context)

        
        
        
        assertEquals(
            File(FilePathManager.getBaseDirFile(), RestorePaths.WORK_DIR_NAME),
            File(paths.spoolDir).parentFile,
        )
        assertNotEquals(File(paths.stagingDbPath).parentFile, File(paths.spoolDir))
        assertNotEquals(File(paths.containerWorkFile).parentFile, File(paths.spoolDir))
    }

    @Test
    fun `the staging db and the container copy stay private under filesDir`() {
        val paths = RestorePaths.forContext(context)

        val work = File(context.filesDir, RestorePaths.WORK_DIR_NAME)
        assertEquals(work, File(paths.stagingDbPath).parentFile)
        assertEquals(work, File(paths.containerWorkFile).parentFile)
    }
}


class RestorePathsTestApplication : ScopeApplication() {
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Unconfined

    override fun onCreate() {
        super.onCreate()
        ApplicationHelper.init(this)
    }
}
