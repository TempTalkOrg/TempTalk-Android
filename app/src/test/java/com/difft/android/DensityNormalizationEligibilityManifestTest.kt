package com.difft.android

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.BaseActivity
import com.difft.android.base.utils.density.DisplayDensityNormalizer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue







@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DensityNormalizationEligibilityManifestTest {

    @Test
    fun mergedManifest_excludesOnlyTheInPlaceCallScreens() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        @Suppress("DEPRECATION")
        val activities = app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES)
            .activities.orEmpty()
            .filter { info ->
                val cls = runCatching { Class.forName(info.name) }.getOrNull()
                cls != null && BaseActivity::class.java.isAssignableFrom(cls)
            }
        val excluded = activities
            .filterNot { DisplayDensityNormalizer.isEligibleConfigChanges(it.configChanges) }
            .map { it.name.substringAfterLast('.') }
            .toSet()

        assertEquals(setOf("LIncomingCallActivity", "LCallServerNodeActivity"), excluded)
        val eligible = activities.map { it.name.substringAfterLast('.') }.toSet() - excluded
        assertTrue("IndexActivity" in eligible && "ChatActivity" in eligible, "home and chat must be normalized: $eligible")
        assertTrue("PictureSelectorSupporterActivity" in eligible, "the media picker is a BaseActivity screen: $eligible")
    }
}
