package com.difft.android.setting

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.difft.android.setting.di.UpdateDeliveryModule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Google Play build carries none of the Play-restricted pieces of the sideload channels. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GooglePlayChannelPolicyTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun mergedManifest_hasNoSelfUpdateOrExactAlarm() {
        @Suppress("DEPRECATION")
        val requested = app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()

        listOf(
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.USE_EXACT_ALARM",
        ).forEach { assertTrue(it !in requested, "$it must not be declared on google") }

        assertFailsWith<PackageManager.NameNotFoundException> {
            app.packageManager.getServiceInfo(
                ComponentName(app.packageName, "com.difft.android.setting.AppUpgradeService"), 0
            )
        }
    }

    @Test
    fun sideloadClasses_areNotCompiled() {
        listOf("AppUpgradeService", "AppUpgradeNotifier", "ProgressThrottle", "SideloadUpdateDelivery", "ApkUpdateFiles")
            .forEach { name ->
                assertFailsWith<ClassNotFoundException>(name) {
                    Class.forName("com.difft.android.setting.$name")
                }
            }
    }

    @Test
    fun updateDelivery_opensGooglePlay() {
        val delivery = UpdateDeliveryModule.provideUpdateDelivery()

        assertIs<StoreUpdateDelivery>(delivery)
        assertEquals(AppStore.GooglePlay, delivery.store)
    }

    @Test
    fun exactAlarmRow_isHidden() {
        assertEquals(ExactAlarmState.NotApplicable, ExactAlarmAccess.state(app))
    }
}
