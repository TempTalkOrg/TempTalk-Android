package com.difft.android.base.utils

import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T1-6/T1-7: [isAnimationsDisabled] is the closest Android equivalent of `prefers-reduced-motion`
 * — Settings.Global.ANIMATOR_DURATION_SCALE == 0 means the user disabled animations system-wide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnimationUtilTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `T1-6 returns true when ANIMATOR_DURATION_SCALE is 0`() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)

        assertTrue(isAnimationsDisabled(context))
    }

    @Test
    fun `T1-7 returns false when ANIMATOR_DURATION_SCALE is 1 (default)`() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

        assertFalse(isAnimationsDisabled(context))
    }
}
