package com.difft.android.me

import android.app.Activity
import android.util.TypedValue
import com.difft.android.databinding.MeFragmentBinding
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** The text-size setting scales the Me page's content, never its header title. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeTextScalingTest {

    private lateinit var controller: ActivityController<Activity>
    private lateinit var binding: MeFragmentBinding

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        binding = MeFragmentBinding.inflate(activity.layoutInflater)
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, controller.get().resources.displayMetrics)

    @Test
    fun `default size scales the rows to 16sp and keeps the 20sp title`() {
        MeTextScaling.apply(binding, isLarger = false)

        assertEquals(sp(16f), binding.tvTextSize.textSize, 0.5f)
        assertEquals(sp(14f), binding.tvProfile.textSize, 0.5f)
        assertEquals("the header title is not page content", sp(20f), binding.tvTitle.textSize, 0.5f)
    }

    @Test
    fun `large size scales the rows to 24sp and keeps the 20sp title`() {
        MeTextScaling.apply(binding, isLarger = true)

        assertEquals(sp(24f), binding.tvTextSize.textSize, 0.5f)
        assertEquals(sp(21f), binding.tvProfile.textSize, 0.5f)
        assertEquals("the header title is not page content", sp(20f), binding.tvTitle.textSize, 0.5f)
    }
}
