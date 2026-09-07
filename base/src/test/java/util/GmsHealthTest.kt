package util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import com.difft.android.base.utils.GmsHealth
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [GmsHealth]: "broken" means GMS is installed but no package serves the gservices
 * authority. Package state is driven through Robolectric's real PackageManager shadow so the
 * lookups exercise the same `getPackageInfo` / `resolveContentProvider` calls as production.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GmsHealthTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() = GmsHealth.resetForTest()

    @After
    fun tearDown() = GmsHealth.resetForTest()

    @Test
    fun `device without GMS is not broken`() {
        assertFalse(GmsHealth.isGmsBroken(context))
    }

    @Test
    fun `GMS with GSF gservices provider is not broken`() {
        installGms()
        installGsf(withGservicesProvider = true)

        assertFalse(GmsHealth.isGmsBroken(context))
    }

    @Test
    fun `GMS without GSF package is broken`() {
        installGms()

        assertTrue(GmsHealth.isGmsBroken(context))
    }

    @Test
    fun `GMS with GSF package lacking the gservices provider is broken`() {
        installGms()
        installGsf(withGservicesProvider = false)

        assertTrue(GmsHealth.isGmsBroken(context))
    }

    @Test
    fun `verdict is evaluated once per process`() {
        installGms()
        assertTrue(GmsHealth.isGmsBroken(context))

        installGsf(withGservicesProvider = true)

        assertTrue(GmsHealth.isGmsBroken(context))
    }

    private fun installGms() {
        shadowOf(context.packageManager).installPackage(packageInfo(GMS_PACKAGE))
    }

    private fun installGsf(withGservicesProvider: Boolean) {
        val info = packageInfo(GSF_PACKAGE)
        if (withGservicesProvider) {
            info.providers = arrayOf(
                ProviderInfo().apply {
                    packageName = GSF_PACKAGE
                    name = "$GSF_PACKAGE.gservices.GservicesProvider"
                    authority = "$GSF_PACKAGE.gservices"
                    applicationInfo = info.applicationInfo
                },
            )
        }
        shadowOf(context.packageManager).installPackage(info)
    }

    private fun packageInfo(pkg: String) = PackageInfo().apply {
        packageName = pkg
        applicationInfo = ApplicationInfo().apply { packageName = pkg }
    }

    private companion object {
        const val GMS_PACKAGE = "com.google.android.gms"
        const val GSF_PACKAGE = "com.google.android.gsf"
    }
}
