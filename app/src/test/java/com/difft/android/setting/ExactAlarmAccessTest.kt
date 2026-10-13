package com.difft.android.setting

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Channel-independent part of [ExactAlarmAccess]; the declared/undeclared cases live per channel. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExactAlarmAccessTest {

    @Test
    @Config(sdk = [30])
    fun belowS_isNotApplicable() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(ExactAlarmState.NotApplicable, ExactAlarmAccess.state(app))
    }
}
