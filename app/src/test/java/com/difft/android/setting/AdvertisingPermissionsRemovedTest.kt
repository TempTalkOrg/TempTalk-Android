package com.difft.android.setting

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * The main manifest strips every advertising permission that GMS/Firebase declare transitively.
 * Robolectric's PackageManager is built from the variant's merged manifest, so this runs on
 * every channel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AdvertisingPermissionsRemovedTest {

    @Test
    fun mergedManifest_requestsNoAdvertisingPermission() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        @Suppress("DEPRECATION")
        val requested = app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()

        val advertising = listOf(
            "com.google.android.gms.permission.AD_ID",
            "android.permission.ACCESS_ADSERVICES_AD_ID",
            "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
            "android.permission.ACCESS_ADSERVICES_CUSTOM_AUDIENCE",
            "android.permission.ACCESS_ADSERVICES_TOPICS",
        )
        advertising.forEach { permission ->
            assertTrue(permission !in requested, "$permission must be removed from the merged manifest")
        }
    }
}
