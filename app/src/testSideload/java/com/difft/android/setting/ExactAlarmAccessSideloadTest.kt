package com.difft.android.setting

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.assertEquals

/** Sideload channels declare SCHEDULE_EXACT_ALARM, so the row reflects the grant state. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ExactAlarmAccessSideloadTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun declared_notGranted() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertEquals(ExactAlarmState.NotGranted, ExactAlarmAccess.state(app))
    }

    @Test
    fun declared_granted() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertEquals(ExactAlarmState.Granted, ExactAlarmAccess.state(app))
    }
}
