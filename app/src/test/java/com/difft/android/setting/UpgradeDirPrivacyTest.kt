package com.difft.android.setting

import android.content.Context
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.FilePathManager
import com.difft.android.base.utils.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Update APKs must live where other apps cannot write (#1433): on Android 8–10 the app-specific
 * external dir is writable by apps holding the storage permission, so a verified APK there could be
 * swapped before the installer reads it.
 */
@RunWith(RobolectricTestRunner::class)
// A separate sandbox keeps the cached application and directory globals from earlier tests
// from pointing at a different Robolectric data directory (#1459).
@Config(
    application = UpgradeDirPrivacyTestApplication::class,
    sdk = [28],
    instrumentedPackages = ["com.difft.android.base.utils"],
)
class UpgradeDirPrivacyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // One test on purpose: FilePathManager and FileProvider cache their dirs per JVM, while
    // Robolectric gives every test a fresh data dir, so the checks must share one environment.
    @Test
    fun `update apks are staged in internal storage the installer can still read`() {
        // Robolectric reports external storage as unmounted by default, which would hide the bug.
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        val upgradeDir = FilePathManager.upgradeDir
        assertEquals(File(context.filesDir, FileUtil.FILE_DIR_UPGRADE).canonicalPath, upgradeDir.canonicalPath)
        context.getExternalFilesDir(null)?.canonicalPath?.let { external ->
            assertFalse(upgradeDir.canonicalPath.startsWith(external + File.separator))
        }
        assertEquals(upgradeDir.absolutePath + File.separator, FileUtil.getFilePath(FileUtil.FILE_DIR_UPGRADE))

        val apk = File(upgradeDir, "a.apk").apply { writeText("x") }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", apk)
        assertEquals("content", uri.scheme)
    }
}

/** [FilePathManager] reads the `application` global, which only a [ScopeApplication] can supply. */
class UpgradeDirPrivacyTestApplication : ScopeApplication() {
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Unconfined

    override fun onCreate() {
        super.onCreate()
        ApplicationHelper.init(this)
    }
}
