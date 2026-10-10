package com.difft.android.setting

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** src/sideload/AndroidManifest.xml is merged into the official and insider channels. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SideloadManifestTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun mergedManifest_declaresSelfUpdateAndExactAlarm() {
        @Suppress("DEPRECATION")
        val requested = app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()

        assertTrue("android.permission.REQUEST_INSTALL_PACKAGES" in requested)
        assertTrue("android.permission.SCHEDULE_EXACT_ALARM" in requested)
        assertTrue("android.permission.USE_EXACT_ALARM" !in requested)
    }

    @Test
    fun mergedManifest_declaresUpgradeService() {
        val info = app.packageManager.getServiceInfo(ComponentName(app, AppUpgradeService::class.java), 0)

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
    }
}
