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






@RunWith(RobolectricTestRunner::class)


@Config(
    application = UpgradeDirPrivacyTestApplication::class,
    sdk = [28],
    instrumentedPackages = ["com.difft.android.base.utils"],
)
class UpgradeDirPrivacyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    
    
    @Test
    fun `update apks are staged in internal storage the installer can still read`() {
        
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


class UpgradeDirPrivacyTestApplication : ScopeApplication() {
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Unconfined

    override fun onCreate() {
        super.onCreate()
        ApplicationHelper.init(this)
    }
}
