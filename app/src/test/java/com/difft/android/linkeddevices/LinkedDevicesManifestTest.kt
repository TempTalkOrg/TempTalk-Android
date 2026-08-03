package com.difft.android.linkeddevices

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNotNull

/**
 * Guards the AndroidManifest registration of every Activity this feature can launch.
 *
 * Robolectric resolves against the real merged manifest, so a missing <activity> entry —
 * which unit tests with host doubles cannot see and which crashes on device with
 * ActivityNotFoundException — fails here at build time.
 */
@RunWith(RobolectricTestRunner::class)
class LinkedDevicesManifestTest {

    @Test
    fun `LinkedDevicesActivity is declared in the merged manifest`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, LinkedDevicesActivity::class.java)
        assertNotNull(
            context.packageManager.resolveActivity(intent, 0),
            "LinkedDevicesActivity is not declared in AndroidManifest.xml"
        )
    }
}
