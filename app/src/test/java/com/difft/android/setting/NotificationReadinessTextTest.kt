package com.difft.android.setting

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.notification.ReadinessIssue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Wording rules: the startup prompt covers only the full-screen permission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationReadinessTextTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `only the full-screen issue has a startup prompt`() {
        assertNotNull(NotificationReadinessText.promptMessage(context, ReadinessIssue.FULL_SCREEN_DENIED, "App"))
        ReadinessIssue.entries.filter { it != ReadinessIssue.FULL_SCREEN_DENIED }.forEach {
            assertNull(NotificationReadinessText.promptMessage(context, it, "App"), "issue=$it")
        }
    }
}
