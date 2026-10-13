package com.difft.android.setting

import android.content.Intent
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.difft.android.R
import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = SideloadTestApplication::class, sdk = [34])
class SideloadUpdateDeliveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: SideloadTestApplication
    private lateinit var upgradeDir: File
    private val delivery = SideloadUpdateDelivery()

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        upgradeDir = tmp.newFolder("upgrade")
        mockkObject(ToastUtil, FileUtil, ComposeDialogManager)
        every { ToastUtil.showLong(any<String>()) } just runs
        every { FileUtil.getFilePath(FileUtil.FILE_DIR_UPGRADE) } returns upgradeDir.absolutePath + File.separator
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun deliver_missingParams_toastsAndStartsNothing() {
        delivery.deliver(app, UpdateOffer(url = "", apkHash = "h"), isForce = false) {}

        verify { ToastUtil.showLong(app.getString(R.string.status_upgrade_param_exception)) }
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun deliver_noCachedApk_startsDownloadService() {
        delivery.deliver(app, UpdateOffer(url = "https://x/a.apk", apkHash = "h"), isForce = true) {}

        val started = shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(AppUpgradeService::class.java.name, started.component?.className)
        assertEquals("https://x/a.apk", started.getStringExtra(AppUpgradeService.EXTRA_URL))
        assertEquals("h", started.getStringExtra(AppUpgradeService.EXTRA_HASH))
        assertTrue(started.getBooleanExtra(AppUpgradeService.EXTRA_FORCE, false))
        assertTrue(started.getStringExtra(AppUpgradeService.EXTRA_PATH)!!.endsWith("/upgrade/a.apk"))
    }

    @Test
    fun deliver_rejectsPathTraversal() {
        delivery.deliver(app, UpdateOffer(url = "https://x/..%2F..%2Fevil.apk", apkHash = "h"), isForce = false) {}

        verify { ToastUtil.showLong(app.getString(R.string.status_upgrade_filename_error)) }
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun attach_failedBroadcast_deletesFile_andReceiverGoesAwayOnDestroy() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        delivery.attach(controller.get()) {}
        val apk = File(upgradeDir, "a.apk").apply { writeText("x") }

        broadcast(AppUpgradeService.STATUS_DOWNLOAD_FAILED, apk, isForce = false)

        assertFalse(apk.exists())
        verify { ToastUtil.showLong(app.getString(R.string.status_upgrade_downolad_failed)) }

        controller.pause().stop().destroy()
        val stillRegistered = shadowOf(app).registeredReceivers.any {
            it.intentFilter.hasAction(AppUpgradeService.ACTION_DOWNLOAD_COMPLETED)
        }
        assertFalse(stillRegistered, "the receiver must be unregistered when the host is destroyed")
    }

    @Test
    fun attach_forceSuccess_handsOffBeforeInstallPrompt() {
        val events = mutableListOf<String>()
        every {
            ComposeDialogManager.showMessageDialog(
                context = any(), title = any(), message = any(), confirmText = any(), cancelText = any(),
                showCancel = false, cancelable = false, autoDismiss = any(),
                confirmButtonColor = any(), cancelButtonColor = any(), onConfirm = any(),
                onCancel = any(), onDismiss = any(), content = any()
            )
        } answers {
            events += "install-dialog"
            mockk<ComposeDialog>(relaxed = true)
        }
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        delivery.attach(activity) { events += "hand-off" }
        val apk = File(upgradeDir, "a.apk").apply { writeText("x") }

        broadcast(AppUpgradeService.STATUS_DOWNLOAD_SUCCESS, apk, isForce = true)

        assertEquals(listOf("hand-off", "install-dialog"), events)
    }

    private fun broadcast(status: Int, apk: File, isForce: Boolean) {
        val intent = Intent(AppUpgradeService.ACTION_DOWNLOAD_COMPLETED)
            .setPackage(app.packageName)
            .putExtra(AppUpgradeService.EXTRA_STATUS, status)
            .putExtra(AppUpgradeService.EXTRA_PATH, apk.absolutePath)
            .putExtra(AppUpgradeService.EXTRA_FORCE, isForce)
        app.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }
}

/** The sideload code reads the `application` global, which only a [ScopeApplication] supplies. */
class SideloadTestApplication : ScopeApplication() {
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Unconfined

    override fun onCreate() {
        super.onCreate()
        ApplicationHelper.init(this)
    }
}
