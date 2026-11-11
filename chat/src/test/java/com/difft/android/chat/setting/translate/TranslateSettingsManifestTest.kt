package com.difft.android.chat.setting.translate

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull








@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslateSettingsManifestTest {

    @Test
    fun `TranslateSettingsActivity is declared in the merged manifest`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, TranslateSettingsActivity::class.java)
        assertNotNull(
            context.packageManager.resolveActivity(intent, 0),
            "TranslateSettingsActivity is not declared in AndroidManifest.xml"
        )
    }
}
