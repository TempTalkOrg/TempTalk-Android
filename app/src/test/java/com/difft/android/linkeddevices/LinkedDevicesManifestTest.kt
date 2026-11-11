package com.difft.android.linkeddevices

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNotNull








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
