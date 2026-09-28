package com.difft.android.app

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CrashFilter.isBaseOnlyInstall] reads two PackageManager facts: the bundletool-injected
 * `com.android.vending.splits.required` meta-data and the installed split APK list. Only the
 * combination "splits required, none installed" may gate crash suppression.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CrashFilterBaseOnlyInstallTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun configureInstall(splitsRequired: Boolean?, splitSourceDirs: Array<String>?) {
        val appInfo = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).applicationInfo!!
        appInfo.metaData = splitsRequired?.let { Bundle().apply { putBoolean("com.android.vending.splits.required", it) } }
        appInfo.splitSourceDirs = splitSourceDirs
    }

    @Test
    fun `splits required and none installed is a base-only install`() {
        configureInstall(splitsRequired = true, splitSourceDirs = null)

        assertTrue(CrashFilter.isBaseOnlyInstall(context))
    }

    // Both PackageManager overloads: the deprecated int-flags path below API 33 and the
    // ApplicationInfoFlags path from API 33 on (the module default SDK covers the latter too).
    @Test
    @Config(sdk = [31])
    fun `legacy int-flags overload detects a base-only install on API 31`() {
        configureInstall(splitsRequired = true, splitSourceDirs = null)

        assertTrue(CrashFilter.isBaseOnlyInstall(context))
    }

    @Test
    @Config(sdk = [34])
    fun `ApplicationInfoFlags overload detects a base-only install on API 34`() {
        configureInstall(splitsRequired = true, splitSourceDirs = null)

        assertTrue(CrashFilter.isBaseOnlyInstall(context))
    }

    @Test
    fun `splits required and installed is a complete install`() {
        configureInstall(splitsRequired = true, splitSourceDirs = arrayOf("/data/app/base/split_config.arm64_v8a.apk"))

        assertFalse(CrashFilter.isBaseOnlyInstall(context))
    }

    @Test
    fun `universal APK without the meta-data is never a base-only install`() {
        configureInstall(splitsRequired = null, splitSourceDirs = null)

        assertFalse(CrashFilter.isBaseOnlyInstall(context))
    }

    @Test
    fun `meta-data explicitly false is never a base-only install`() {
        configureInstall(splitsRequired = false, splitSourceDirs = null)

        assertFalse(CrashFilter.isBaseOnlyInstall(context))
    }
}
