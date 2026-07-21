package com.difft.android.me

import android.app.Activity
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.difft.android.databinding.MeFragmentBinding
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * State-assertion for the cl_linked_devices row: inflates the real MeFragmentBinding and drives the
 * count via the real [linkedDevicesBadgeState] rule plus the same plumbing
 * [MeFragment.observeLinkedDevicesCount] applies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeFragmentLinkedDevicesRowTest {

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

    /** Same rule + plumbing as [MeFragment.observeLinkedDevicesCount]. */
    private fun applyCount(count: Int?) {
        val (visible, text) = linkedDevicesBadgeState(count)
        binding.tvLinkedDevicesCount.visibility = if (visible) View.VISIBLE else View.GONE
        binding.tvLinkedDevicesCount.text = text
    }

    @Test
    fun `S6 count 2 renders visible count text before the arrow`() {
        applyCount(2)
        assertEquals(View.VISIBLE, binding.tvLinkedDevicesCount.visibility)
        assertEquals("2", binding.tvLinkedDevicesCount.text.toString())
        assertEquals(View.VISIBLE, binding.tvLinkedDevices.visibility)
        assertEquals(View.VISIBLE, binding.ivLinkedDevicesArrow.visibility)
        // Count value sits immediately left of the arrow.
        val lp = binding.tvLinkedDevicesCount.layoutParams as ConstraintLayout.LayoutParams
        assertEquals(binding.ivLinkedDevicesArrow.id, lp.rightToLeft)
    }

    @Test
    fun `S7 count 0 hides count text but keeps title and arrow`() {
        applyCount(0)
        assertEquals(View.GONE, binding.tvLinkedDevicesCount.visibility)
        assertEquals(View.VISIBLE, binding.tvLinkedDevices.visibility)
        assertEquals(View.VISIBLE, binding.ivLinkedDevicesArrow.visibility)
    }
}
